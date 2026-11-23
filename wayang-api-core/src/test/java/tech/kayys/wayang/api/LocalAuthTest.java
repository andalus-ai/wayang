package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocalAuthTest {

    private static final String KEY = "k-0123456789-abcdefghij-xyz";

    @Test
    void theRightKeyIsAnAdministratorOfTheLocalTenant() {
        Principal p = new LocalAuth(KEY, null).resolve("Bearer " + KEY);
        assertEquals("local", p.tenantId());
        assertTrue(p.can(Permission.RUN_START));
        assertEquals("acme", new LocalAuth(KEY, "acme").resolve("bearer " + KEY).tenantId());
    }

    @Test
    void anythingElseIsUnauthorizedAndTheKeyIsNeverEchoed() {
        LocalAuth a = new LocalAuth(KEY, null);
        for (String bad : new String[] {null, "", "Bearer ", "Bearer wrong", "Basic " + KEY, KEY, "Bearer " + KEY + "x"}) {
            ApiException e = assertThrows(ApiException.class, () -> a.resolve(bad), String.valueOf(bad));
            assertEquals(ApiException.Code.UNAUTHORIZED, e.code());
            assertFalse(String.valueOf(e.getMessage()).contains(KEY));
        }
    }

    @Test
    void aMissingShortOrBadlyScopedConfigurationRefusesToStart() {
        assertThrows(IllegalArgumentException.class, () -> new LocalAuth(null, null));
        assertThrows(IllegalArgumentException.class, () -> new LocalAuth("short", null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new LocalAuth(KEY, "bad tenant!"));
        assertFalse(e.getMessage().contains(KEY));
    }
}
