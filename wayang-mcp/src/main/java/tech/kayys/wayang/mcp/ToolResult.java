package tech.kayys.wayang.mcp;

/**
 * Outcome of a tool call. {@code structured} must be a JSON object or null. Errors the model can act on (bad
 * arguments, a 404 from the API) are results with {@code isError}, not protocol errors, per the MCP specification.
 */
public record ToolResult(boolean isError, String text, Object structured) {

    public static ToolResult error(String text) {
        return new ToolResult(true, text, null);
    }

    public static ToolResult ok(String text, Object structured) {
        return new ToolResult(false, text, structured);
    }
}
