package tech.kayys.wayang.rag;

import java.util.List;

/** Renders table-like data (CSV, spreadsheets) as one retrievable line per record: {@code header=value; header=value}. */
final class Tabular {

    private Tabular() {
    }

    static void emit(StringBuilder out, String title, List<List<String>> rows, Limits limits) {
        if (rows.isEmpty()) {
            return;
        }
        if (title != null) {
            out.append("## ").append(title).append('\n');
        }
        List<String> head = rows.get(0);
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < row.size(); c++) {
                String v = row.get(c) == null ? "" : row.get(c).strip();
                if (v.isEmpty()) {
                    continue;
                }
                String h = c < head.size() && head.get(c) != null && !head.get(c).isBlank() ? head.get(c).strip() : "col" + (c + 1);
                if (line.length() > 0) {
                    line.append("; ");
                }
                line.append(h).append('=').append(v);
            }
            if (line.length() > 0) {
                out.append(line).append('\n');
                if (out.length() > limits.maxChars()) {
                    throw new RagException("the file holds more than " + limits.maxChars() + " characters of text");
                }
            }
        }
    }
}
