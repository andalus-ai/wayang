import 'package:flutter/material.dart';

import '../core/theme.dart';
import 'tokens.dart';

/// A presentation-only destination used by WDS navigation components.
///
/// The application owns destination identity and navigation behavior; WDS
/// only renders the supplied items and reports the selected key.
@immutable
class WdsNavigationItem {
  const WdsNavigationItem({
    required this.key,
    required this.label,
    required this.icon,
  });

  final String key;
  final String label;
  final IconData icon;
}

/// Responsive side navigation for desktop and tablet application shells.
class WdsNavigationRail extends StatelessWidget {
  const WdsNavigationRail({
    super.key,
    required this.items,
    required this.selectedKey,
    required this.onSelected,
    this.extended = true,
    this.brand = 'Wayang',
    this.sectionLabel,
    this.brandIcon = Icons.hub_rounded,
  });

  final List<WdsNavigationItem> items;
  final String selectedKey;
  final ValueChanged<String> onSelected;
  final bool extended;
  final String brand;
  final String? sectionLabel;
  final IconData brandIcon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Container(
      width: extended ? WdsLayout.sidebarExpandedWidth : WdsLayout.sidebarCompactWidth,
      decoration: BoxDecoration(
        color: scheme.surface,
        border: Border(right: BorderSide(color: scheme.outlineVariant)),
      ),
      child: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: EdgeInsets.symmetric(
                horizontal: extended ? WdsSpacing.lg : WdsSpacing.sm,
                vertical: WdsSpacing.xl,
              ),
              child: Row(
                mainAxisAlignment: extended ? MainAxisAlignment.start : MainAxisAlignment.center,
                children: [
                  DecoratedBox(
                    decoration: BoxDecoration(
                      gradient: const LinearGradient(colors: AppColors.brandGradient),
                      borderRadius: BorderRadius.circular(WdsRadius.md),
                    ),
                    child: SizedBox(
                      width: 36,
                      height: 36,
                      child: Icon(brandIcon, color: Colors.white, size: 20),
                    ),
                  ),
                  if (extended) ...[
                    const SizedBox(width: WdsSpacing.md),
                    Flexible(
                      child: Text(
                        brand,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleMedium?.copyWith(
                          color: scheme.onSurface,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            Divider(height: 1, color: scheme.outlineVariant),
            if (sectionLabel != null && extended)
              Padding(
                padding: const EdgeInsets.fromLTRB(WdsSpacing.lg, WdsSpacing.lg, WdsSpacing.md, WdsSpacing.xs),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    sectionLabel!.toUpperCase(),
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                      fontWeight: FontWeight.w800,
                      letterSpacing: 1,
                    ),
                  ),
                ),
              ),
            Expanded(
              child: ListView(
                padding: const EdgeInsets.symmetric(vertical: WdsSpacing.md, horizontal: WdsSpacing.sm),
                children: [
                  for (final item in items)
                    _WdsRailItem(
                      item: item,
                      selected: item.key == selectedKey,
                      extended: extended,
                      onTap: () => onSelected(item.key),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _WdsRailItem extends StatelessWidget {
  const _WdsRailItem({
    required this.item,
    required this.selected,
    required this.extended,
    required this.onTap,
  });

  final WdsNavigationItem item;
  final bool selected;
  final bool extended;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final foreground = selected ? scheme.primary : scheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(bottom: WdsSpacing.xs),
      child: Tooltip(
        message: item.label,
        child: Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(WdsRadius.md),
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(WdsRadius.md),
            child: Semantics(
              button: true,
              selected: selected,
              label: item.label,
              child: Container(
                constraints: const BoxConstraints(minHeight: WdsControlSize.comfortable),
                padding: EdgeInsets.symmetric(
                  horizontal: extended ? WdsSpacing.md : 0,
                  vertical: WdsSpacing.sm,
                ),
                decoration: BoxDecoration(
                  color: selected ? scheme.primary.withValues(alpha: 0.14) : null,
                  borderRadius: BorderRadius.circular(WdsRadius.md),
                  border: selected
                      ? Border.all(color: scheme.primary.withValues(alpha: 0.45))
                      : null,
                ),
                alignment: extended ? Alignment.centerLeft : Alignment.center,
                child: Row(
                  mainAxisSize: extended ? MainAxisSize.max : MainAxisSize.min,
                  children: [
                    Icon(item.icon, size: 19, color: foreground),
                    if (extended) ...[
                      const SizedBox(width: WdsSpacing.md),
                      Expanded(
                        child: Text(
                          item.label,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.labelLarge?.copyWith(
                            fontSize: 13,
                            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                            color: foreground,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Compact bottom navigation for the small set of destinations chosen by the
/// host application. The host decides which items are visible.
class WdsCompactNavigationBar extends StatelessWidget {
  const WdsCompactNavigationBar({
    super.key,
    required this.items,
    required this.selectedKey,
    required this.onSelected,
  });

  final List<WdsNavigationItem> items;
  final String selectedKey;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final activeIndex = items.indexWhere((item) => item.key == selectedKey);
    if (items.isEmpty) return const SizedBox.shrink();
    return NavigationBar(
      backgroundColor: theme.colorScheme.surface,
      selectedIndex: activeIndex >= 0 ? activeIndex : 0,
      onDestinationSelected: (index) => onSelected(items[index].key),
      destinations: [
        for (final item in items)
          NavigationDestination(
            icon: Icon(item.icon, size: 20),
            label: item.label,
            tooltip: item.label,
          ),
      ],
    );
  }
}
