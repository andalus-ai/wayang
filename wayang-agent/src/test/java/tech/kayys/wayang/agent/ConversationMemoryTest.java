package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.agent.testkit.MemoryContracts;

class ConversationMemoryTest {

    @Test
    void inMemoryKeepsTheContract() {
        MemoryContracts.memory(() -> new ConversationMemory.InMemory(100));
    }

    @Test
    void theLeastRecentlyUsedConversationsAreDroppedWhenFull() {
        var m = new ConversationMemory.InMemory(2);
        m.append("n", "a", new ConversationMemory.Turn("1", "1", 1), 5);
        m.append("n", "b", new ConversationMemory.Turn("2", "2", 2), 5);
        m.load("n", "a", 5); // a is now the most recently used
        m.append("n", "c", new ConversationMemory.Turn("3", "3", 3), 5);
        assertEquals(1, m.load("n", "a", 5).size());
        assertTrue(m.load("n", "b", 5).isEmpty(), "b was the least recently used");
        assertEquals(1, m.load("n", "c", 5).size());
    }

    @Test
    void turnsBecomeAlternatingChatMessages() {
        var msgs = ConversationMemory.asMessages(List.of(new ConversationMemory.Turn("q1", "a1", 1), new ConversationMemory.Turn("q2", "a2", 2)));
        assertEquals(List.of("user", "assistant", "user", "assistant"), msgs.stream().map(x -> x.get("role")).toList());
        assertEquals("q2", msgs.get(2).get("content"));
    }

    private static AgentSpec spec(Object memory) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("id", "a", "model", "openai/x"));
        if (memory != null) {
            m.put("options", Map.of("memory", memory));
        }
        return AgentSpecLoader.fromMap(m);
    }

    @Test
    void theMemoryOptionIsCheckedWhenTheAgentLoads() {
        assertNull(AgentMemory.of(spec(null)));
        var ok = AgentMemory.of(spec(Map.of("key", "{{ event.chatId }}")));
        assertEquals(10, ok.turns());
        assertEquals("{{ event.chatId }}", ok.keyTemplate());
        assertEquals(30, AgentMemory.of(spec(Map.of("key", "k", "turns", 30))).turns());
        for (Object bad : List.of("yes", Map.of(), Map.of("key", ""), Map.of("key", "k", "turns", 0), Map.of("key", "k", "turns", 51),
                Map.of("key", "k", "turns", "ten"), Map.of("key", "x".repeat(201)))) {
            assertThrows(AgentSpecException.class, () -> spec(bad), String.valueOf(bad));
        }
    }
}
