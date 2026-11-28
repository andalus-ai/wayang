import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/workspace_model.dart';
import 'package:wayang_studio/design_system/wds.dart';
import 'package:wayang_studio/features/workspace/config_screen.dart';
import 'package:wayang_studio/models/wayang.dart';
import 'package:wayang_studio/providers/app_providers.dart';

void main() {
  testWidgets('configuration uses WDS while preserving visible non-secret values', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        configProvider.overrideWith((ref) async => [
          const ConfigItem(
            key: 'BASE_URL',
            value: 'https://api.example.test',
            version: 1,
            project: 'p1',
          ),
        ]),
        projectsProvider.overrideWith((ref) async => [
          const Project(id: 'p1', name: 'Billing'),
        ]),
      ],
      child: const MaterialApp(
        home: Scaffold(body: ConfigScreen()),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(WdsPageHeader), findsOneWidget);
    expect(find.byType(WdsButton), findsOneWidget);
    expect(find.byType(WdsSurface), findsOneWidget);
    expect(find.byType(WdsIconButton), findsOneWidget);
    expect(find.text('BASE_URL'), findsOneWidget);
    expect(find.text('https://api.example.test'), findsOneWidget);
  });
}
