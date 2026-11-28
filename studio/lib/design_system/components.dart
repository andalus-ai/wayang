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
    final shape = RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(radius),
      side: BorderSide(
        color: borderColor ?? (isDark ? AppColors.darkBorder : AppColors.lightBorder),
      ),
    );

    // Keep the surface itself as the Material ancestor for interactive descendants
    // such as ListTile. A decorated Container between Material and ListTile hides
    // ink effects and triggers Flutter's ListTile visibility assertion.
    return Material(
      color: color ?? (isDark ? AppColors.darkSurfaceAlt : AppColors.lightSurface),
      shape: shape,
      clipBehavior: Clip.antiAlias,
      child: onTap == null
          ? Padding(padding: padding, child: child)
          : InkWell(
              onTap: onTap,
              customBorder: shape,
              child: Padding(padding: padding, child: child),
            ),
    );
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
    final heading = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (eyebrow != null) ...[
          Text(eyebrow!.toUpperCase(), style: theme.textTheme.labelSmall?.copyWith(
            letterSpacing: 1, fontWeight: FontWeight.w700, color: theme.colorScheme.primary,
          )),
          const SizedBox(height: WdsSpacing.xs),
        ],
        Text(title, style: theme.textTheme.headlineSmall),
        if (description != null) ...[
          const SizedBox(height: WdsSpacing.xs),
          Text(description!, style: theme.textTheme.bodySmall),
        ],
      ],
    );
    if (actions.isEmpty) return heading;
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < 680;
        if (compact) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              heading,
              const SizedBox(height: WdsSpacing.md),
              Wrap(spacing: WdsSpacing.sm, runSpacing: WdsSpacing.sm, children: actions),
            ],
          );
        }
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: heading),
            const SizedBox(width: WdsSpacing.md),
            Flexible(child: Wrap(
              alignment: WrapAlignment.end,
              spacing: WdsSpacing.sm,
              runSpacing: WdsSpacing.sm,
              children: actions,
            )),
          ],
        );
      },
    );
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
        child: Padding(padding: const EdgeInsets.all(WdsSpacing.lg), child: Icon(icon, size: 28, color: theme.colorScheme.primary))),
      const SizedBox(height: WdsSpacing.lg),
      Text(title, style: theme.textTheme.titleMedium, textAlign: TextAlign.center),
      if (description != null) ...[const SizedBox(height: WdsSpacing.xs),
        ConstrainedBox(constraints: const BoxConstraints(maxWidth: 420), child: Text(description!, style: theme.textTheme.bodySmall, textAlign: TextAlign.center))],
      if (action != null) ...[const SizedBox(height: WdsSpacing.lg), action!],
    ])));
  }
}


/// A breadcrumb item. The final item is treated as the current page.
@immutable
class WdsBreadcrumbItem {
  const WdsBreadcrumbItem({required this.label, this.onTap});
  final String label;
  final VoidCallback? onTap;
}

/// Compact breadcrumb trail with overflow handled by the available width.
class WdsBreadcrumbs extends StatelessWidget {
  const WdsBreadcrumbs({super.key, required this.items, this.separator = Icons.chevron_right_rounded});
  final List<WdsBreadcrumbItem> items;
  final IconData separator;

  @override
  Widget build(BuildContext context) {
    if (items.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: WdsSpacing.xs,
      runSpacing: WdsSpacing.xs,
      children: [
        for (var index = 0; index < items.length; index++) ...[
          if (index > 0) Icon(separator, size: 15, color: theme.colorScheme.onSurfaceVariant),
          if (index == items.length - 1)
            Text(items[index].label, style: theme.textTheme.labelMedium?.copyWith(
              color: theme.colorScheme.onSurface, fontWeight: FontWeight.w700,
            ))
          else
            TextButton(
              onPressed: items[index].onTap,
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: WdsSpacing.xs),
                minimumSize: const Size(0, WdsControlSize.compact),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(items[index].label),
            ),
        ],
      ],
    );
  }
}

/// Page-level heading composition shared by catalogs, settings and designers.
class WdsPageHeader extends StatelessWidget {
  const WdsPageHeader({
    super.key,
    required this.title,
    this.description,
    this.eyebrow,
    this.breadcrumbs = const [],
    this.actions = const [],
    this.trailing,
  });

  final String title;
  final String? description;
  final String? eyebrow;
  final List<WdsBreadcrumbItem> breadcrumbs;
  final List<Widget> actions;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final heading = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (eyebrow != null) ...[
          Text(eyebrow!.toUpperCase(), style: theme.textTheme.labelSmall?.copyWith(
            color: theme.colorScheme.primary, fontWeight: FontWeight.w800, letterSpacing: 1,
          )),
          const SizedBox(height: WdsSpacing.xs),
        ],
        Text(title, style: theme.textTheme.headlineMedium),
        if (description != null) ...[
          const SizedBox(height: WdsSpacing.xs),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: Text(description!, style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            )),
          ),
        ],
      ],
    );

    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < 640;
        final titleRow = compact
            ? Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                heading,
                if (trailing != null) ...[
                  const SizedBox(height: WdsSpacing.md),
                  trailing!,
                ],
              ])
            : Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(child: heading),
                if (trailing != null) ...[
                  const SizedBox(width: WdsSpacing.lg),
                  trailing!,
                ],
              ]);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (breadcrumbs.isNotEmpty) ...[
              WdsBreadcrumbs(items: breadcrumbs),
              const SizedBox(height: WdsSpacing.md),
            ],
            titleRow,
            if (actions.isNotEmpty) ...[
              const SizedBox(height: WdsSpacing.lg),
              Wrap(spacing: WdsSpacing.sm, runSpacing: WdsSpacing.sm, children: actions),
            ],
          ],
        );
      },
    );
  }
}

/// Responsive composition for search, filters and contextual list actions.
class WdsFilterToolbar extends StatelessWidget {
  const WdsFilterToolbar({
    super.key,
    this.search,
    this.filters = const [],
    this.actions = const [],
    this.searchWidth = 320,
  });

  final Widget? search;
  final List<Widget> filters;
  final List<Widget> actions;
  final double searchWidth;

  @override
  Widget build(BuildContext context) {
    final controls = <Widget>[
      if (search != null)
        ConstrainedBox(
          constraints: BoxConstraints(minWidth: 180, maxWidth: searchWidth),
          child: search!,
        ),
      ...filters,
    ];
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < 680;
        if (compact) {
          return Wrap(
            spacing: WdsSpacing.sm,
            runSpacing: WdsSpacing.sm,
            children: [
              ...controls,
              if (actions.isNotEmpty)
                SizedBox(
                  width: constraints.maxWidth,
                  child: Wrap(
                    spacing: WdsSpacing.sm,
                    runSpacing: WdsSpacing.sm,
                    children: actions,
                  ),
                ),
            ],
          );
        }
        return Row(
          children: [
            Expanded(child: Wrap(
              spacing: WdsSpacing.sm,
              runSpacing: WdsSpacing.sm,
              children: controls,
            )),
            if (actions.isNotEmpty) ...[
              const SizedBox(width: WdsSpacing.md),
              Wrap(
                alignment: WrapAlignment.end,
                spacing: WdsSpacing.sm,
                runSpacing: WdsSpacing.sm,
                children: actions,
              ),
            ],
          ],
        );
      },
    );
  }
}
