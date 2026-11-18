package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Language-neutral tokenizer: lower-cased runs of letters and digits (any script). No stop-word list, so it behaves the same for every language. */
final class Tokens {

    private Tokens() {
    }

    static List<String> of(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String s = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                cur.appendCodePoint(cp);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
