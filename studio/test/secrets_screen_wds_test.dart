import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/design_system/wds.dart';
import 'package:wayang_studio/features/secrets/secrets_screen.dart';

void main() {
  testWidgets('secrets empty state uses WDS and remains write-only', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        secretsProvider.overrideWith((ref) async => []),
        keyStatusProvider.overrideWith((ref) async => null),
      ],
      child: const MaterialApp(
        home: Scaffold(body: SecretsScreen()),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(WdsPageHeader), findsOneWidget);
    expect(find.byType(WdsFilterToolbar), findsOneWidget);
    expect(find.byType(WdsEmptyState), findsOneWidget);
    expect(find.text('No secrets'), findsOneWidget);
    expect(find.textContaining('secret value'), findsNothing);
  });
}
