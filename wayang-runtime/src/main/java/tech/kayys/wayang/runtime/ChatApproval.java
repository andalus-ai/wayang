package tech.kayys.wayang.runtime;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Approvals answered in the chat itself. A trigger with {@code approvals: true} treats a plain "yes" or "no" from the allowed chat as the answer
 * to the oldest approval that run of the same trigger is waiting on in that chat, instead of as a new message for the agent.
 *
 * <p>Only an exact short answer counts, so a sentence that happens to contain "no" is still a message. Nothing here reaches outside the chat that
 * already passed the trigger's allow-list.
 */
public final class ChatApproval {

    private static final List<String> YES = List.of("yes", "y", "ok", "okay", "approve", "approved", "send", "send it");
    private static final List<String> NO = List.of("no", "n", "reject", "rejected", "cancel", "drop", "stop", "don't send", "dont send");

    private ChatApproval() {
    }

    /** @return TRUE for an approval, FALSE for a refusal, empty when the text is an ordinary message */
    public static Optional<Boolean> decision(Object text) {
        if (!(text instanceof String s)) {
            return Optional.empty();
        }
        String t = s.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s.!]+$", "");
        if (t.isEmpty() || t.length() > 20) {
            return Optional.empty();
        }
        if (YES.contains(t)) {
            return Optional.of(Boolean.TRUE);
        }
        return NO.contains(t) ? Optional.of(Boolean.FALSE) : Optional.empty();
    }

    /** The event field that identifies the conversation for a source or provider, or null when approvals are not supported there. */
    public static String conversationField(Map<String, Object> triggerConfig) {
        Object src = triggerConfig.get("source") != null ? triggerConfig.get("source") : triggerConfig.get("auth");
        return switch (String.valueOf(src)) {
            case "telegram" -> "chatId";
            case "slack" -> "channel";
            default -> null;
        };
    }

    /** True when {@code run} (its variables) belongs to this trigger and conversation. */
    public static boolean sameConversation(Map<String, Object> runVariables, String triggerId, String field, Object conversation) {
        if (conversation == null || !triggerId.equals(runVariables.get("trigger"))
                || !(runVariables.get("event") instanceof Map<?, ?> ev)) {
            return false;
        }
        Object mine = ev.get(field);
        return mine != null && String.valueOf(mine).equals(String.valueOf(conversation));
    }

}
