import '../models/dmn.dart';

const int csvMaxRows = 500;
const int csvMaxChars = 200000;

/// The rules of [table] as CSV: a header row of column labels (conditions first, then results, then a "Note" column) and one row per rule.
/// Meant to be pasted back into Studio or into a spreadsheet; entries are written exactly as they are, so a spreadsheet may treat one that starts
/// with `=`, `+`, `-` or `@` as a formula.
String rulesToCsv(DmnDecisionTable table) {
  final rows = <List<String>>[
    [...table.inputs.map((c) => c.label), ...table.outputs.map((c) => c.label), 'Note'],
    for (final r in table.rules)
      [
        for (var i = 0; i < table.inputs.length; i++) i < r.inputEntries.length ? r.inputEntries[i] : '-',
        for (var i = 0; i < table.outputs.length; i++) i < r.outputEntries.length ? r.outputEntries[i] : '',
        r.annotation,
      ],
  ];
  return rows.map((r) => r.map(_quote).join(',')).join('\n');
}

String _quote(String v) => v.contains(RegExp(r'[",\n\r]')) || v != v.trim() ? '"${v.replaceAll('"', '""')}"' : v;

/// Parses CSV text (RFC 4180: quoted fields, doubled quotes, commas and line breaks inside quotes, CRLF or LF). Returns null when a quote is left open
/// or the input is larger than [csvMaxChars]. A blank line is skipped.
List<List<String>>? parseCsv(String text) {
  if (text.length > csvMaxChars) return null;
  final rows = <List<String>>[];
  var row = <String>[];
  final cell = StringBuffer();
  var inQuotes = false;
  var any = false; // something was read on this row
  for (var i = 0; i < text.length; i++) {
    final c = text[i];
    if (inQuotes) {
      if (c == '"') {
        if (i + 1 < text.length && text[i + 1] == '"') {
          cell.write('"');
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        cell.write(c);
      }
      continue;
    }
    if (c == '"') {
      inQuotes = true;
      any = true;
    } else if (c == ',') {
      row.add(cell.toString());
      cell.clear();
      any = true;
    } else if (c == '\n' || c == '\r') {
      if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++;
      if (any || cell.isNotEmpty) {
        row.add(cell.toString());
        rows.add(row);
      }
      row = <String>[];
      cell.clear();
      any = false;
    } else {
      cell.write(c);
      any = true;
    }
  }
  if (inQuotes) return null;
  if (any || cell.isNotEmpty) {
    row.add(cell.toString());
    rows.add(row);
  }
  return rows;
}

/// What a CSV paste would add: the new [rules], and what could not be used.
class CsvImport {
  const CsvImport({this.rules = const [], this.ignoredColumns = const [], this.error});
  final List<DmnRule> rules;

  /// Header names that matched no column of the table (their cells were left out).
  final List<String> ignoredColumns;

  /// Why nothing can be imported, or null.
  final String? error;
}

/// Reads [csv] into rules for [table]. The first row names the columns; a name is matched to an input or output column by its label (case-insensitive,
/// surrounding spaces ignored), `Note` fills the rule's note, and any other header is reported and ignored. A column the CSV does not mention gets `-`
/// for conditions and an empty result. At most [csvMaxRows] rows are read. [newId] makes the ids of the new rules.
CsvImport rulesFromCsv(DmnDecisionTable table, String csv, String Function() newId) {
  final rows = parseCsv(csv);
  if (rows == null) return const CsvImport(error: 'The CSV is too large or has an unclosed quote.');
  if (rows.length < 2) return const CsvImport(error: 'Need a header row and at least one rule row.');
  final header = rows.first.map((h) => h.trim().toLowerCase()).toList();
  int? find(String label) {
    final i = header.indexOf(label.trim().toLowerCase());
    return i < 0 ? null : i;
  }

  final inputCols = [for (final c in table.inputs) find(c.label)];
  final outputCols = [for (final c in table.outputs) find(c.label)];
  final noteCol = find('note');
  if (inputCols.every((c) => c == null) && outputCols.every((c) => c == null)) {
    return const CsvImport(error: 'No header matches a column label of this table.');
  }
  final used = {...inputCols.whereType<int>(), ...outputCols.whereType<int>(), if (noteCol != null) noteCol};
  final ignored = [for (var i = 0; i < rows.first.length; i++) if (!used.contains(i) && rows.first[i].trim().isNotEmpty) rows.first[i].trim()];
  String cell(List<String> r, int? i, String fallback) => i == null || i >= r.length ? fallback : r[i];
  final rules = [
    for (final r in rows.skip(1).take(csvMaxRows))
      DmnRule(
        id: newId(),
        inputEntries: [for (final c in inputCols) cell(r, c, '-').trim().isEmpty ? '-' : cell(r, c, '-')],
        outputEntries: [for (final c in outputCols) cell(r, c, '')],
        annotation: cell(r, noteCol, ''),
      ),
  ];
  return CsvImport(rules: rules, ignoredColumns: ignored);
}
