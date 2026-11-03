package tech.kayys.wayang.api.run;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.definition.Studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Collaboration rooms for the Studio (who is editing which definition, live "draft changed" hints). Rooms are
 * namespaced by tenant internally, so equal names in two tenants never meet; the identity shown to other members
 * is the authenticated principal, never client-supplied. Messages are ephemeral and unpersisted. Viewers can join
 * and observe; only principals with DEFINITION_WRITE can speak.
 */
public final class Rooms {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9:_./-]{1,200}");
    public static final int MAX_MEMBERS = 50;
    public static final int MAX_PAYLOAD_CHARS = 4096;

    /** One connection's membership handle. */
    private record Member(String tenant, String userId, String connection, Consumer<String> sender) {
    }

    private final Map<String, Map<String, Member>> rooms = new ConcurrentHashMap<>();

    /** @return the room's members (user ids, distinct, sorted) after the join */
    public List<String> join(Principal p, String connection, String room, Consumer<String> sender) {
        require(p, Permission.DEFINITION_READ);
        checkName(room);
        String key = key(p.tenantId(), room);
        List<Member> others;
        synchronized (this) {
            Map<String, Member> members = rooms.computeIfAbsent(key, k -> new LinkedHashMap<>());
            if (!members.containsKey(connection) && members.size() >= MAX_MEMBERS) {
                throw ApiException.badRequest("Room is full");
            }
            boolean fresh = members.putIfAbsent(connection, new Member(p.tenantId(), p.userId(), connection, sender)) == null;
            others = fresh ? new ArrayList<>(members.values()) : List.of();
        }
        if (!others.isEmpty()) {
            send(others, connection, room, "joined", p.userId(), null);
        }
        return who(p, room);
    }

    public void leave(Principal p, String connection, String room) {
        String key = key(p.tenantId(), room);
        List<Member> rest;
        boolean was;
        synchronized (this) {
            Map<String, Member> members = rooms.get(key);
            if (members == null) {
                return;
            }
            was = members.remove(connection) != null;
            rest = new ArrayList<>(members.values());
            if (members.isEmpty()) {
                rooms.remove(key);
            }
        }
        if (was) {
            send(rest, connection, room, "left", p.userId(), null);
        }
    }

    /** Broadcasts to the other members. Caller must be a member and hold DEFINITION_WRITE. */
    public int say(Principal p, String connection, String room, Object payload) {
        require(p, Permission.DEFINITION_WRITE);
        String text = Json.write(payload == null ? Map.of() : payload);
        if (text.length() > MAX_PAYLOAD_CHARS) {
            throw ApiException.badRequest("Payload too large (max " + MAX_PAYLOAD_CHARS + " characters)");
        }
        List<Member> all;
        synchronized (this) {
            Map<String, Member> members = rooms.get(key(p.tenantId(), room));
            if (members == null || !members.containsKey(connection)) {
                throw ApiException.badRequest("Join the room first");
            }
            all = new ArrayList<>(members.values());
        }
        return send(all, connection, room, "msg", p.userId(), payload == null ? Map.of() : payload);
    }

    public synchronized List<String> who(Principal p, String room) {
        require(p, Permission.DEFINITION_READ);
        Map<String, Member> members = rooms.get(key(p.tenantId(), room));
        return members == null ? List.of() : members.values().stream().map(Member::userId).distinct().sorted().toList();
    }

    public synchronized int roomCount() {
        return rooms.size();
    }

    private static int send(List<Member> targets, String exceptConnection, String room, String type, String user,
            Object payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("room", room);
        m.put("type", type);
        m.put("user", user);
        if (payload != null) {
            m.put("payload", payload);
        }
        String frame = Json.write(Map.of("room", m));
        int n = 0;
        for (Member t : targets) {
            if (t.connection().equals(exceptConnection)) {
                continue;
            }
            try {
                t.sender().accept(frame);
                n++;
            } catch (RuntimeException ignored) {
                // a dead socket must not break delivery to the others; it is removed on close
            }
        }
        return n;
    }

    private static String key(String tenant, String room) {
        return tenant + "\0" + room;
    }

    private static void checkName(String room) {
        if (room == null || !NAME.matcher(room).matches()) {
            throw ApiException.badRequest("Room name must match " + NAME.pattern());
        }
    }

    private static void require(Principal p, Permission perm) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(perm)) {
            throw ApiException.forbidden(perm);
        }
    }
}
