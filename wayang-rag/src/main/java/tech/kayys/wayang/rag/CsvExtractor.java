package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** CSV, TSV and semicolon-separated files (RFC 4180 quoting). The first row is the header; every later row becomes one {@code header=value; ...} line. */
public final class CsvExtractor implements DocumentExtractor {

    @Override
    public Set<String> extensions() {
        return Set.of("csv", "tsv");
    }

    @Override
    public String extract(byte[] content, Limits limits) {
        String s = Text.normalise(Text.decode(content));
        Text.checkSize(s, limits);
        List<List<String>> rows = parse(s, delimiter(s), limits.maxRows());
        StringBuilder out = new StringBuilder();
        Tabular.emit(out, null, rows, limits);
        return out.toString();
    }

    static char delimiter(String s) {
        int end = Math.min(s.length(), 4096);
        int nl = s.indexOf('\n');
        String first = s.substring(0, nl < 0 || nl > end ? end : nl);
        char best = ',';
        int bestCount = -1;
        for (char c : new char[] { ',', ';', '\t', '|' }) {
            int n = (int) first.chars().filter(x -> x == c).count();
            if (n > bestCount) {
                best = c;
                bestCount = n;
            }
        }
        return best;
    }

    static List<List<String>> parse(String s, char delim, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delim) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                addRow(rows, row, maxRows);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            addRow(rows, row, maxRows);
        }
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row, int maxRows) {
        if (row.stream().allMatch(String::isBlank)) {
            return;
        }
        if (rows.size() >= maxRows) {
            throw new RagException("the file has more than " + maxRows + " rows");
        }
        rows.add(row);
    }
}
