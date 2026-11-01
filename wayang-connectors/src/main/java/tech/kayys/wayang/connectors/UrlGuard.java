package tech.kayys.wayang.connectors;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Server-side request forgery guard for outbound calls whose URL is chosen by a process author. Only http/https;
 * no credentials in the URL; the host must resolve exclusively to public addresses unless the operator
 * allow-listed it. Residual risk: the HTTP client resolves the name again when it connects, so a hostile DNS
 * server could answer differently the second time (DNS rebinding); run the engine behind an egress firewall
 * for hard guarantees.
 */
public final class UrlGuard {

    private final boolean allowPrivate;
    private final Set<String> allowedHosts;

    /**
     * @param allowPrivate  operator switch: permit private/loopback targets (dev, or in-cluster services)
     * @param allowedHosts  exact host names that are permitted even if they resolve to private addresses
     */
    public UrlGuard(boolean allowPrivate, Set<String> allowedHosts) {
        this.allowPrivate = allowPrivate;
        this.allowedHosts = Set.copyOf(allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList());
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
        if (b.length == 16) { // IPv6: unique local fc00::/7; IPv4-mapped handled by the JDK (becomes Inet4Address)
            return (b[0] & 0xFE) == 0xFC;
        }
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
        return b0 == 0                                  // 0.0.0.0/8
                || (b0 == 100 && b1 >= 64 && b1 <= 127) // 100.64.0.0/10 carrier-grade NAT
                || (b0 == 192 && b1 == 0 && (b[2] & 0xFF) == 0) // 192.0.0.0/24
                || (b0 == 198 && (b1 == 18 || b1 == 19)) // 198.18.0.0/15 benchmarking
                || b0 >= 240;                           // reserved + broadcast
    }
}
