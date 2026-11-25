import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/api_client.dart';

void main() {
  test('connection problems tell the person what to do', () {
    expect(const WayangApiException(401, 'UNAUTHORIZED', 'Authentication required').hint, contains('Settings'));
    expect(const WayangApiException(0, 'UNREACHABLE', 'Cannot reach the server: x').hint, contains('server address'));
    expect(const WayangApiException(0, 'TIMEOUT', 'The server did not answer in time').hint, contains('Retry'));
    expect(const WayangApiException(403, 'FORBIDDEN', 'no').hint, contains('role'));
  });

  test('ordinary failures get no hint and errText keeps the server message and details', () {
    const e = WayangApiException(422, 'VALIDATION', 'Invalid', ['name is required']);
    expect(e.hint, isNull);
    expect(errText(e), 'Invalid\nname is required');
    expect(errText(const WayangApiException(409, 'CONFLICT', 'changed')), 'changed');
    expect(errText(StateError('x')), contains('x'));
  });

  test('errText appends the hint after the message', () {
    expect(errText(const WayangApiException(401, 'UNAUTHORIZED', 'Authentication required')), startsWith('Authentication required\nOpen Settings'));
  });
}
