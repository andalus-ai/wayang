/// A declarative UI surface (the A2UI wire format Wayang's server validates at `POST /api/v1/ui/surfaces/validate`). The model is data
/// only: no widgets, no callbacks. [UiSurface.parse] is tolerant on purpose, so an old or odd document shows what it can instead of
/// failing, and it keeps its own depth/size limits so it is safe even on a surface the server did not check.
class UiSurface {
  const UiSurface({required this.id, required this.title, required this.root});

  final String id;
  final String title;
  final SurfaceNode root;

  /// The surface in [json], or null when it is not one (no object, no root node).
  static UiSurface? parse(Object? json) {
    if (json is! Map) return null;
    final budget = _Budget();
    final root = SurfaceNode._parse(json['rootNode'], 1, budget);
    if (root == null) return null;
    return UiSurface(id: _short(json['id']), title: _short(json['title']), root: root);
  }
}

const int surfaceMaxDepth = 8;
const int surfaceMaxNodes = 300;
const int surfaceMaxText = 10000;

class _Budget {
  int nodes = 0;
}

String _short(Object? v) => v is String ? (v.length > 200 ? v.substring(0, 200) : v) : '';
String _text(Object? v) => v is String ? (v.length > surfaceMaxText ? v.substring(0, surfaceMaxText) : v) : '';

/// What a button may do here: reload the surface, or open a screen inside Studio. Anything else is dropped to [SurfaceAction.none].
class SurfaceAction {
  const SurfaceAction._(this.kind, [this.route]);

  static const none = SurfaceAction._('none');
  static const refresh = SurfaceAction._('refresh');
  factory SurfaceAction.navigate(String route) => SurfaceAction._('navigate', route);

  final String kind;
  final String? route;

  static SurfaceAction parse(Object? json) {
    if (json is! Map) return none;
    switch (json['_type']) {
      case 'refresh':
        return refresh;
      case 'navigate':
        final r = json['route'];
        return r is String && r.startsWith('/') && !r.startsWith('//') && r.length <= 200 ? SurfaceAction.navigate(r) : none;
      default:
        return none;
    }
  }
}

sealed class SurfaceNode {
  const SurfaceNode();

  static List<SurfaceNode> _children(Object? raw, int depth, _Budget b) => [
        if (raw is List)
          for (final c in raw)
            if (_parse(c, depth + 1, b) case final n?) n,
      ];

  static SurfaceNode? _parse(Object? raw, int depth, _Budget b) {
    if (raw is! Map || depth > surfaceMaxDepth || ++b.nodes > surfaceMaxNodes) return null;
    switch (raw['_type']) {
      case 'text':
        return TextSurfaceNode(_text(raw['text']), secondary: raw['secondary'] == true);
      case 'heading':
        final l = raw['level'];
        return HeadingSurfaceNode(_text(raw['text']), l is num ? l.toInt().clamp(1, 6) : 1);
      case 'markdown':
        return TextSurfaceNode(_text(raw['markdown'])); // shown as plain text: no links, no images
      case 'badge':
        return BadgeSurfaceNode(_short(raw['label']), raw['variant'] is String ? raw['variant'] as String : 'neutral');
      case 'divider':
        return const DividerSurfaceNode();
      case 'metadata_row':
        return MetadataSurfaceNode(_short(raw['label']), _text(raw['value']));
      case 'provenance':
        final c = raw['confidence'];
        return ProvenanceSurfaceNode(_short(raw['evidenceId']), _text(raw['citation']), c is num ? c.toDouble().clamp(0.0, 1.0) : null);
      case 'card':
        return CardSurfaceNode(raw['title'] is String ? _short(raw['title']) : null, _children(raw['children'], depth, b));
      case 'container':
        return ContainerSurfaceNode(_children(raw['children'], depth, b), row: raw['row'] == true);
      case 'list':
        return ListSurfaceNode(_children(raw['items'], depth, b), ordered: raw['ordered'] == true);
      case 'table':
        return TableSurfaceNode(
          [if (raw['headers'] is List) for (final h in raw['headers'] as List) _short('$h')].take(20).toList(),
          [
            if (raw['rows'] is List)
              for (final r in (raw['rows'] as List).take(200))
                if (r is List) [for (final c in r.take(20)) _short('$c')],
          ],
        );
      case 'button':
        return ButtonSurfaceNode(_short(raw['label']), SurfaceAction.parse(raw['action']), primary: raw['primary'] != false);
      default:
        // image, chart, artifact and anything unknown are not drawn: a visible note beats a silent gap, and nothing is fetched
        return TextSurfaceNode('[Not shown: ${raw['_type'] is String ? _short(raw['_type']) : 'unknown'} node]', secondary: true);
    }
  }
}

class TextSurfaceNode extends SurfaceNode {
  const TextSurfaceNode(this.text, {this.secondary = false});
  final String text;
  final bool secondary;
}

class HeadingSurfaceNode extends SurfaceNode {
  const HeadingSurfaceNode(this.text, this.level);
  final String text;
  final int level;
}

class BadgeSurfaceNode extends SurfaceNode {
  const BadgeSurfaceNode(this.label, this.variant);
  final String label;
  final String variant;
}

class DividerSurfaceNode extends SurfaceNode {
  const DividerSurfaceNode();
}

class MetadataSurfaceNode extends SurfaceNode {
  const MetadataSurfaceNode(this.label, this.value);
  final String label;
  final String value;
}

class ProvenanceSurfaceNode extends SurfaceNode {
  const ProvenanceSurfaceNode(this.evidenceId, this.citation, this.confidence);
  final String evidenceId;
  final String citation;
  final double? confidence;
}

class CardSurfaceNode extends SurfaceNode {
  const CardSurfaceNode(this.title, this.children);
  final String? title;
  final List<SurfaceNode> children;
}

class ContainerSurfaceNode extends SurfaceNode {
  const ContainerSurfaceNode(this.children, {this.row = false});
  final List<SurfaceNode> children;
  final bool row;
}

class ListSurfaceNode extends SurfaceNode {
  const ListSurfaceNode(this.items, {this.ordered = false});
  final List<SurfaceNode> items;
  final bool ordered;
}

class TableSurfaceNode extends SurfaceNode {
  const TableSurfaceNode(this.headers, this.rows);
  final List<String> headers;
  final List<List<String>> rows;
}

class ButtonSurfaceNode extends SurfaceNode {
  const ButtonSurfaceNode(this.label, this.action, {this.primary = true});
  final String label;
  final SurfaceAction action;
  final bool primary;
}

/// What the server said about a surface: [ok] with the rebuilt [surface], or the [problems] with where they are.
class SurfaceCheck {
  const SurfaceCheck({required this.ok, required this.problems, this.surface});

  factory SurfaceCheck.fromJson(Map<String, dynamic> j) => SurfaceCheck(
        ok: j['ok'] == true,
        problems: [if (j['problems'] is List) for (final p in j['problems'] as List) '$p'],
        surface: UiSurface.parse(j['surface']),
      );

  final bool ok;
  final List<String> problems;
  final UiSurface? surface;
}

/// A small surface to start from in the playground.
const String sampleSurfaceJson = '''{
  "id": "order-42",
  "title": "Order 42",
  "rootNode": {
    "_type": "card",
    "title": "Summary",
    "children": [
      {"_type": "metadata_row", "label": "Customer", "value": "Acme"},
      {"_type": "badge", "label": "Needs review", "variant": "warning"},
      {"_type": "table", "headers": ["Item", "Qty"], "rows": [["Widget", "3"], ["Gadget", "1"]]},
      {"_type": "provenance", "evidenceId": "doc-7", "citation": "Purchase order, page 2", "confidence": 0.82},
      {"_type": "button", "label": "Open runs", "action": {"_type": "navigate", "route": "/runs"}}
    ]
  }
}''';

/// The same idea in the A2UI standard-catalog shape (flat components by id), which the server also accepts.
const String sampleA2uiJson = '''{
  "id": "hello",
  "title": "Hello (A2UI)",
  "root": "col",
  "components": [
    {"id": "col", "component": {"Column": {"children": {"explicitList": ["h", "card"]}}}},
    {"id": "h", "component": {"Text": {"text": {"literalString": "Order 42"}, "usageHint": "h2"}}},
    {"id": "card", "component": {"Card": {"child": "row"}}},
    {"id": "row", "component": {"Row": {"children": {"explicitList": ["a", "b"]}}}},
    {"id": "a", "component": {"Text": {"text": {"literalString": "Customer: Acme"}}}},
    {"id": "b", "component": {"Text": {"text": {"literalString": "Needs review"}}}}
  ]
}''';
