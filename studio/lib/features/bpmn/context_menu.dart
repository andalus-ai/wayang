import 'package:flutter/material.dart';

import '../../core/theme.dart';

/// One row of the canvas context menu. A null [onTap] is a section label; [divider] draws a line before the row.
class BpmnMenuItem {
  const BpmnMenuItem(this.label, {this.icon, this.onTap, this.destructive = false, this.divider = false});

  final String label;
  final IconData? icon;
  final VoidCallback? onTap;
  final bool destructive;
  final bool divider;
}

/// Shows [items] at [globalPosition] and runs the chosen row's action. Nothing happens if the menu is dismissed.
Future<void> showBpmnContextMenu(BuildContext context, Offset globalPosition, List<BpmnMenuItem> items) async {
  if (items.isEmpty) return;
  final overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
  final chosen = await showMenu<int>(
    context: context,
    color: AppColors.darkSurfaceRaised,
    position: RelativeRect.fromRect(globalPosition & const Size(1, 1), Offset.zero & overlay.size),
    items: [
      for (var i = 0; i < items.length; i++)
        if (items[i].onTap == null)
          PopupMenuItem<int>(
            enabled: false,
            height: 28,
            child: Text(items[i].label.toUpperCase(), style: const TextStyle(fontSize: 10.5, letterSpacing: 0.6, color: AppColors.textSecondaryDark)),
          )
        else ...[
          if (items[i].divider) const PopupMenuDivider(height: 8),
          PopupMenuItem<int>(
            value: i,
            height: 34,
            child: Row(children: [
              if (items[i].icon != null) ...[
                Icon(items[i].icon, size: 16, color: items[i].destructive ? AppColors.danger : AppColors.textSecondaryDark),
                const SizedBox(width: 10),
              ],
              Text(items[i].label, style: TextStyle(fontSize: 13, color: items[i].destructive ? AppColors.danger : null)),
            ]),
          ),
        ],
    ],
  );
  if (chosen != null) items[chosen].onTap?.call();
}
