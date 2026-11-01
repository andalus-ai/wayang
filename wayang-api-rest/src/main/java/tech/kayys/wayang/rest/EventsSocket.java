package tech.kayys.wayang.rest;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import tech.kayys.wayang.api.WayangApi;
import tech.kayys.wayang.api.WsSession;

/**
 * Live run events for the Studio and Management console at {@code /ws/events}.
 * Authentication happens on the HTTP upgrade: native clients send the bearer token; browsers
 * (Flutter web) call {@code POST /api/v1/ws/ticket} and connect with {@code ?ticket=}. The permissions
 * are those at ticket/upgrade time and stay for the life of the connection. The protocol itself lives in {@link WsSession}.
 */
@WebSocket(path = "/ws/events")
public class EventsSocket {

    @Inject
    WayangApi api;
    @Inject
    Principals principals;
    @Inject
    Clock clock;
    @Inject
    WsTicketsHolder tickets;
    @Inject
    TenantGateConfig tenantGate;

    private final tech.kayys.wayang.api.Rooms rooms = new tech.kayys.wayang.api.Rooms();
    private final Map<String, WsSession> sessions = new ConcurrentHashMap<>();

    @OnOpen
    void open(WebSocketConnection c) {
        // Browsers cannot send an Authorization header on the upgrade, so they pass ?ticket= (see WsTicketResource).
        String ticket = ticketOf(c.handshakeRequest().query());
        var p = ticket != null ? tickets.tickets().redeem(ticket) : principals.current();
        tenantGate.gate().require(p.tenantId()); // suspended since the ticket was issued
        sessions.put(c.id(), new WsSession(api, p, text -> c.sendTextAndAwait(text), clock, rooms));
    }

    /** The {@code ticket} query parameter, or null. Other parameters are ignored. */
    static String ticketOf(String query) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("ticket=") && part.length() > 7) {
                return java.net.URLDecoder.decode(part.substring(7), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @OnTextMessage
    void message(WebSocketConnection c, String text) {
        WsSession s = sessions.get(c.id());
        if (s != null) {
            s.onText(text);
        }
    }

    @OnClose
    void close(WebSocketConnection c) {
        WsSession s = sessions.remove(c.id());
        if (s != null) {
            s.close();
        }
    }
}
