package tech.kayys.wayang.agent.testkit;

import java.util.List;
import java.util.function.Supplier;
import tech.kayys.wayang.agent.ConversationMemory;
import tech.kayys.wayang.agent.ConversationMemory.Turn;

/** What every {@link ConversationMemory} must do; run against each implementation (memory, file, database). */
public final class MemoryContracts {

    private MemoryContracts() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("conversation memory contract: " + what);
        }
    }

    public static void memory(Supplier<ConversationMemory> factory) {
        ConversationMemory m = factory.get();
        String ns = "t|p|a" + System.nanoTime(); // may share storage with other runs
        check(m.load(ns, "chat1", 5).isEmpty(), "an unknown conversation is empty");
        m.append(ns, "chat1", new Turn("hi", "hello", 1_000), 3);
        m.append(ns, "chat1", new Turn("how are you", "fine", 2_000), 3);
        List<Turn> t = m.load(ns, "chat1", 5);
        check(t.size() == 2 && t.get(0).user().equals("hi") && t.get(1).assistant().equals("fine"), "turns come back oldest first");
        check(t.get(1).atMs() == 2_000, "the time of each turn is kept");
        m.append(ns, "chat1", new Turn("c", "c2", 3_000), 3);
        m.append(ns, "chat1", new Turn("d", "d2", 4_000), 3);
        t = m.load(ns, "chat1", 10);
        check(t.size() == 3 && t.get(0).user().equals("how are you") && t.get(2).user().equals("d"), "only the last maxTurns turns are kept");
        check(m.load(ns, "chat1", 1).size() == 1 && m.load(ns, "chat1", 1).get(0).user().equals("d"), "load honours its own limit, newest turns");
        check(m.load(ns, "chat1", 0).isEmpty(), "zero turns asks for nothing");
        check(m.load(ns, "chat2", 5).isEmpty() && m.load(ns + "x", "chat1", 5).isEmpty(), "conversations and namespaces are separate");
        String big = "x".repeat(ConversationMemory.MAX_CHARS + 500);
        m.append(ns, "big", new Turn(big, big, 5_000), 3);
        check(m.load(ns, "big", 1).get(0).user().length() == ConversationMemory.MAX_CHARS, "a message is cut to the limit");
        check(m.clear(ns, "chat1") && m.load(ns, "chat1", 5).isEmpty(), "clear forgets a conversation");
        check(!m.clear(ns, "chat1"), "clearing nothing says so");
        check(!m.load(ns, "big", 1).isEmpty(), "clear touches only its conversation");
        m.append(ns, "old", new Turn("a", "b", 10), 5);
        m.append(ns, "old", new Turn("c", "d", 9_000_000), 5);
        check(m.purge(1_000_000) >= 1, "purge removes turns older than the cutoff");
        check(m.load(ns, "old", 5).size() == 1 && m.load(ns, "old", 5).get(0).user().equals("c"), "purge keeps newer turns");
    }
}
