package tech.kayys.wayang.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Transport-neutral WebSocket session: one per connection. The transport
 * passes each received text frame to {@link #onText} and supplies a sender for
 * outgoing text frames.
 *
 * <p>Client frames: {@code {"id":"1","op":"subscribe","runId":"r1"}} (omit
 * {@code runId} for all runs of the tenant), {@code {"op":"unsubscribe","runId":"r1"}},
 * {@code {"op":"ping"}}. Replies echo {@code id}: {@code {"id":"1","ok":true}} or
 * {@code {"id":"1","ok":false,"error":"..."}}. Server pushes
 * {@code {"event":{...}}}. If the client reads too slowly, oldest events are
 * dropped and an {@code OVERFLOW} event reports how many.
 */
public final class WsSession implements AutoCloseable {

    public static final int MAX_SUBSCRIPTIONS = 20;
    public static final int MAX_FRAME_CHARS = 16_384;
    public static final int BUFFER = 256;

    private final WayangApi api;
    private final Principal principal;
    private final Consumer<String> sender;
    private final Clock clock;
    private final Map<String, AutoCloseable> subs = new LinkedHashMap<>();
    private final EventBus.BoundedBuffer buffer = new EventBus.BoundedBuffer(BUFFER);
    private final AtomicBoolean pumping = new AtomicBoolean();
    private volatile boolean closed;

    public static final int MAX_ROOMS = 10;
    public static final int SAY_PER_SECOND = 20;

    private final Rooms rooms;
    private final String connection = java.util.UUID.randomUUID().toString();
    private final java.util.Set<String> joined = new java.util.LinkedHashSet<>();
    private long sayWindowStart;
    private int sayInWindow;

    public WsSession(WayangApi api, Principal principal, Consumer<String> sender, Clock clock) {
        this(api, principal, sender, clock, null);
    }

    /** With {@code rooms} non-null the join/leave/say/who ops are enabled (collaboration). */
    public WsSession(WayangApi api, Principal principal, Consumer<String> sender, Clock clock, Rooms rooms) {
        this.rooms = rooms;
        this.api = api;
        this.principal = principal;
        this.sender = sender;
        this.clock = clock;
    }

    public synchronized void onText(String frame) {
        if (closed) {
            return;
        }
        Object id = null;
        try {
            if (frame == null || frame.length() > MAX_FRAME_CHARS) {
                throw ApiException.badRequest("Frame too large");
            }
            Object parsed = Json.read(frame);
            if (!(parsed instanceof Map<?, ?> m)) {
                throw ApiException.badRequest("Frame must be a JSON object");
            }
            id = m.get("id");
            Object op = m.get("op");
            Object runId = m.get("runId");
            if (runId != null && !(runId instanceof String)) {
                throw ApiException.badRequest("runId must be a string");
            }
            if (!(op instanceof String o)) {
                throw ApiException.badRequest("Missing op");
            }
            switch (o) {
                case "ping" -> reply(id, true, null);
                case "subscribe" -> {
                    String key = runId == null ? "*" : (String) runId;
                    if (subs.containsKey(key)) {
                        reply(id, true, null);
                        return;
                    }
                    if (subs.size() >= MAX_SUBSCRIPTIONS) {
                        throw ApiException.badRequest("Too many subscriptions (max " + MAX_SUBSCRIPTIONS + ")");
                    }
                    subs.put(key, api.subscribe(principal, (String) runId, this::offer));
                    reply(id, true, null);
                }
                case "unsubscribe" -> {
                    AutoCloseable c = subs.remove(runId == null ? "*" : (String) runId);
                    if (c != null) {
                        c.close();
                    }
                    reply(id, true, null);
                }
                case "join", "leave", "say", "who" -> room(o, id, m);
                default -> throw ApiException.badRequest("Unknown op '" + o + "'");
            }
        } catch (ApiException e) {
            reply(id, false, e.getMessage());
        } catch (Exception e) {
            reply(id, false, "Bad frame: " + e.getMessage());
        }
    }

    private void room(String op, Object id, Map<?, ?> m) {
        if (rooms == null) {
            throw ApiException.badRequest("Unknown op '" + op + "'");
        }
        if (!(m.get("room") instanceof String room)) {
            throw ApiException.badRequest("room must be a string");
        }
        switch (op) {
            case "join" -> {
                if (!joined.contains(room) && joined.size() >= MAX_ROOMS) {
                    throw ApiException.badRequest("Too many rooms (max " + MAX_ROOMS + ")");
                }
                List<String> members = rooms.join(principal, connection, room, this::sendRaw);
                joined.add(room);
                Map<String, Object> r = new LinkedHashMap<>();
                if (id != null) {
                    r.put("id", id);
                }
                r.put("ok", true);
                r.put("members", members);
                sender.accept(Json.write(r));
            }
            case "leave" -> {
                rooms.leave(principal, connection, room);
                joined.remove(room);
                reply(id, true, null);
            }
            case "who" -> {
                Map<String, Object> r = new LinkedHashMap<>();
                if (id != null) {
                    r.put("id", id);
                }
                r.put("ok", true);
                r.put("members", rooms.who(principal, room));
                sender.accept(Json.write(r));
            }
            default -> {
                long now = clock.millis();
                if (now - sayWindowStart >= 1000) {
                    sayWindowStart = now;
                    sayInWindow = 0;
                }
                if (++sayInWindow > SAY_PER_SECOND) {
                    throw ApiException.badRequest("Too many messages; slow down");
                }
                rooms.say(principal, connection, room, m.get("payload"));
                reply(id, true, null);
            }
        }
    }

    private synchronized void sendRaw(String frame) {
        if (!closed) {
            sender.accept(frame);
        }
    }

    private void offer(RunEvent e) {
        if (closed) {
            return;
        }
        buffer.offer(e);
        pump();
    }

    private void pump() {
        while (!closed && pumping.compareAndSet(false, true)) {
            try {
                List<RunEvent> batch = buffer.drain(principal.tenantId(), clock.instant());
                for (RunEvent ev : batch) {
                    sender.accept(Json.write(Map.of("event", ev.toMap())));
                }
            } finally {
                pumping.set(false);
            }
            if (buffer.size() == 0) {
                return;
            }
        }
    }

    private void reply(Object id, boolean ok, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) {
            m.put("id", id);
        }
        m.put("ok", ok);
        if (error != null) {
            m.put("error", error);
        }
        sender.accept(Json.write(m));
    }

    public synchronized int subscriptionCount() {
        return subs.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (rooms != null) {
            for (String r : new ArrayList<>(joined)) {
                rooms.leave(principal, connection, r);
            }
            joined.clear();
        }
        for (AutoCloseable c : new ArrayList<>(subs.values())) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
        subs.clear();
    }
}
