package tech.kayys.wayang.rest;

import java.time.Clock;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import tech.kayys.wayang.api.WsTickets;

/** The one in-memory ticket book of this node, shared by the ticket endpoint and the socket. */
@ApplicationScoped
public class WsTicketsHolder {

    @Inject
    Clock clock;

    private volatile WsTickets tickets;

    public synchronized WsTickets tickets() {
        if (tickets == null) {
            tickets = new WsTickets(clock);
        }
        return tickets;
    }
}
