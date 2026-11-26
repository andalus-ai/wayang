import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/core/unsaved_edits.dart';

void main() {
  test('hasUnsaved is true only while some editor is dirty', () {
    expect(hasUnsaved(const {}), isFalse);
    expect(hasUnsaved(const {'bpmn'}), isTrue);
  });

  test('the close guard is a no-op outside a browser', () {
    setBrowserCloseGuard(true);
    setBrowserCloseGuard(false);
  });

  test('provider starts empty', () {
    expect(ProviderContainer().read(unsavedEditorsProvider), isEmpty);
  });
}
