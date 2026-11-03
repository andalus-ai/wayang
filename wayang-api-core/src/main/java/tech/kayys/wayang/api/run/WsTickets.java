package tech.kayys.wayang.api.run;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Short-lived, single-use tickets that let a browser open the WebSocket (browsers cannot send an {@code Authorization}
 * header on a WebSocket upgrade). A caller authenticated the normal way asks for a ticket, then connects with
 * {@code /ws/events?ticket=...}. A ticket is 256 random bits, valid for {@link #TTL}, redeemable once, and bound to the
 * principal as resolved at issue time. Only a hash is kept, so a memory dump does not yield usable tickets. Per node:
 * behind several nodes the redeeming request must reach the issuing node (sticky routing) or the ticket is refused.
 */
public final class WsTickets {

    public static final Duration TTL = Duration.ofSeconds(30);
    public static final int MAX_OUTSTANDING = 10_000;
    public static final int MAX_PER_PRINCIPAL = 20;

    public record Ticket(String value, Instant expires) {
    }

    private record Entry(Principal principal, Instant expires) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> byHash = new LinkedHashMap<>();

    public WsTickets(Clock clock) {
        this.clock = clock;
    }

    public synchronized Ticket issue(Principal p) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        purge();
        long mine = byHash.values().stream().filter(e -> e.principal().tenantId().equals(p.tenantId()) && e.principal().userId().equals(p.userId())).count();
        if (mine >= MAX_PER_PRINCIPAL || byHash.size() >= MAX_OUTSTANDING) {
            throw new ApiException(ApiException.Code.CONFLICT, "Too many outstanding tickets; connect with the ones you have or wait");
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant exp = clock.instant().plus(TTL);
        byHash.put(hash(value), new Entry(p, exp));
        return new Ticket(value, exp);
    }

    /** Consumes the ticket. Unknown, expired and already-used tickets all look the same: UNAUTHORIZED. */
    public synchronized Principal redeem(String value) {
        Entry e = value == null || value.length() > 100 ? null : byHash.remove(hash(value));
        if (e == null || !e.expires().isAfter(clock.instant())) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Invalid or expired ticket");
        }
        return e.principal();
    }

    private void purge() {
        Instant now = clock.instant();
        byHash.values().removeIf(e -> !e.expires().isAfter(now));
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
