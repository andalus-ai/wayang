package tech.kayys.wayang.cel;

import java.util.List;
import java.util.Optional;

public record CelCompileResult(boolean successful, CompiledCelExpression artifact,
        List<String> diagnosticCodes) {
    public CelCompileResult {
        diagnosticCodes = List.copyOf(diagnosticCodes);
        if (successful && artifact == null) throw new IllegalArgumentException("Successful compilation requires an artifact");
        if (!successful && artifact != null) throw new IllegalArgumentException("Failed compilation cannot expose an artifact");
    }
    public Optional<CompiledCelExpression> artifactOptional() { return Optional.ofNullable(artifact); }
}
