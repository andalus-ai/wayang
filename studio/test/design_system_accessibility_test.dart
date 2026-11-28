import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';

void main() {
  group('WDS accessibility and theme contracts', () {
    const items = [
      WdsNavigationItem(key: 'home', label: 'Home', icon: Icons.home_outlined),
      WdsNavigationItem(key: 'runs', label: 'Runs', icon: Icons.play_arrow),
    ];

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
      // containsSemantics does not name the flag enum, which newer Flutter versions renamed (SemanticsFlag is undefined there).
      expect(semantics, containsSemantics(isButton: true, isSelected: true));
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

    testWidgets('navigation rail uses the active light color scheme', (tester) async {
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
      expect(tester.takeException(), isNull);
    });
  });
}
