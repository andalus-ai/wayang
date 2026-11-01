package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ChatApprovalTest {

    @Test
    void onlyAShortPlainAnswerCounts() {
        for (String yes : new String[] {"yes", "Yes!", " Y ", "OK.", "approve", "Send it"}) {
            assertEquals(Optional.of(true), ChatApproval.decision(yes), yes);
        }
        for (String no : new String[] {"no", "NO!!", "n", "Cancel", "don't send", "drop"}) {
            assertEquals(Optional.of(false), ChatApproval.decision(no), no);
        }
        for (String other : new String[] {"", "  ", "yes please send it now", "no thanks", "maybe", "yesterday", "can you say yes or no"}) {
            assertEquals(Optional.empty(), ChatApproval.decision(other), other);
        }
        assertEquals(Optional.empty(), ChatApproval.decision(null));
        assertEquals(Optional.empty(), ChatApproval.decision(42));
    }

    @Test
    void theConversationFieldDependsOnTheChannel() {
        assertEquals("chatId", ChatApproval.conversationField(Map.of("source", "telegram")));
        assertEquals("channel", ChatApproval.conversationField(Map.of("auth", "slack")));
        assertNull(ChatApproval.conversationField(Map.of("source", "rss")));
        assertNull(ChatApproval.conversationField(Map.of()));
    }

    @Test
    void aRunBelongsToTheSameTriggerAndConversationOnly() {
        Map<String, Object> vars = Map.of("trigger", "t1", "event", Map.of("chatId", 7L, "text", "hi"));
        assertTrue(ChatApproval.sameConversation(vars, "t1", "chatId", "7"), "number and text compare equal");
        assertFalse(ChatApproval.sameConversation(vars, "t2", "chatId", 7L));
        assertFalse(ChatApproval.sameConversation(vars, "t1", "chatId", 8L));
        assertFalse(ChatApproval.sameConversation(vars, "t1", "chatId", null));
        assertFalse(ChatApproval.sameConversation(Map.of("trigger", "t1"), "t1", "chatId", 7L));
        assertFalse(ChatApproval.sameConversation(Map.of("trigger", "t1", "event", Map.of()), "t1", "chatId", 7L));
    }
}
