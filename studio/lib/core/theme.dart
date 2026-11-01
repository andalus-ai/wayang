import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Central color palette. The studio is dark-first (developer-tool feel)
/// with a light theme provided for completeness.
class AppColors {
  AppColors._();

  // Brand — indigo -> violet -> cyan accent family.
  static const Color primary = Color(0xFF6C5CE7);
  static const Color primaryBright = Color(0xFF8B7CF6);
  static const Color secondary = Color(0xFF00D9C6);
  static const Color tertiary = Color(0xFFFF6B9D);

  // Dark surfaces.
  static const Color darkBg = Color(0xFF0B0E14);
  static const Color darkSurface = Color(0xFF12151E);
  static const Color darkSurfaceAlt = Color(0xFF171B26);
  static const Color darkSurfaceRaised = Color(0xFF1D2230);
  static const Color darkBorder = Color(0xFF262B3A);
  static const Color darkBorderBright = Color(0xFF343B4E);

  // Light surfaces.
  static const Color lightBg = Color(0xFFF5F6FA);
  static const Color lightSurface = Color(0xFFFFFFFF);
  static const Color lightBorder = Color(0xFFE3E6EE);

  // Text.
  static const Color textPrimaryDark = Color(0xFFEDEEF3);
  static const Color textSecondaryDark = Color(0xFF9BA1B4);
  static const Color textMutedDark = Color(0xFF6B7288);
  static const Color textPrimaryLight = Color(0xFF161923);
  static const Color textSecondaryLight = Color(0xFF565C6D);

  // Semantic.
  static const Color success = Color(0xFF2ED47A);
  static const Color warning = Color(0xFFFFB020);
  static const Color danger = Color(0xFFFF5C7A);
  static const Color info = Color(0xFF4DA8FF);

  // Canvas dot grid.
  static const Color canvasDot = Color(0xFF232838);

  static const List<Color> brandGradient = [primary, secondary];

  /// Deterministic accent color for a capability/node "kind", so the same
  /// kind always renders with the same hue across catalog, canvas & badges.
  static Color forKindIndex(int index) {
    const palette = [
      Color(0xFF6C5CE7), // connector
      Color(0xFF00B8A9), // transform / mapping
      Color(0xFFFF9F45), // routing / control-flow
      Color(0xFF4DA8FF), // schema
      Color(0xFFFF6B9D), // policy
      Color(0xFF2ED47A), // runtime
      Color(0xFFEAD94C), // transport
      Color(0xFF9B8CFF), // operation
    ];
    return palette[index % palette.length];
  }
}

class AppRadius {
  AppRadius._();
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 22;
}

class AppTheme {
  AppTheme._();

  static TextTheme _textTheme(Color primaryText, Color secondaryText) {
    final base = GoogleFonts.manropeTextTheme();
    return base
        .copyWith(
          displaySmall: base.displaySmall?.copyWith(
              color: primaryText, fontWeight: FontWeight.w800, letterSpacing: -0.5),
          headlineSmall: base.headlineSmall?.copyWith(
              color: primaryText, fontWeight: FontWeight.w700, letterSpacing: -0.3),
          titleLarge: base.titleLarge
              ?.copyWith(color: primaryText, fontWeight: FontWeight.w700),
          titleMedium: base.titleMedium
              ?.copyWith(color: primaryText, fontWeight: FontWeight.w600),
          titleSmall: base.titleSmall
              ?.copyWith(color: primaryText, fontWeight: FontWeight.w600),
          bodyLarge: base.bodyLarge?.copyWith(color: primaryText),
          bodyMedium: base.bodyMedium?.copyWith(color: primaryText),
          bodySmall: base.bodySmall?.copyWith(color: secondaryText),
          labelLarge: base.labelLarge
              ?.copyWith(color: primaryText, fontWeight: FontWeight.w600),
          labelMedium: base.labelMedium?.copyWith(color: secondaryText),
          labelSmall: base.labelSmall?.copyWith(color: secondaryText),
        )
        .apply(fontFamily: GoogleFonts.manrope().fontFamily);
  }

  static ThemeData dark() {
    final scheme = const ColorScheme.dark(
      brightness: Brightness.dark,
      primary: AppColors.primaryBright,
      onPrimary: Colors.white,
      secondary: AppColors.secondary,
      onSecondary: Colors.black,
      surface: AppColors.darkSurface,
      onSurface: AppColors.textPrimaryDark,
      error: AppColors.danger,
      onError: Colors.white,
    );
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      colorScheme: scheme,
      scaffoldBackgroundColor: AppColors.darkBg,
      canvasColor: AppColors.darkBg,
      dividerColor: AppColors.darkBorder,
      textTheme: _textTheme(AppColors.textPrimaryDark, AppColors.textSecondaryDark),
      fontFamily: GoogleFonts.manrope().fontFamily,
      splashFactory: InkSparkle.splashFactory,
      cardTheme: CardThemeData(
        color: AppColors.darkSurfaceAlt,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.lg),
          side: const BorderSide(color: AppColors.darkBorder),
        ),
      ),
      tooltipTheme: TooltipThemeData(
        decoration: BoxDecoration(
          color: AppColors.darkSurfaceRaised,
          borderRadius: BorderRadius.circular(AppRadius.sm),
          border: Border.all(color: AppColors.darkBorderBright),
        ),
        textStyle: const TextStyle(color: AppColors.textPrimaryDark, fontSize: 12),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: AppColors.darkSurfaceRaised,
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
          borderSide: const BorderSide(color: AppColors.darkBorder),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
          borderSide: const BorderSide(color: AppColors.darkBorder),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(AppRadius.md),
          borderSide: const BorderSide(color: AppColors.primaryBright, width: 1.4),
        ),
        hintStyle: const TextStyle(color: AppColors.textMutedDark, fontSize: 13),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.primaryBright,
          foregroundColor: Colors.white,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13.5),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: AppColors.textPrimaryDark,
          side: const BorderSide(color: AppColors.darkBorderBright),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13.5),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: AppColors.textSecondaryDark,
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
        ),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: AppColors.darkSurfaceRaised,
        side: const BorderSide(color: AppColors.darkBorder),
        labelStyle: const TextStyle(color: AppColors.textPrimaryDark, fontSize: 12),
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.sm)),
      ),
      dividerTheme: const DividerThemeData(color: AppColors.darkBorder, thickness: 1),
      navigationRailTheme: const NavigationRailThemeData(
        backgroundColor: AppColors.darkSurface,
        indicatorColor: AppColors.primary,
        selectedIconTheme: IconThemeData(color: Colors.white),
        unselectedIconTheme: IconThemeData(color: AppColors.textMutedDark),
        selectedLabelTextStyle:
            TextStyle(color: AppColors.textPrimaryDark, fontSize: 11.5, fontWeight: FontWeight.w600),
        unselectedLabelTextStyle: TextStyle(color: AppColors.textMutedDark, fontSize: 11.5),
      ),
      scrollbarTheme: ScrollbarThemeData(
        thumbColor: WidgetStateProperty.all(AppColors.darkBorderBright),
        radius: const Radius.circular(8),
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: AppColors.darkSurfaceRaised,
        contentTextStyle: const TextStyle(color: AppColors.textPrimaryDark),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  static ThemeData light() {
    final scheme = const ColorScheme.light(
      brightness: Brightness.light,
      primary: AppColors.primary,
      onPrimary: Colors.white,
      secondary: AppColors.secondary,
      onSecondary: Colors.black,
      surface: AppColors.lightSurface,
      onSurface: AppColors.textPrimaryLight,
      error: AppColors.danger,
      onError: Colors.white,
    );
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      colorScheme: scheme,
      scaffoldBackgroundColor: AppColors.lightBg,
      dividerColor: AppColors.lightBorder,
      textTheme: _textTheme(AppColors.textPrimaryLight, AppColors.textSecondaryLight),
      fontFamily: GoogleFonts.manrope().fontFamily,
      cardTheme: CardThemeData(
        color: AppColors.lightSurface,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.lg),
          side: const BorderSide(color: AppColors.lightBorder),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.primary,
          foregroundColor: Colors.white,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppRadius.md)),
          textStyle: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13.5),
        ),
      ),
    );
  }
}
