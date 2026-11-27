import 'package:flutter/material.dart';
import '../core/theme.dart';
import 'tokens.dart';

/// A consistent bordered surface for cards, panels and inspector sections.
class WdsSurface extends StatelessWidget {
  const WdsSurface({super.key, required this.child, this.padding = const EdgeInsets.all(WdsSpacing.lg), this.color, this.borderColor, this.radius = WdsRadius.lg, this.onTap});
  final Widget child;
  final EdgeInsetsGeometry padding;
  final Color? color, borderColor;
  final double radius;
  final VoidCallback? onTap;
  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final surface = Container(
      padding: padding,
      decoration: BoxDecoration(
        color: color ?? (isDark ? AppColors.darkSurfaceAlt : AppColors.lightSurface),
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: borderColor ?? (isDark ? AppColors.darkBorder : AppColors.lightBorder)),
      ),
      child: child,
    );
    if (onTap == null) return surface;
    return Material(color: Colors.transparent, borderRadius: BorderRadius.circular(radius), clipBehavior: Clip.antiAlias,
      child: InkWell(onTap: onTap, child: surface));
  }
}

enum WdsBadgeTone { neutral, brand, success, warning, danger, info }

/// Semantic badge. Use tones for meaning, not decoration.
class WdsBadge extends StatelessWidget {
  const WdsBadge({super.key, required this.label, this.tone = WdsBadgeTone.neutral, this.dense = false, this.icon});
  final String label;
  final WdsBadgeTone tone;
  final bool dense;
  final IconData? icon;
  Color _toneColor(ThemeData theme) => switch (tone) {
    WdsBadgeTone.neutral => theme.colorScheme.onSurface.withValues(alpha: 0.65),
    WdsBadgeTone.brand => theme.colorScheme.primary,
    WdsBadgeTone.success => AppColors.success,
    WdsBadgeTone.warning => AppColors.warning,
    WdsBadgeTone.danger => AppColors.danger,
    WdsBadgeTone.info => AppColors.info,
  };
  @override
  Widget build(BuildContext context) {
    final color = _toneColor(Theme.of(context));
    return Container(
      padding: EdgeInsets.symmetric(horizontal: dense ? WdsSpacing.sm : WdsSpacing.md, vertical: dense ? 3 : WdsSpacing.xs + 1),
      decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(WdsRadius.pill),
        border: Border.all(color: color.withValues(alpha: 0.28))),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (icon != null) ...[Icon(icon, size: dense ? 11 : 13, color: color), const SizedBox(width: WdsSpacing.xs)]
        else Container(width: 6, height: 6, margin: const EdgeInsets.only(right: WdsSpacing.sm - 2),
          decoration: BoxDecoration(color: color, shape: BoxShape.circle)),
        Text(label, style: TextStyle(color: color, fontSize: dense ? 10.5 : 11.5, fontWeight: FontWeight.w700, letterSpacing: 0.15)),
      ]),
    );
  }
}

enum WdsButtonVariant { primary, secondary, tertiary, danger }

/// Standard action button with consistent sizing and semantic variants.
class WdsButton extends StatelessWidget {
  const WdsButton({super.key, required this.label, required this.onPressed, this.variant = WdsButtonVariant.primary,
    this.icon, this.size = WdsControlSize.regular, this.isLoading = false, this.expand = false});
  final String label;
  final VoidCallback? onPressed;
  final WdsButtonVariant variant;
  final IconData? icon;
  final double size;
  final bool isLoading, expand;
  @override
  Widget build(BuildContext context) {
    final child = isLoading ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
      : Row(mainAxisSize: MainAxisSize.min, children: [
          if (icon != null) ...[Icon(icon, size: 16), const SizedBox(width: WdsSpacing.sm)],
          Text(label),
        ]);
    final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(WdsRadius.md));
    final button = switch (variant) {
      WdsButtonVariant.primary => FilledButton(onPressed: isLoading ? null : onPressed,
        style: FilledButton.styleFrom(minimumSize: Size(0, size), shape: shape), child: child),
      WdsButtonVariant.secondary => OutlinedButton(onPressed: isLoading ? null : onPressed,
        style: OutlinedButton.styleFrom(minimumSize: Size(0, size), shape: shape), child: child),
      WdsButtonVariant.tertiary => TextButton(onPressed: isLoading ? null : onPressed,
        style: TextButton.styleFrom(minimumSize: Size(0, size), shape: shape), child: child),
      WdsButtonVariant.danger => FilledButton(onPressed: isLoading ? null : onPressed,
        style: FilledButton.styleFrom(minimumSize: Size(0, size), backgroundColor: AppColors.danger, foregroundColor: Colors.white, shape: shape), child: child),
    };
    return expand ? SizedBox(width: double.infinity, child: button) : button;
  }
}

/// Icon-only action with an accessible tooltip.
class WdsIconButton extends StatelessWidget {
  const WdsIconButton({super.key, required this.icon, required this.tooltip, required this.onPressed, this.selected = false});
  final IconData icon;
  final String tooltip;
  final VoidCallback? onPressed;
  final bool selected;
  @override
  Widget build(BuildContext context) => IconButton(tooltip: tooltip, onPressed: onPressed, isSelected: selected, icon: Icon(icon));
}

/// Shared page/section heading with optional supporting text and actions.
class WdsSectionHeader extends StatelessWidget {
  const WdsSectionHeader({super.key, required this.title, this.description, this.actions = const [], this.eyebrow});
  final String title;
  final String? description, eyebrow;
  final List<Widget> actions;
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        if (eyebrow != null) ...[
          Text(eyebrow!.toUpperCase(), style: theme.textTheme.labelSmall?.copyWith(letterSpacing: 1, fontWeight: FontWeight.w700, color: theme.colorScheme.primary)),
          const SizedBox(height: WdsSpacing.xs),
        ],
        Text(title, style: theme.textTheme.headlineSmall),
        if (description != null) ...[const SizedBox(height: WdsSpacing.xs), Text(description!, style: theme.textTheme.bodySmall)],
      ])),
      if (actions.isNotEmpty) ...[const SizedBox(width: WdsSpacing.md), Wrap(spacing: WdsSpacing.sm, runSpacing: WdsSpacing.sm, children: actions)],
    ]);
  }
}

/// Compact search field inheriting the active theme's input treatment.
class WdsSearchField extends StatelessWidget {
  const WdsSearchField({super.key, required this.hint, this.onChanged, this.controller, this.autofocus = false});
  final String hint;
  final ValueChanged<String>? onChanged;
  final TextEditingController? controller;
  final bool autofocus;
  @override
  Widget build(BuildContext context) => TextField(controller: controller, onChanged: onChanged, autofocus: autofocus,
    style: Theme.of(context).textTheme.bodyMedium,
    decoration: InputDecoration(hintText: hint, isDense: true, prefixIcon: const Icon(Icons.search_rounded, size: 18),
      prefixIconConstraints: const BoxConstraints(minWidth: 40)));
}

/// Consistent empty state for catalog, list and dashboard screens.
class WdsEmptyState extends StatelessWidget {
  const WdsEmptyState({super.key, required this.icon, required this.title, this.description, this.action});
  final IconData icon;
  final String title;
  final String? description;
  final Widget? action;
  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(child: Padding(padding: const EdgeInsets.all(WdsSpacing.xxxl), child: Column(mainAxisSize: MainAxisSize.min, children: [
      DecoratedBox(decoration: BoxDecoration(color: theme.colorScheme.primary.withValues(alpha: 0.10), shape: BoxShape.circle),
        child: const Padding(padding: EdgeInsets.all(WdsSpacing.lg), child: Icon(Icons.inbox_outlined, size: 28))),
      const SizedBox(height: WdsSpacing.lg),
      Text(title, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
      if (description != null) ...[const SizedBox(height: WdsSpacing.xs),
        ConstrainedBox(constraints: const BoxConstraints(maxWidth: 420), child: Text(description!, style: theme.textTheme.bodySmall, textAlign: TextAlign.center))],
      if (action != null) ...[const SizedBox(height: WdsSpacing.lg), action!],
    ])));
  }
}
