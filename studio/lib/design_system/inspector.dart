import 'package:flutter/material.dart';

import 'tokens.dart';

/// A titled group within a property inspector.
class WdsInspectorSection extends StatelessWidget {
  const WdsInspectorSection({
    super.key,
    required this.title,
    required this.child,
    this.description,
    this.trailing,
    this.initiallyExpanded = true,
    this.collapsible = false,
  });

  final String title;
  final String? description;
  final Widget child;
  final Widget? trailing;
  final bool initiallyExpanded;
  final bool collapsible;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final heading = Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: theme.textTheme.titleSmall),
              if (description != null) ...[
                const SizedBox(height: WdsSpacing.xxs),
                Text(
                  description!,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ],
          ),
        ),
        if (trailing != null) trailing!,
      ],
    );

    if (!collapsible) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          heading,
          const SizedBox(height: WdsSpacing.md),
          child,
        ],
      );
    }

    return Theme(
      data: theme.copyWith(dividerColor: Colors.transparent),
      child: ExpansionTile(
        tilePadding: EdgeInsets.zero,
        childrenPadding: EdgeInsets.zero,
        initiallyExpanded: initiallyExpanded,
        title: heading,
        children: [
          const SizedBox(height: WdsSpacing.sm),
          Align(alignment: Alignment.centerLeft, child: child),
        ],
      ),
    );
  }
}

/// Consistent vertical spacing for related inspector controls.
class WdsFieldGroup extends StatelessWidget {
  const WdsFieldGroup({
    super.key,
    required this.children,
    this.spacing = WdsSpacing.md,
  });

  final List<Widget> children;
  final double spacing;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (var index = 0; index < children.length; index++) ...[
            if (index > 0) SizedBox(height: spacing),
            children[index],
          ],
        ],
      );
}

/// Label/value composition for read-only properties and compact metadata.
class WdsPropertyRow extends StatelessWidget {
  const WdsPropertyRow({
    super.key,
    required this.label,
    required this.value,
    this.helperText,
    this.trailing,
    this.valueAlignment = CrossAxisAlignment.end,
  });

  final String label;
  final Widget value;
  final String? helperText;
  final Widget? trailing;
  final CrossAxisAlignment valueAlignment;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          flex: 2,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              if (helperText != null) ...[
                const SizedBox(height: WdsSpacing.xxs),
                Text(helperText!, style: theme.textTheme.labelSmall),
              ],
            ],
          ),
        ),
        const SizedBox(width: WdsSpacing.md),
        Expanded(
          flex: 3,
          child: Column(
            crossAxisAlignment: valueAlignment,
            children: [
              value,
              if (trailing != null) ...[
                const SizedBox(height: WdsSpacing.xs),
                trailing!,
              ],
            ],
          ),
        ),
      ],
    );
  }
}

/// Displays a field-level validation message without owning validation logic.
class WdsFieldMessage extends StatelessWidget {
  const WdsFieldMessage({
    super.key,
    required this.message,
    this.isError = true,
    this.icon,
  });

  final String message;
  final bool isError;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = isError
        ? theme.colorScheme.error
        : theme.colorScheme.onSurfaceVariant;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(
          icon ?? (isError ? Icons.error_outline_rounded : Icons.info_outline_rounded),
          size: 15,
          color: color,
        ),
        const SizedBox(width: WdsSpacing.xs),
        Expanded(
          child: Text(
            message,
            style: theme.textTheme.bodySmall?.copyWith(color: color),
          ),
        ),
      ],
    );
  }
}
