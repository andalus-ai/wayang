package tech.kayys.wayang.cel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CelProviderCapabilitiesTest {
    @Test
    void unqualifiedProviderDefaultsFailClosed() {
        var capabilities = CelProviderCapabilities.unqualified();

        assertFalse(capabilities.compilerQualified());
        assertFalse(capabilities.evaluatorQualified());
        assertTrue(capabilities.compilerGaps().contains("STATIC_TYPE_CHECKING_UNVERIFIED"));
        assertTrue(capabilities.evaluatorGaps().contains("HARD_EVALUATION_COST_LIMIT_UNVERIFIED"));
    }

    @Test
    void compilerAndEvaluatorHaveDistinctMandatoryGates() {
        var capabilities = new CelProviderCapabilities(
                true, true, false, false, false, false, true, true);

        assertTrue(capabilities.compilerQualified());
        assertFalse(capabilities.evaluatorQualified());
        assertEquals(List.of(
                "HARD_EVALUATION_COST_LIMIT_UNVERIFIED",
                "BOUNDED_RESULT_SIZE_UNVERIFIED",
                "COOPERATIVE_CANCELLATION_UNVERIFIED",
                "DETERMINISTIC_FUNCTION_PROFILE_UNVERIFIED"), capabilities.evaluatorGaps());
    }

    @Test
    void completeCapabilitySetPassesBothGateSets() {
        var capabilities = new CelProviderCapabilities(
                true, true, true, true, true, true, true, true);

        assertTrue(capabilities.compilerQualified());
        assertTrue(capabilities.evaluatorQualified());
        assertTrue(capabilities.compilerGaps().isEmpty());
        assertTrue(capabilities.evaluatorGaps().isEmpty());
    }
}
