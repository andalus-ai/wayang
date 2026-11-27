import 'package:flutter/widgets.dart';

/// Wayang Design System (WDS) foundational design tokens.
class WdsSpacing {
  WdsSpacing._();
  static const double xxs = 2, xs = 4, sm = 8, md = 12, lg = 16, xl = 24, xxl = 32, xxxl = 48;
}
class WdsRadius {
  WdsRadius._();
  static const double xs = 4, sm = 8, md = 12, lg = 16, xl = 22, pill = 999;
}
class WdsControlSize {
  WdsControlSize._();
  static const double compact = 32, regular = 40, comfortable = 48;
}
class WdsMotion {
  WdsMotion._();
  static const Duration fast = Duration(milliseconds: 120);
  static const Duration normal = Duration(milliseconds: 180);
  static const Duration deliberate = Duration(milliseconds: 240);
  static const Curve standard = Curves.easeOutCubic;
}
class WdsBreakpoint {
  WdsBreakpoint._();
  static const double compact = 600, medium = 900, expanded = 1200, wide = 1440;
}
class WdsLayout {
  WdsLayout._();
  static const double contentMaxWidth = 1440;
  static const double inspectorWidth = 320;
  static const double sidebarExpandedWidth = 248;
  static const double sidebarCompactWidth = 72;
}
