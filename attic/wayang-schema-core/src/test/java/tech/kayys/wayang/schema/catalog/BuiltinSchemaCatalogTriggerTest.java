package tech.kayys.wayang.schema.catalog;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinSchemaCatalogTriggerTest {

    @Test
    void shouldExposeAllTriggerSchemas() {
        Set<String> ids = BuiltinSchemaCatalog.ids();

        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_START));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_MANUAL));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_SCHEDULE));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_EMAIL));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_TELEGRAM));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_WEBSOCKET));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_WEBHOOK));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_EVENT));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_KAFKA));
        assertTrue(ids.contains(BuiltinSchemaCatalog.TRIGGER_FILE));

        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_START));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_MANUAL));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_SCHEDULE));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_EMAIL));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_TELEGRAM));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_WEBSOCKET));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_WEBHOOK));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_EVENT));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_KAFKA));
        assertNotNull(BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_FILE));
    }

    @Test
    void shouldExposeExpectedTriggerSchemaParameters() {
        String schedule = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_SCHEDULE);
        String email = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_EMAIL);
        String telegram = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_TELEGRAM);
        String websocket = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_WEBSOCKET);
        String webhook = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_WEBHOOK);
        String event = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_EVENT);
        String kafka = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_KAFKA);
        String file = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.TRIGGER_FILE);

        assertTrue(schedule.contains("intervalSeconds"));
        assertTrue(schedule.contains("cron"));
        assertTrue(schedule.contains("timezone"));

        assertTrue(email.contains("imapHost"));
        assertTrue(email.contains("imapPort"));
        assertTrue(email.contains("folder"));
        assertTrue(email.contains("subjectFilter"));

        assertTrue(telegram.contains("botToken"));
        assertTrue(telegram.contains("chatId"));
        assertTrue(telegram.contains("allowedUserId"));

        assertTrue(websocket.contains("path"));
        assertTrue(websocket.contains("channel"));

        assertTrue(webhook.contains("path"));
        assertTrue(webhook.contains("method"));
        assertTrue(webhook.contains("secret"));

        assertTrue(event.contains("eventName"));
        assertTrue(event.contains("eventSource"));

        assertTrue(kafka.contains("brokers"));
        assertTrue(kafka.contains("topic"));
        assertTrue(kafka.contains("groupId"));

        assertTrue(file.contains("path"));
        assertTrue(file.contains("pattern"));
        assertTrue(file.contains("pollSeconds"));
    }
}
