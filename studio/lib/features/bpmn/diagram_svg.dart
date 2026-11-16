import 'dart:math' as math;

import 'package:flutter/painting.dart' show Color;

import '../../models/bpmn.dart';
import 'bpmn_canvas.dart';
import 'selection.dart';

String _esc(String s) => s
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')
    // control characters are not allowed in XML 1.0 at all
    .replaceAll(RegExp(r'[\x00-\x08\x0B\x0C\x0E-\x1F]'), '');

String _hex(Color c) => '#${(c.toARGB32() & 0xFFFFFF).toRadixString(16).padLeft(6, '0')}';

String _num(double v) => v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(1);

/// A standalone SVG picture of [diagram]: lanes, flows with arrowheads and labels, and the nodes with their names. It is plain text with no
/// scripts, no external references and every label escaped, so it is safe to paste into a file, a wiki or a browser. Node settings (which can
/// hold references to secrets) are never part of it.
String diagramToSvg(BpmnDiagram diagram, {double padding = 40}) {
  final nodes = diagram.nodeList;
  final lanes = diagram.sortedLanes;
  final tops = laneLayout(lanes).tops;

  var maxX = 400.0, maxY = lanes.isEmpty ? 200.0 : laneLayout(lanes).totalHeight;
  for (final n in nodes) {
    final s = bpmnNodeSize(n.type);
    maxX = math.max(maxX, n.x + s.width);
    maxY = math.max(maxY, n.y + s.height);
  }
  final width = maxX + padding, height = maxY + padding;
  final b = StringBuffer()
    ..writeln('<svg xmlns="http://www.w3.org/2000/svg" width="${_num(width)}" height="${_num(height)}" viewBox="0 0 ${_num(width)} ${_num(height)}" font-family="sans-serif" font-size="12">')
    ..writeln('<rect width="100%" height="100%" fill="#ffffff"/>')
    ..writeln('<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#555555"/></marker></defs>');

  for (var i = 0; i < lanes.length; i++) {
    b.writeln('<rect x="0" y="${_num(tops[i])}" width="${_num(width)}" height="${_num(lanes[i].height)}" fill="${i.isOdd ? '#f4f4f6' : '#ffffff'}" stroke="#cccccc"/>');
    b.writeln('<text x="8" y="${_num(tops[i] + 18)}" font-weight="bold" fill="#444444">${_esc(lanes[i].name)}</text>');
  }

  for (final f in diagram.flowList) {
    final from = diagram.node(f.from), to = diagram.node(f.to);
    if (from == null || to == null) continue;
    final sf = bpmnNodeSize(from.type), st = bpmnNodeSize(to.type);
    final x1 = from.x + sf.width, y1 = from.y + sf.height / 2, x2 = to.x, y2 = to.y + st.height / 2;
    b.writeln('<line x1="${_num(x1)}" y1="${_num(y1)}" x2="${_num(x2)}" y2="${_num(y2)}" stroke="#555555" stroke-width="1.5"${f.isDefault ? ' stroke-dasharray="6 3"' : ''} marker-end="url(#arrow)"/>');
    final text = f.label.isNotEmpty ? f.label : (f.condition ?? '');
    if (text.isNotEmpty) {
      b.writeln('<text x="${_num((x1 + x2) / 2)}" y="${_num((y1 + y2) / 2 - 5)}" text-anchor="middle" fill="#333333">${_esc(text.length > 60 ? '${text.substring(0, 60)}…' : text)}</text>');
    }
  }

  for (final n in nodes) {
    final s = bpmnNodeSize(n.type);
    final color = _hex(bpmnNodeColor(n.type));
    final cx = n.x + s.width / 2, cy = n.y + s.height / 2;
    final style = 'fill="$color" fill-opacity="0.14" stroke="$color" stroke-width="2"';
    if (n.type.isEvent) {
      b.writeln('<circle cx="${_num(cx)}" cy="${_num(cy)}" r="${_num(s.width / 2 - 1)}" $style/>');
    } else if (n.type.isGateway) {
      b.writeln('<polygon points="${_num(cx)},${_num(n.y)} ${_num(n.x + s.width)},${_num(cy)} ${_num(cx)},${_num(n.y + s.height)} ${_num(n.x)},${_num(cy)}" $style/>');
    } else {
      b.writeln('<rect x="${_num(n.x)}" y="${_num(n.y)}" width="${_num(s.width)}" height="${_num(s.height)}" rx="10" $style/>');
    }
    final name = n.label.isEmpty ? n.type.label : n.label;
    final shown = name.length > 28 ? '${name.substring(0, 27)}…' : name;
    final ty = (n.type.isEvent || n.type.isGateway) ? n.y + s.height + 14 : cy + 4;
    b.writeln('<text x="${_num(cx)}" y="${_num(ty)}" text-anchor="middle" fill="#222222">${_esc(shown)}</text>');
  }
  b.write('</svg>');
  return b.toString();
}
