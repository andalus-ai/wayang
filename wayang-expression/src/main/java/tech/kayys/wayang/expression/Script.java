package tech.kayys.wayang.expression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CES, the Common Expression Script: a few statements of the Wayang expression language, for logic that does not fit one expression.
 *
 * <pre>
 * // statements end at a new line or a semicolon (line breaks inside (), [] and {} do not end one)
 * total := sum(items.price)
 * tax   := total * 0.11
 * `grand total` := total + tax
 * if `grand total` &gt; 1000 then "review" else "ok"      // the last statement is the result
 * </pre>
 *
 * <ul>
 * <li>{@code name := expression} binds a value for the statements after it (backticks allow spaces in a name); it shadows an input of
 * the same name and cannot be undone. {@code return expression} is the same as the bare expression.</li>
 * <li>The result is the value of the last statement (an assignment yields its value). An empty script is an error.</li>
 * <li>It is still the same bounded language: no I/O, reflection, regex or randomness; at most {@value #MAX_SOURCE} characters, at most
 * {@value #MAX_STATEMENTS} statements and one shared step budget for the whole script.</li>
 * </ul>
 */
public final class Script {

    public static final int MAX_SOURCE = 20_000;
    public static final int MAX_STATEMENTS = 100;

    private static final Pattern ASSIGN = Pattern.compile("^\\s*(`[^`]{1,100}`|[A-Za-z_][A-Za-z0-9_]{0,99})\\s*:=(?!=)(.*)$", Pattern.DOTALL);
    private static final Pattern RETURN = Pattern.compile("^\\s*return\\b(.*)$", Pattern.DOTALL);

    private record Statement(String name, Expression expression, int number) {
    }

    private final List<Statement> statements;

    private Script(List<Statement> statements) {
        this.statements = statements;
    }

    /** Parses every statement up front, so syntax errors are found before anything runs. */
    public static Script parse(String source) {
        if (source == null || source.isBlank()) {
            throw new ExpressionException("The script is empty", String.valueOf(source), -1);
        }
        if (source.length() > MAX_SOURCE) {
            throw new ExpressionException("The script is longer than " + MAX_SOURCE + " characters", source, -1);
        }
        List<String> parts = split(source);
        if (parts.isEmpty()) {
            throw new ExpressionException("The script has no statements", source, -1);
        }
        if (parts.size() > MAX_STATEMENTS) {
            throw new ExpressionException("The script has more than " + MAX_STATEMENTS + " statements", source, -1);
        }
        List<Statement> out = new ArrayList<>();
        int n = 0;
        for (String part : parts) {
            n++;
            String name = null;
            String text = part;
            Matcher a = ASSIGN.matcher(part);
            if (a.matches()) {
                String raw = a.group(1);
                name = raw.startsWith("`") ? raw.substring(1, raw.length() - 1) : raw;
                text = a.group(2);
            } else {
                Matcher r = RETURN.matcher(part);
                if (r.matches()) {
                    text = r.group(1);
                }
            }
            if (text.isBlank()) {
                throw new ExpressionException("Statement " + n + " has nothing after " + (name != null ? "':='" : "'return'"), source, -1);
            }
            try {
                out.add(new Statement(name, Expressions.parse(text), n));
            } catch (ExpressionException e) {
                throw new ExpressionException("Statement " + n + ": " + e.getMessage(), source, -1);
            }
        }
        return new Script(out);
    }

    /** Runs the statements in order with one step budget; returns the last value. */
    public Object run(Map<String, ?> variables) {
        Scope scope = Scope.of(variables);
        Object last = null;
        Map<String, Object> assigned = new LinkedHashMap<>();
        for (Statement s : statements) {
            try {
                last = s.expression().evaluate(scope);
            } catch (LimitExceededException e) {
                throw new LimitExceededException("Statement " + s.number() + ": " + e.getMessage(), "");
            } catch (ExpressionException e) {
                throw new ExpressionException("Statement " + s.number() + ": " + e.getMessage(), "", -1);
            }
            if (s.name() != null) {
                scope = scope.child(s.name(), last);
                assigned.put(s.name(), last);
            }
        }
        return last;
    }

    /** One-shot parse and run. */
    public static Object evaluate(String source, Map<String, ?> variables) {
        return parse(source).run(variables);
    }

    /** Splits at top-level new lines and semicolons; strings, backtick names, comments and brackets are respected. */
    static List<String> split(String src) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < src.length() && src.charAt(j) != '"') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                if (j >= src.length()) {
                    throw new ExpressionException("Unterminated text in the script", src, i);
                }
                cur.append(src, i, j + 1);
                i = j;
            } else if (c == '`') {
                int j = src.indexOf('`', i + 1);
                if (j < 0) {
                    throw new ExpressionException("Unterminated `name` in the script", src, i);
                }
                cur.append(src, i, j + 1);
                i = j;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
                i--; // the new line itself is handled next
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                cur.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
                cur.append(c);
            } else if ((c == '\n' || c == ';') && depth == 0) {
                flush(parts, cur);
            } else {
                cur.append(c);
            }
        }
        flush(parts, cur);
        return parts;
    }

    private static void flush(List<String> parts, StringBuilder cur) {
        if (!cur.toString().isBlank()) {
            parts.add(cur.toString().strip());
        }
        cur.setLength(0);
    }
}
