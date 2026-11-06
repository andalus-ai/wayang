package tech.kayys.wayang.cel.testing;

import java.util.Objects;
import tech.kayys.wayang.cel.CelCompileRequest;

/** One compiler conformance case with an explicit expected compile outcome. */
public record CelCompileTestCase(String testCaseId, CelCompileRequest request,
        boolean expectedSuccessful) {
    public CelCompileTestCase {
        if (testCaseId == null || testCaseId.isBlank()) {
            throw new IllegalArgumentException("testCaseId is required");
        }
        Objects.requireNonNull(request, "request");
    }
}
