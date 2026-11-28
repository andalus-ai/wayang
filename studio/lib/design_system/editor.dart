import 'package:flutter/material.dart';

import 'components.dart';
import 'tokens.dart';

/// Responsive action strip for a visual editor or workflow designer.
class WdsEditorToolbar extends StatelessWidget {
  const WdsEditorToolbar({
    super.key,
    this.leading,
    this.title,
    this.subtitle,
    this.center,
    this.actions = const [],
    this.dense = false,
  });

  final Widget? leading;
  final String? title;
  final String? subtitle;
  final Widget? center;
  final List<Widget> actions;
  final bool dense;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final identity = <Widget>[
      if (leading != null) leading!,
      if (title != null)
        Flexible(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title!, maxLines: 1, overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleSmall),
              if (subtitle != null)
                Text(subtitle!, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    )),
            ],
          ),
        ),
    ];

    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < 720;
        final actionWidgets = Wrap(
          spacing: WdsSpacing.xs,
          runSpacing: WdsSpacing.xs,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: actions,
        );
        if (compact) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(children: [
                ...identity,
                const Spacer(),
                if (actions.isNotEmpty) actionWidgets,
              ]),
              if (center != null) ...[
                SizedBox(height: dense ? WdsSpacing.xs : WdsSpacing.sm),
                center!,
              ],
            ],
          );
        }
        return Row(
          children: [
            ...identity,
            if (center != null) ...[
              const Spacer(),
              Flexible(child: center!),
              const Spacer(),
            ] else
              const Spacer(),
            actionWidgets,
          ],
        );
      },
    );
  }
}

/// A dockable-looking panel surface for canvas sidebars and bottom drawers.
/// Panel behavior (docking, resizing, persistence) remains host-owned.
class WdsEditorPanel extends StatelessWidget {
  const WdsEditorPanel({
    super.key,
    required this.child,
    this.title,
    this.description,
    this.leading,
    this.actions = const [],
    this.footer,
    this.padding = const EdgeInsets.all(WdsSpacing.md),
    this.width,
  });

  final Widget child;
  final String? title;
  final String? description;
  final Widget? leading;
  final List<Widget> actions;
  final Widget? footer;
  final EdgeInsetsGeometry padding;
  final double? width;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SizedBox(
      width: width,
      child: Material(
        color: theme.colorScheme.surface,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (title != null)
              Padding(
                padding: padding,
                child: Row(
                  children: [
                    if (leading != null) ...[
                      leading!,
                      const SizedBox(width: WdsSpacing.sm),
                    ],
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(title!, style: theme.textTheme.titleSmall),
                          if (description != null)
                            Text(description!,
                                style: theme.textTheme.bodySmall?.copyWith(
                                  color: theme.colorScheme.onSurfaceVariant,
                                )),
                        ],
                      ),
                    ),
                    ...actions,
                  ],
                ),
              ),
            if (title != null) const Divider(height: 1),
            Expanded(child: Padding(padding: padding, child: child)),
            if (footer != null) ...[
              const Divider(height: 1),
              Padding(padding: padding, child: footer!),
            ],
          ],
        ),
      ),
    );
  }
}
