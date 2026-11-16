import 'dart:ui';

import '../../models/bpmn.dart';
import 'bpmn_canvas.dart';

/// Ids of the nodes whose box touches [rect] (canvas coordinates).
Set<String> nodesInRect(BpmnDiagram diagram, Rect rect) => {
      for (final n in diagram.nodeList)
        if (rect.overlaps(Rect.fromLTWH(n.x, n.y, bpmnNodeSize(n.type).width, bpmnNodeSize(n.type).height))) n.id,
    };

/// The rectangle between two corners, whichever way it was dragged.
Rect rectBetween(Offset a, Offset b) => Rect.fromPoints(a, b);
