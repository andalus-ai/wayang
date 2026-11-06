package tech.kayys.wayang.cel;

import java.util.ArrayList;
import java.util.List;

/**
 * Explicit, evidence-backed provider guarantees.
 *
 * <p>These flags are declarations by an adapter, not proof. A provider may
 * only set a flag after its qualification tests demonstrate the behavior.</p>
 */
public record CelProviderCapabilities(
        boolean staticTypeChecking,
        boolean functionAllowlisting,
        boolean hardEvaluationCostLimit,
        boolean boundedResultSize,
        boolean cooperativeCancellation,
        boolean deterministicFunctionProfile,
        boolean durableArtifactPersistence,
        boolean exactVersionRecovery) {

    /** Conservative default for providers that have not completed qualification. */
    public static CelProviderCapabilities unqualified() {
        return new CelProviderCapabilities(false, false, false, false,
                false, false, false, false);
    }

    public List<String> compilerGaps() {
        List<String> gaps = new ArrayList<>();
        if (!staticTypeChecking) gaps.add("STATIC_TYPE_CHECKING_UNVERIFIED");
        if (!functionAllowlisting) gaps.add("FUNCTION_ALLOWLIST_UNVERIFIED");
        if (!durableArtifactPersistence) gaps.add("DURABLE_ARTIFACT_PERSISTENCE_UNVERIFIED");
        if (!exactVersionRecovery) gaps.add("EXACT_VERSION_RECOVERY_UNVERIFIED");
        return List.copyOf(gaps);
    }

    public List<String> evaluatorGaps() {
        List<String> gaps = new ArrayList<>();
        if (!functionAllowlisting) gaps.add("FUNCTION_ALLOWLIST_UNVERIFIED");
        if (!hardEvaluationCostLimit) gaps.add("HARD_EVALUATION_COST_LIMIT_UNVERIFIED");
        if (!boundedResultSize) gaps.add("BOUNDED_RESULT_SIZE_UNVERIFIED");
        if (!cooperativeCancellation) gaps.add("COOPERATIVE_CANCELLATION_UNVERIFIED");
        if (!deterministicFunctionProfile) gaps.add("DETERMINISTIC_FUNCTION_PROFILE_UNVERIFIED");
        if (!exactVersionRecovery) gaps.add("EXACT_VERSION_RECOVERY_UNVERIFIED");
        return List.copyOf(gaps);
    }

    public boolean compilerQualified() {
        return compilerGaps().isEmpty();
    }

    public boolean evaluatorQualified() {
        return evaluatorGaps().isEmpty();
    }
}
