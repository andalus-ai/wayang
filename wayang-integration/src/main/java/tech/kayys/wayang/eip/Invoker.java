package tech.kayys.wayang.eip;

import java.util.Map;

/**
 * Seam through which a pipeline calls out (connectors, MCP tools, agents). The runtime supplies an
 * implementation; the integration module stays free of any transport. Implementations throw to signal failure;
 * throw {@link Permanent} when trying again cannot help, so retry steps stop at once.
 */
@FunctionalInterface
public interface Invoker {
    Map<String, Object> invoke(String name, Map<String, Object> config, Map<String, Object> inputs) throws Exception;

    /** A failure that retrying will not fix. */
    final class Permanent extends Exception {
        private static final long serialVersionUID = 1L;

        public Permanent(String message) {
            super(message);
        }
    }
}
