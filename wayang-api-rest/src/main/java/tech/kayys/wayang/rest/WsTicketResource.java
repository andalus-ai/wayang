package tech.kayys.wayang.rest;

import java.time.Clock;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.WsTickets;

/**
 * {@code POST /api/v1/ws/ticket}: a 30-second single-use ticket for {@code /ws/events?ticket=...}, for browsers that cannot send an
 * Authorization header on a WebSocket. Needs RUN_READ somewhere (the socket is for run events and collaboration).
 */
@Path("/api/v1/ws/ticket")
@Blocking
public class WsTicketResource {

    @Inject
    Principals principals;
    @Inject
    WsTicketsHolder holder;
    @Inject
    Clock clock;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> issue() {
        var p = principals.current();
        if (!p.canAnywhere(Permission.RUN_READ) && !p.canAnywhere(Permission.DEFINITION_READ)) {
            throw tech.kayys.wayang.api.ApiException.forbidden(Permission.RUN_READ);
        }
        WsTickets.Ticket t = holder.tickets().issue(p);
        return Map.of("ticket", t.value(), "expiresAt", t.expires().toString(), "expiresInSeconds", WsTickets.TTL.toSeconds());
    }
}
