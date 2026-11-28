import 'package:flutter/material.dart';

import '../../design_system/wds.dart';
import '../../data/surface_model.dart';

/// Draws a [UiSurface]. Read-only: text, tables and badges only; no images are fetched, markdown is plain text, and a button can only
/// refresh or open a Studio screen through [onAction].
class SurfaceView extends StatelessWidget {
  const SurfaceView({super.key, required this.surface, this.onAction, this.allowTaskActions = false});

  final UiSurface surface;
  final void Function(SurfaceAction action)? onAction;

  /// Whether `task.complete` buttons are live. Only a surface that came from a run may turn this on; a pasted one never does.
  final bool allowTaskActions;

  @override
  Widget build(BuildContext context) => _node(context, surface.root);

  Widget _kids(BuildContext context, List<SurfaceNode> nodes, {bool row = false, double gap = 8}) {
    final children = [
      for (var i = 0; i < nodes.length; i++) ...[
        if (i > 0) SizedBox(width: row ? gap : 0, height: row ? 0 : gap),
        row ? Flexible(child: _node(context, nodes[i])) : _node(context, nodes[i]),
      ],
    ];
    return row
        ? Row(crossAxisAlignment: CrossAxisAlignment.start, children: children)
        : Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: children);
  }

  WdsBadgeTone _variant(String v) => switch (v) {
        'success' => WdsBadgeTone.success,
        'warning' => WdsBadgeTone.warning,
        'error' => WdsBadgeTone.danger,
        'info' => WdsBadgeTone.info,
        _ => WdsBadgeTone.neutral,
      };

  Widget _node(BuildContext context, SurfaceNode n) {
    final theme = Theme.of(context);
    switch (n) {
      case TextSurfaceNode():
        return Text(n.text, style: n.secondary ? theme.textTheme.bodySmall : theme.textTheme.bodyMedium);
      case HeadingSurfaceNode():
        final style = switch (n.level) {
          1 => theme.textTheme.headlineSmall,
          2 => theme.textTheme.titleLarge,
          3 => theme.textTheme.titleMedium,
          _ => theme.textTheme.titleSmall,
        };
        return Text(n.text, style: style);
      case BadgeSurfaceNode():
        return Align(alignment: Alignment.centerLeft, child: WdsBadge(label: n.label, tone: _variant(n.variant)));
      case DividerSurfaceNode():
        return const Divider(height: 16);
      case MetadataSurfaceNode():
        return WdsPropertyRow(label: n.label, value: Text(n.value, style: theme.textTheme.bodyMedium));
      case ProvenanceSurfaceNode():
        final c = n.confidence;
        return Row(children: [
          const Icon(Icons.fact_check_outlined, size: 16),
          const SizedBox(width: 6),
          Expanded(child: Text('${n.citation}${n.evidenceId.isEmpty ? '' : '  (${n.evidenceId})'}', style: theme.textTheme.bodySmall)),
          if (c != null) WdsBadge(label: '${(c * 100).round()}%', tone: c >= 0.8 ? WdsBadgeTone.success : WdsBadgeTone.warning, dense: true),
        ]);
      case CardSurfaceNode():
        return WdsSurface(
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
            if (n.title != null && n.title!.isNotEmpty) ...[Text(n.title!, style: theme.textTheme.titleMedium), const SizedBox(height: 10)],
            _kids(context, n.children),
          ]),
        );
      case ContainerSurfaceNode():
        return _kids(context, n.children, row: n.row);
      case ListSurfaceNode():
        return Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
          for (var i = 0; i < n.items.length; i++)
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              SizedBox(width: 24, child: Text(n.ordered ? '${i + 1}.' : '•')),
              Expanded(child: _node(context, n.items[i])),
            ]),
        ]);
      case TableSurfaceNode():
        final cols = n.headers.length;
        if (cols == 0) return const SizedBox.shrink();
        return SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: DataTable(
            columns: [for (final h in n.headers) DataColumn(label: Text(h))],
            rows: [
              for (final r in n.rows)
                DataRow(cells: [for (var i = 0; i < cols; i++) DataCell(Text(i < r.length ? r[i] : ''))]),
            ],
          ),
        );
      case ButtonSurfaceNode():
        final enabled = n.action.kind != 'none' && onAction != null && (n.action.kind != 'task.complete' || allowTaskActions);
        final press = enabled ? () => onAction!(n.action) : null;
        return Align(
          alignment: Alignment.centerLeft,
          child: n.primary ? FilledButton(onPressed: press, child: Text(n.label)) : OutlinedButton(onPressed: press, child: Text(n.label)),
        );
    }
  }
}
