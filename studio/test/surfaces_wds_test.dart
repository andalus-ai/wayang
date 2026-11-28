import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:wayang_studio/design_system/wds.dart';
import 'package:wayang_studio/features/surfaces/surfaces_screen.dart';
import 'package:wayang_studio/features/surfaces/surface_view.dart';
import 'package:wayang_studio/data/surface_model.dart';

void main() {
  testWidgets('surfaces workbench uses WDS composition and offers both samples', (tester) async {
    await tester.view.setPhysicalSize(const Size(1200, 900));
    await tester.view.setDevicePixelRatio(1);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(const ProviderScope(
      child: MaterialApp(home: Scaffold(body: SurfacesScreen())),
    ));

    expect(find.text('UI surfaces'), findsOneWidget);
    expect(find.text('Surface definition'), findsOneWidget);
    expect(find.text('Rendered preview'), findsOneWidget);
    expect(find.text('Check and render'), findsOneWidget);
    expect(find.text('A2UI sample'), findsOneWidget);
    expect(find.byType(WdsPageHeader), findsOneWidget);
    expect(find.byType(WdsSurface), findsOneWidget);
    expect(find.text('Nothing to preview'), findsOneWidget);
  });

  testWidgets('surface renderer maps badge variants to semantic WDS tones', (tester) async {
    const surface = UiSurface(
      id: 'sample',
      title: 'Sample',
      root: BadgeSurfaceNode('Healthy', 'success'),
    );
    await tester.pumpWidget(const MaterialApp(
      home: Scaffold(body: SurfaceView(surface: surface)),
    ));

    expect(find.byType(WdsBadge), findsOneWidget);
    final badge = tester.widget<WdsBadge>(find.byType(WdsBadge));
    expect(badge.tone, WdsBadgeTone.success);
    expect(badge.label, 'Healthy');
  });

  testWidgets('pasted task completion action remains disabled', (tester) async {
    var called = false;
    const surface = UiSurface(
      id: 'sample',
      title: 'Sample',
      root: ButtonSurfaceNode(
        'Complete task',
        SurfaceAction.none,
      ),
    );
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: SurfaceView(
          surface: surface,
          onAction: (_) => called = true,
        ),
      ),
    ));

    final button = tester.widget<FilledButton>(find.byType(FilledButton));
    expect(button.onPressed, isNull);
    expect(called, isFalse);
  });
}
