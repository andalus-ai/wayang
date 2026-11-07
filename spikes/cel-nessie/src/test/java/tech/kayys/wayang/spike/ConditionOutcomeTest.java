package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.projectnessie.cel.common.types.BoolT;
import org.projectnessie.cel.common.types.Err;
import org.projectnessie.cel.common.types.NullT;
import org.projectnessie.cel.common.types.UnknownT;

class ConditionOutcomeTest {

    @Test
    void booleanFalseIsAValidDecisionNotAnEvaluationFailure() {
        ConditionOutcome outcome = ConditionOutcome.fromCelValue(BoolT.False);

        assertEquals(ConditionOutcome.Kind.FALSE, outcome.kind());
        assertEquals(false, outcome.booleanValue());
        assertNull(outcome.diagnosticCode());
    }

    @Test
    void booleanTrueIsAValidDecision() {
        ConditionOutcome outcome = ConditionOutcome.fromCelValue(BoolT.True);

        assertEquals(ConditionOutcome.Kind.TRUE, outcome.kind());
        assertTrue(outcome.booleanValue());
    }

    @Test
    void celErrorUnknownNullAndNonBooleanRemainDistinct() {
        assertEquals(ConditionOutcome.Kind.ERROR,
                ConditionOutcome.fromCelValue(Err.newErr("internal detail")).kind());
        assertEquals(ConditionOutcome.Kind.UNKNOWN,
                ConditionOutcome.fromCelValue(UnknownT.UnknownOf(1)).kind());
        assertEquals(ConditionOutcome.Kind.NULL,
                ConditionOutcome.fromCelValue(NullT.NullValue).kind());
        assertEquals(ConditionOutcome.Kind.NON_BOOLEAN,
                ConditionOutcome.fromCelValue(org.projectnessie.cel.common.types.IntT.intOf(1)).kind());
        assertEquals(ConditionOutcome.Kind.NULL, ConditionOutcome.fromCelValue(null).kind());
    }

    @Test
    void hostFailureDoesNotExposeExceptionMessage() {
        ConditionOutcome outcome = ConditionOutcome.hostFailure(
                new IllegalStateException("secret implementation detail"));

        assertEquals(ConditionOutcome.Kind.HOST_FAILURE, outcome.kind());
        assertEquals("CEL_HOST_EVALUATION_FAILURE", outcome.diagnosticCode());
        assertFalse(outcome.diagnosticCode().contains("secret"));
    }

    @Test
    void onlyBooleanOutcomesMayCarryBooleanValue() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConditionOutcome(ConditionOutcome.Kind.ERROR, false, "CEL_ERROR"));
    }
}
