import 'package:flutter/material.dart';

import 'components.dart';
import 'tokens.dart';

enum WdsNodePortDirection { input, output }

enum WdsNodePortKind { data, control, event }

/// Metadata for a node's visual connection point.
@immutable
class WdsNodePortData {
  const WdsNodePortData({
    required this.id,
    required this.label,
    this.kind = WdsNodePortKind.data,
    this.description,
    this.isConnected = false,
    this.isRequired = false,
    this.enabled = true,
  });

  final String id;
  final String label;
  final WdsNodePortKind kind;
  final String? description;
  final bool isConnected;
  final bool isRequired;
  final bool enabled;
}

/// A single input or output connector. The graph editor owns connection
/// gestures, validation, hit-testing and graph mutations.
class WdsNodePort extends StatelessWidget {
  const WdsNodePort({
    super.key,
    required this.port,
    required this.direction,
    this.onTap,
    this.compact = false,
  });

  final WdsNodePortData port;
  final WdsNodePortDirection direction;
  final VoidCallback? onTap;
  final bool compact;

  Color _portColor(ThemeData theme) => switch (port.kind) {
        WdsNodePortKind.data => theme.colorScheme.primary,
        WdsNodePortKind.control => theme.colorScheme.tertiary,
        WdsNodePortKind.event => theme.colorScheme.secondary,
      };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = _portColor(theme);
    final marker = Container(
      width: compact ? 9 : 11,
      height: compact ? 9 : 11,
      decoration: BoxDecoration(
        color: port.isConnected ? color : theme.colorScheme.surface,
        shape: port.kind == WdsNodePortKind.event
            ? BoxShape.circle
            : BoxShape.rectangle,
        borderRadius: port.kind == WdsNodePortKind.event
            ? null
            : BorderRadius.circular(3),
        border: Border.all(color: port.enabled ? color : theme.disabledColor,
            width: 1.6),
      ),
    );
    final label = Flexible(
      child: Text(
        '${port.label}${port.isRequired ? ' *' : ''}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: theme.textTheme.labelSmall?.copyWith(
          color: port.enabled
              ? theme.colorScheme.onSurface
              : theme.disabledColor,
        ),
      ),
    );
    final children = direction == WdsNodePortDirection.input
        ? <Widget>[marker, const SizedBox(width: WdsSpacing.xs), label]
        : <Widget>[label, const SizedBox(width: WdsSpacing.xs), marker];
    final content = Row(
      mainAxisSize: MainAxisSize.min,
      mainAxisAlignment: direction == WdsNodePortDirection.input
          ? MainAxisAlignment.start
          : MainAxisAlignment.end,
      children: children,
    );
    final tooltip = port.description == null
        ? port.label
        : '${port.label}: ${port.description}';
    return Tooltip(
      message: tooltip,
      child: InkWell(
        onTap: port.enabled ? onTap : null,
        borderRadius: BorderRadius.circular(WdsRadius.sm),
        child: Padding(
          padding: EdgeInsets.symmetric(
            horizontal: WdsSpacing.xs,
            vertical: compact ? WdsSpacing.xxs : WdsSpacing.xs,
          ),
          child: content,
        ),
      ),
    );
  }
}

/// Generic visual node card. It has no assumptions about workflow semantics;
/// Agent Builder, BPMN and other designers supply labels and port metadata.
class WdsNodeCard extends StatelessWidget {
  const WdsNodeCard({
    super.key,
    required this.title,
    this.subtitle,
    this.icon = Icons.account_tree_outlined,
    this.status,
    this.inputs = const [],
    this.outputs = const [],
    this.selected = false,
    this.enabled = true,
    this.onTap,
    this.onPortTap,
    this.width = 240,
    this.footer,
  });

  final String title;
  final String? subtitle;
  final IconData icon;
  final WdsBadge? status;
  final List<WdsNodePortData> inputs;
  final List<WdsNodePortData> outputs;
  final bool selected;
  final bool enabled;
  final VoidCallback? onTap;
  final ValueChanged<WdsNodePortData>? onPortTap;
  final double width;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final borderColor = selected
        ? theme.colorScheme.primary
        : theme.colorScheme.outlineVariant;
    return Opacity(
      opacity: enabled ? 1 : 0.55,
      child: Material(
        color: theme.colorScheme.surface,
        elevation: selected ? 3 : 1,
        borderRadius: BorderRadius.circular(WdsRadius.lg),
        child: InkWell(
          onTap: enabled ? onTap : null,
          borderRadius: BorderRadius.circular(WdsRadius.lg),
          child: Container(
            width: width,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(WdsRadius.lg),
              border: Border.all(
                color: borderColor,
                width: selected ? 2 : 1,
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: [
                Padding(
                  padding: const EdgeInsets.all(WdsSpacing.md),
                  child: Row(
                    children: [
                      DecoratedBox(
                        decoration: BoxDecoration(
                          color: theme.colorScheme.primaryContainer,
                          borderRadius: BorderRadius.circular(WdsRadius.md),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(WdsSpacing.sm),
                          child: Icon(icon, size: 18,
                              color: theme.colorScheme.onPrimaryContainer),
                        ),
                      ),
                      const SizedBox(width: WdsSpacing.sm),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(title, maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: theme.textTheme.titleSmall),
                            if (subtitle != null)
                              Text(subtitle!, maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: theme.textTheme.labelSmall?.copyWith(
                                    color: theme.colorScheme.onSurfaceVariant,
                                  )),
                          ],
                        ),
                      ),
                      if (status != null) status!,
                    ],
                  ),
                ),
                if (inputs.isNotEmpty || outputs.isNotEmpty)
                  const Divider(height: 1),
                if (inputs.isNotEmpty || outputs.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: WdsSpacing.xs,
                      vertical: WdsSpacing.xs,
                    ),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              for (final port in inputs)
                                WdsNodePort(
                                  port: port,
                                  direction: WdsNodePortDirection.input,
                                  onTap: onPortTap == null
                                      ? null
                                      : () => onPortTap!(port),
                                ),
                            ],
                          ),
                        ),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.end,
                            children: [
                              for (final port in outputs)
                                WdsNodePort(
                                  port: port,
                                  direction: WdsNodePortDirection.output,
                                  onTap: onPortTap == null
                                      ? null
                                      : () => onPortTap!(port),
                                ),
                            ],
                          ),
                        ),
                      ],
                    ),
                  ),
                if (footer != null) ...[
                  const Divider(height: 1),
                  Padding(
                    padding: const EdgeInsets.all(WdsSpacing.sm),
                    child: footer!,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
