package tech.kayys.wayang.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an agent remembers of one conversation between runs: the last few turns, per tenant, process, agent and conversation key
 * (for a chat bot, the chat id). A turn is one message of the person and the agent's answer to it. It holds what people typed, so it is
 * bounded (turns per conversation, characters per message, conversations kept) and meant to be dropped after a retention period.
 *
 * <p>Only what the person said and what the agent answered is kept, never tool output.
 */
public interface ConversationMemory {

    int MAX_CHARS = 8_000;

    /** One exchange. */
    record Turn(String user, String assistant, long atMs) {
        public Turn {
            user = cut(user);
            assistant = cut(assistant);
        }
    }

    /** The last {@code maxTurns} turns, oldest first; empty for an unknown conversation. */
    List<Turn> load(String namespace, String key, int maxTurns);

    /** Adds a turn and drops the oldest beyond {@code maxTurns}. */
    void append(String namespace, String key, Turn turn, int maxTurns);

    /** Forgets one conversation. @return true when there was something to forget */
    boolean clear(String namespace, String key);

    /** Drops turns older than {@code cutoffMs} (retention). @return how many turns were removed */
    int purge(long cutoffMs);

    static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS);
    }

    /** The turns as chat messages, oldest first: user, assistant, user, assistant... */
    static List<Map<String, Object>> asMessages(List<Turn> turns) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Turn t : turns) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("role", "user");
            u.put("content", t.user());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("role", "assistant");
            a.put("content", t.assistant());
            out.add(u);
            out.add(a);
        }
        return out;
    }

    /** In memory, per process: bounded to {@code maxConversations} (the least recently used are dropped). */
    final class InMemory implements ConversationMemory {
        private final int maxConversations;
        private final java.util.LinkedHashMap<String, List<Turn>> conversations;

        public InMemory(int maxConversations) {
            this.maxConversations = Math.max(1, maxConversations);
            this.conversations = new java.util.LinkedHashMap<>(16, 0.75f, true);
        }

        @Override
        public synchronized List<Turn> load(String ns, String key, int maxTurns) {
            List<Turn> t = conversations.get(ns + '\0' + key);
            if (t == null) {
                return List.of();
            }
            return List.copyOf(t.subList(Math.max(0, t.size() - Math.max(0, maxTurns)), t.size()));
        }

        @Override
        public synchronized void append(String ns, String key, Turn turn, int maxTurns) {
            List<Turn> t = conversations.computeIfAbsent(ns + '\0' + key, k -> new ArrayList<>());
            t.add(turn);
            while (t.size() > Math.max(1, maxTurns)) {
                t.remove(0);
            }
            while (conversations.size() > maxConversations) {
                conversations.remove(conversations.keySet().iterator().next());
            }
        }

        @Override
        public synchronized boolean clear(String ns, String key) {
            return conversations.remove(ns + '\0' + key) != null;
        }

        @Override
        public synchronized int purge(long cutoffMs) {
            int removed = 0;
            for (List<Turn> t : conversations.values()) {
                int before = t.size();
                t.removeIf(x -> x.atMs() < cutoffMs);
                removed += before - t.size();
            }
            conversations.values().removeIf(List::isEmpty);
            return removed;
        }
    }
}
