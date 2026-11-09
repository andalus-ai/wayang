package tech.kayys.wayang.connectors;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side request forgery guard for outbound calls whose URL is chosen by a process author. Only http/https;
 * no credentials in the URL; the host must resolve exclusively to public addresses unless the operator
 * allow-listed it.
 *
 * <p>DNS rebinding: the HTTP client resolves the name again when it connects. Both lookups go through the JVM's address cache
 * ({@code networkaddress.cache.ttl}, 30 seconds by default), so the second one gets the answer the guard just checked, and a hostile DNS
 * server cannot flip it in between. That protection is gone when the cache is switched off ({@code 0}): {@link #dnsAnswersArePinned()}
 * says which case this JVM is in, and the constructor warns once when it is not. The remaining sliver is a cache entry expiring between
 * the check and the connect; an egress firewall closes that too.
 */
public final class UrlGuard {

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    private final boolean allowPrivate;
    private final Set<String> allowedHosts;

    /**
     * @param allowPrivate  operator switch: permit private/loopback targets (dev, or in-cluster services)
     * @param allowedHosts  exact host names that are permitted even if they resolve to private addresses
     */
    public UrlGuard(boolean allowPrivate, Set<String> allowedHosts) {
        if (!dnsAnswersArePinned() && WARNED.compareAndSet(false, true)) {
            System.getLogger(UrlGuard.class.getName()).log(System.Logger.Level.WARNING,
                    "The JVM address cache is switched off (networkaddress.cache.ttl=0): the URL guard cannot pin DNS answers, so DNS rebinding is possible. "
                            + "Leave the cache on (the default) or run behind an egress firewall.");
        }
        this.allowPrivate = allowPrivate;
        this.allowedHosts = Set.copyOf(allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList());
    }

    /** Whether the JVM caches name lookups, so the second lookup (the connect) returns what the guard checked. */
    public static boolean dnsAnswersArePinned() {
        return dnsAnswersArePinned(System.getProperty("sun.net.inetaddr.ttl"), java.security.Security.getProperty("networkaddress.cache.ttl"));
    }

    /** @param systemTtl the {@code sun.net.inetaddr.ttl} property, which wins when set; @param securityTtl the {@code networkaddress.cache.ttl} security property */
    static boolean dnsAnswersArePinned(String systemTtl, String securityTtl) {
        String v = systemTtl != null ? systemTtl : securityTtl;
        if (v == null) {
            return true; // the JDK default: 30 seconds
        }
        try {
            return Integer.parseInt(v.strip()) != 0; // negative = cached forever
        } catch (NumberFormatException e) {
            return true; // the JDK ignores a bad value and keeps its default
        }
    }

    public static UrlGuard strict() {
        return new UrlGuard(false, Set.of());
    }

    /** @throws IllegalArgumentException with a message that is safe to show to the author */
    public URI check(String url) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid URL");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("Only http and https URLs are allowed");
        }
        if (u.getUserInfo() != null) {
            throw new IllegalArgumentException("Credentials in the URL are not allowed; use headers");
        }
        String host = u.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL has no host");
        }
        if (allowPrivate || allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return u;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Host cannot be resolved");
        }
        for (InetAddress a : addrs) {
            if (isInternal(a)) {
                throw new IllegalArgumentException("Calls to internal addresses are not allowed");
            }
        }
        return u;
    }

    /**
     * For raw-socket protocols (SMTP, IMAP): resolves the host once and returns the address to connect to, so the check and the
     * connection use the same answer (no DNS rebinding window).
     *
     * @throws IllegalArgumentException with a message that is safe to show to the author
     */
    public InetAddress checkHost(String host) {
        if (host == null || host.isBlank() || host.length() > 253 || !host.matches("[A-Za-z0-9.:\\-\\[\\]]+")) {
            throw new IllegalArgumentException("Invalid host");
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Host cannot be resolved");
        }
        if (!allowPrivate && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            for (InetAddress a : addrs) {
                if (isInternal(a)) {
                    throw new IllegalArgumentException("Connections to internal addresses are not allowed");
                }
            }
        }
        return addrs[0];
    }

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 16) {
            return internalV6(b);
        }
        return internalV4(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
    }

    private static boolean internalV4(int b0, int b1, int b2) {
        return b0 == 0                                       // 0.0.0.0/8
                || b0 == 10 || b0 == 127                     // private, loopback (the JDK flags these too; kept for embedded addresses)
                || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168)
                || (b0 == 169 && b1 == 254)                  // link local, cloud metadata
                || (b0 == 100 && b1 >= 64 && b1 <= 127)      // 100.64.0.0/10 carrier-grade NAT
                || (b0 == 192 && b1 == 0 && b2 == 0)         // 192.0.0.0/24
                || (b0 == 192 && b1 == 0 && b2 == 2)         // documentation
                || (b0 == 192 && b1 == 88 && b2 == 99)       // 6to4 relay
                || (b0 == 198 && (b1 == 18 || b1 == 19))     // 198.18.0.0/15 benchmarking
                || (b0 == 198 && b1 == 51 && b2 == 100)      // documentation
                || (b0 == 203 && b1 == 0 && b2 == 113)       // documentation
                || b0 >= 224;                                // multicast, reserved, broadcast
    }

    /**
     * IPv6: besides loopback / link local / unique local, every form that carries an IPv4 address inside it is judged by that
     * address, because a network with NAT64 or a 6to4 relay would deliver such a packet to the embedded (possibly internal) target.
     */
    private static boolean internalV6(byte[] b) {
        int w0 = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        int w1 = ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        boolean zeros80 = true;
        for (int i = 0; i < 10; i++) {
            zeros80 &= b[i] == 0;
        }
        if ((b[0] & 0xFE) == 0xFC) {                      // fc00::/7 unique local (also AWS IPv6 metadata fd00:ec2::254)
            return true;
        }
        if (w0 == 0x2001 && w1 == 0x0000) {               // Teredo tunnels
            return true;
        }
        if (w0 == 0x2001 && w1 == 0x0DB8) {               // documentation
            return true;
        }
        if (w0 == 0x0100 && w1 == 0 && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0) { // 100::/64 discard-only
            return true;
        }
        boolean zeros96 = zeros80 && b[10] == 0 && b[11] == 0;
        if (zeros96) {                                    // ::a.b.c.d IPv4-compatible (deprecated) and ::
            return internalV4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF);
        }
        if (zeros80 && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) { // ::ffff:a.b.c.d (the JDK normally converts it to IPv4)
            return internalV4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF);
        }
        if (w0 == 0x0064 && w1 == 0xFF9B) {               // NAT64
            boolean wellKnown = true;                     // 64:ff9b::/96 embeds the IPv4 address in its last 32 bits
            for (int i = 4; i < 12; i++) {
                wellKnown &= b[i] == 0;
            }
            return !wellKnown || internalV4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF); // 64:ff9b:1::/48 (local use) is never public
        }
        if (w0 == 0x2002) {                               // 6to4: 2002:AABB:CCDD::/48 embeds the IPv4 address
            return internalV4(b[2] & 0xFF, b[3] & 0xFF, b[4] & 0xFF);
        }
        return false;
    }
}
