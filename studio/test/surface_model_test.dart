import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/surface_model.dart';

void main() {
  test('the sample parses into a card with its children', () {
    final s = UiSurface.parse({
      'id': 'a',
      'title': 'T',
      'rootNode': {
        '_type': 'card',
        'children': [
          {'_type': 'text', 'text': 'hi'},
          {'_type': 'badge', 'label': 'x', 'variant': 'warning'},
        ],
      },
    })!;
    expect(s.title, 'T');
    final card = s.root as CardSurfaceNode;
    expect(card.children.length, 2);
    expect((card.children[0] as TextSurfaceNode).text, 'hi');
  });

  test('not a surface gives null', () {
    expect(UiSurface.parse('x'), isNull);
    expect(UiSurface.parse({'id': 'a'}), isNull);
  });

  test('unknown and image nodes become a visible note, never a fetch', () {
    final s = UiSurface.parse({'rootNode': {'_type': 'image', 'url': 'http://evil/x.png'}})!;
    expect((s.root as TextSurfaceNode).text, contains('Not shown'));
  });

  test('actions: only refresh and a local route survive', () {
    expect(SurfaceAction.parse({'_type': 'refresh'}).kind, 'refresh');
    expect(SurfaceAction.parse({'_type': 'navigate', 'route': '/runs'}).route, '/runs');
    expect(SurfaceAction.parse({'_type': 'navigate', 'route': '//evil.com'}).kind, 'none');
    expect(SurfaceAction.parse({'_type': 'navigate', 'route': 'https://evil.com'}).kind, 'none');
    expect(SurfaceAction.parse({'_type': 'invoke', 'tool': 'x'}).kind, 'none');
  });

  test('depth and node count are capped', () {
    Map<String, dynamic> nest(int d) => d == 0 ? {'_type': 'text', 'text': 'x'} : {'_type': 'container', 'children': [nest(d - 1)]};
    var n = UiSurface.parse({'rootNode': nest(20)})!.root;
    var depth = 1;
    while (n is ContainerSurfaceNode && n.children.isNotEmpty) {
      n = n.children.first;
      depth++;
    }
    expect(depth <= surfaceMaxDepth, isTrue);
    final wide = UiSurface.parse({
      'rootNode': {'_type': 'container', 'children': [for (var i = 0; i < 1000; i++) {'_type': 'text', 'text': '$i'}]},
    })!;
    expect((wide.root as ContainerSurfaceNode).children.length <= surfaceMaxNodes, isTrue);
  });

  test('confidence is clamped and long text is cut', () {
    final p = UiSurface.parse({'rootNode': {'_type': 'provenance', 'confidence': 7}})!.root as ProvenanceSurfaceNode;
    expect(p.confidence, 1.0);
    final t = UiSurface.parse({'rootNode': {'_type': 'text', 'text': 'a' * 50000}})!.root as TextSurfaceNode;
    expect(t.text.length, surfaceMaxText);
  });

  test('the check reads problems and the rebuilt surface', () {
    final c = SurfaceCheck.fromJson({'ok': false, 'problems': ['bad'], 'surface': null});
    expect(c.ok, isFalse);
    expect(c.problems, ['bad']);
    expect(c.surface, isNull);
    expect(UiSurface.parse({'rootNode': {'_type': 'text', 'text': 't'}}), isNotNull);
  });
}
