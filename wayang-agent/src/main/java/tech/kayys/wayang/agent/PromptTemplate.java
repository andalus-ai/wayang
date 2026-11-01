package tech.kayys.wayang.agent;

import java.util.Map;

import tech.kayys.wayang.expression.ExpressionException;
import tech.kayys.wayang.expression.Expressions;
import tech.kayys.wayang.expression.Scope;
import tech.kayys.wayang.expression.Values;

/**
 * Renders {@code {{ expression }}} placeholders using the bounded expression
 * language. Unknown variables render as an empty string, never as code. Output
 * is capped to keep prompts from ballooning.
 */
public final class PromptTemplate {

    public static final int MAX_OUTPUT = 200_000;

    private PromptTemplate() {
    }

    public static String render(String template, Map<String, ?> variables) {
        return render(template, Scope.of(variables));
    }

    public static String render(String template, Scope scope) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                throw new AgentSpecException("Unclosed '{{' in template at position " + open);
            }
            String src = template.substring(open + 2, close).strip();
            try {
                Object v = Expressions.parse(src).evaluate(scope);
                out.append(v == null ? "" : Values.asString(v));
            } catch (ExpressionException e) {
                throw new AgentSpecException("Template expression '" + src + "': " + e.getMessage(), e);
            }
            if (out.length() > MAX_OUTPUT) {
                throw new AgentSpecException("Rendered prompt exceeds " + MAX_OUTPUT + " characters");
            }
            i = close + 2;
        }
        return out.toString();
    }
}
