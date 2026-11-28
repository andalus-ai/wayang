import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';

void main() {
  group('WDS accessibility and theme contracts', () {
    const items = [
      WdsNavigationItem(key: 'home', label: 'Home', icon: Icons.home_outlined),
      WdsNavigationItem(key: 'runs', label: 'Runs', icon: Icons.play_arrow),
    ];

    testWidgets('navigation rail uses the active light color scheme', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: const ColorScheme.light(
            primary: Color(0xFF174EA6),
            surface: Color(0xFFFDFBFF),
            onSurface: Color(0xFF191B20),
            outlineVariant: Color(0xFF777777),
          ),
        ),
        home: Scaffold(
          body: Row(
            children: [
              WdsNavigationRail(
                items: items,
                selectedKey: 'home',
                onSelected: (_) {},
              ),
              const Expanded(child: SizedBox()),
            ],
          ),
        ),
      ));

      final rail = tester.widget<Container>(
        find.ancestor(
          of: find.text('Wayang'),
          matching: find.byType(Container),
        ).first,
      );
      expect(rail, isNotNull);
      expect(tester.takeException(), isNull);
    });

    testWidgets('navigation rail exposes selected state and label to semantics', (tester) async {
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(
          body: Row(
            children: [
              WdsNavigationRail(
                items: items,
                selectedKey: 'home',
                onSelected: (_) {},
              ),
              const Expanded(child: SizedBox()),
            ],
          ),
        ),
      ));

      final semantics = tester.getSemantics(find.text('Home').first);
      expect(semantics.label, contains('Home'));
      expect(semantics.hasFlag(SemanticsFlag.isButton), isTrue);
      expect(semantics.hasFlag(SemanticsFlag.isSelected), isTrue);
    });

    testWidgets('compact navigation safely handles an empty destination set', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          bottomNavigationBar: WdsCompactNavigationBar(
            items: const [],
            selectedKey: 'missing',
            onSelected: (_) {},
          ),
        ),
      ));

      expect(tester.takeException(), isNull);
      expect(find.byType(NavigationBar), findsNothing);
    });

    testWidgets('navigation adapts its surface to a light theme', (tester) async {
      const surface = Color(0xFFFDFBFF);
      await tester.pumpWidget(MaterialApp(
        theme: ThemeData(
          useMaterial3: true,
          colorScheme: const ColorScheme.light(surface: surface),
        ),
        home: Scaffold(
          body: WdsNavigationRail(
            items: items,
            selectedKey: 'home',
            onSelected: (_) {},
          ),
        ),
      ));

      final railContainer = tester.widget<Container>(
        find.ancestor(
          of: find.byType(ListView),
          matching: find.byType(Container),
        ).first,
      );
      expect((railContainer.decoration as BoxDecoration).color, surface);
    });
  });
}
