import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';
import 'package:wayang_studio/design_system/wds.dart';
import 'package:wayang_studio/features/settings/settings_screen.dart';
import 'package:wayang_studio/providers/app_providers.dart';

void main() {
  testWidgets('connection settings use WDS and keep bearer token masked', (tester) async {
    await tester.pumpWidget(ProviderScope(
      overrides: [
        apiConfigProvider.overrideWith(
          (ref) => const ApiConfig(
            baseUrl: 'http://localhost:8080',
            token: 'sensitive-token',
            tenant: 'tenant-a',
          ),
        ),
      ],
      child: const MaterialApp(
        home: Scaffold(body: SettingsScreen()),
      ),
    ));
    await tester.pumpAndSettle();

    expect(find.byType(WdsPageHeader), findsOneWidget);
    expect(find.byType(WdsInspectorSection), findsOneWidget);
    expect(find.byType(WdsFieldGroup), findsOneWidget);
    expect(find.byType(WdsButton), findsOneWidget);
    final tokenField = tester.widget<TextField>(
      find.byWidgetPredicate((widget) =>
          widget is TextField &&
          widget.decoration?.labelText?.startsWith('API key or bearer token') == true),
    );
    expect(tokenField.obscureText, isTrue);
    // find.text also matches an EditableText's (obscured) value, so look for the token shown as plain text instead.
    expect(find.byWidgetPredicate((w) => w is Text && (w.data ?? '').contains('sensitive-token')), findsNothing);
  });
}
