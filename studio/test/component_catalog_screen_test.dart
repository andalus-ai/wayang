import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/features/components/component_catalog_screen.dart';
import 'package:wayang_studio/models/wayang.dart';

ComponentCatalogEntry _entry({
  required String id,
  required String displayName,
  String category = 'AI',
  String kind = 'NODE',
  String executorType = 'JAVA',
  String description = '',
  List<String> capabilities = const [],
}) =>
    ComponentCatalogEntry(
      id: id,
      version: '1.0.0',
      kind: kind,
      executorType: executorType,
      description: description,
      displayName: displayName,
      category: category,
      requiredFeatures: const [],
      dependencies: const [],
      isolationLevel: 'IN_PROCESS',
      capabilities: capabilities,
      configSchema: const {},
      uiHints: const {},
    );

void main() {
  final rag = _entry(
    id: 'wayang.knowledge.rag-search',
    displayName: 'RAG Search',
    category: 'Knowledge',
    description: 'Search indexed documents',
    capabilities: const ['vector-search', 'retrieval'],
  );
  final http = _entry(
    id: 'wayang.http.request',
    displayName: 'HTTP Request',
    category: 'Integration',
    kind: 'CONNECTOR',
    executorType: 'HTTP',
  );

  test('component search matches every term across display metadata', () {
    expect(filterComponents([rag, http], 'rag search'), [rag]);
    expect(filterComponents([rag, http], 'knowledge retrieval'), [rag]);
    expect(filterComponents([rag, http], 'connector http'), [http]);
    expect(filterComponents([rag, http], 'no such component'), isEmpty);
  });

  test('empty and whitespace-only queries preserve the original catalog', () {
    expect(filterComponents([rag, http], ''), [rag, http]);
    expect(filterComponents([rag, http], '   '), [rag, http]);
  });
}
