import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../data/component_inputs.dart';
import '../../models/wayang.dart';
import '../../providers/app_providers.dart';

/// Components matching [query]: every whitespace-separated word must appear (case-insensitive) in the name, id, category, kind,
/// executor type, description or a capability. An empty query keeps everything.
List<ComponentCatalogEntry> filterComponents(List<ComponentCatalogEntry> all, String query) {
  final words = query.toLowerCase().split(RegExp(r'\s+')).where((w) => w.isNotEmpty).toList();
  if (words.isEmpty) return all;
  return [
    for (final c in all)
      if (words.every(_haystack(c).contains)) c,
  ];
}

String _haystack(ComponentCatalogEntry c) =>
    [c.displayName, c.id, c.category, c.kind, c.executorType, c.description, ...c.capabilities].join(' ').toLowerCase();

/// Read-only view of the active runtime's reusable capabilities and config contracts.
/// Editing/execution remains in the owning designer and runtime APIs.
class ComponentCatalogScreen extends ConsumerStatefulWidget {
  const ComponentCatalogScreen({super.key});

  @override
  ConsumerState<ComponentCatalogScreen> createState() => _ComponentCatalogScreenState();
}

class _ComponentCatalogScreenState extends ConsumerState<ComponentCatalogScreen> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final catalog = ref.watch(componentCatalogProvider);
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const ScreenHeader(
            title: 'Component Catalog',
            subtitle: 'Reusable capabilities available in this runtime profile, including their configuration contracts.',
            icon: Icons.widgets_outlined,
          ),
          const SizedBox(height: 12),
          TextField(
            decoration: const InputDecoration(
              isDense: true,
              prefixIcon: Icon(Icons.search, size: 18),
              hintText: 'Search components (name, category, capability…)',
            ),
            onChanged: (v) => setState(() => _query = v),
          ),
          const SizedBox(height: 12),
          Expanded(
            child: catalog.when(
              loading: () => const LoadingView(label: 'Loading component catalog…'),
              error: (e, _) => ErrorView(
                message: '$e',
                onRetry: () => ref.invalidate(componentCatalogProvider),
              ),
              data: (items) => items.isEmpty
                  ? const EmptyState(
                      icon: Icons.widgets_outlined,
                      title: 'No components available',
                      message: 'Check the active runtime profile and installed extensions.',
                    )
                  : _list(filterComponents(items, _query), items.length),
            ),
          ),
        ],
      ),
    );
  }
}

Widget _list(List<ComponentCatalogEntry> shown, int total) {
  if (shown.isEmpty) {
    return const EmptyState(icon: Icons.search_off, title: 'No component matches', message: 'Try fewer or different words.');
  }
  return ListView.separated(
    itemCount: shown.length,
    separatorBuilder: (_, __) => const SizedBox(height: 10),
    itemBuilder: (context, index) => _ComponentCard(component: shown[index]),
  );
}

class _ComponentCard extends StatelessWidget {
  const _ComponentCard({required this.component});
  final ComponentCatalogEntry component;

  @override
  Widget build(BuildContext context) {
    final properties = asMap(component.configSchema['properties']);
    final requiredFields = asList(component.configSchema['required']).map((e) => '$e').toSet();
    final dependencies = component.dependencies;
    return Card(
      color: AppColors.darkSurface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadius.md),
        side: const BorderSide(color: AppColors.darkBorder),
      ),
      child: ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        leading: const Icon(Icons.extension_outlined, color: AppColors.primaryBright),
        title: Row(
          children: [
            Expanded(child: Text(component.displayName, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13))),
            Text(component.version, style: const TextStyle(fontSize: 10.5, color: AppColors.textMutedDark)),
          ],
        ),
        subtitle: Text(
          '${component.category} · ${component.kind} · ${component.executorType}',
          style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark),
        ),
        children: [
          if (component.description.isNotEmpty)
            Align(alignment: Alignment.centerLeft, child: Text(component.description, style: const TextStyle(fontSize: 12))),
          const SizedBox(height: 12),
          _MetaLine(label: 'Component ID', value: component.id),
          _MetaLine(label: 'Isolation', value: component.isolationLevel),
          if (component.requiredFeatures.isNotEmpty)
            _MetaLine(label: 'Required profile features', value: component.requiredFeatures.join(', ')),
          if (component.capabilities.isNotEmpty)
            _MetaLine(label: 'Capabilities', value: component.capabilities.join(', ')),
          if (dependencies.isNotEmpty)
            _MetaLine(label: 'Dependencies', value: dependencies.map((d) => '${d['componentId']}@${d['version']}').join(', ')),
          const Divider(height: 22),
          const Align(
            alignment: Alignment.centerLeft,
            child: Text('Configuration contract', style: TextStyle(fontWeight: FontWeight.w700, fontSize: 12)),
          ),
          if (properties.isEmpty)
            const Align(alignment: Alignment.centerLeft, child: Text('No configuration fields.', style: TextStyle(fontSize: 11, color: AppColors.textMutedDark)))
          else
            for (final field in properties.entries)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      flex: 2,
                      child: Text(
                        '${field.key}${requiredFields.contains(field.key) ? ' *' : ''}',
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 11.5, fontWeight: FontWeight.w600),
                      ),
                    ),
                    Expanded(
                      flex: 3,
                      child: Text(
                        _fieldSummary(asMap(field.value)),
                        style: const TextStyle(fontSize: 11, color: AppColors.textMutedDark),
                      ),
                    ),
                  ],
                ),
              ),
          if (componentInputs(component).isNotEmpty) ...[
            const SizedBox(height: 10),
            _MetaLine(
              label: 'Inputs (mandatory ones only warn when missing)',
              value: componentInputs(component).map((i) => '${i.name}: ${i.type}${i.mandatory ? ' (mandatory)' : ''}').join(' · '),
            ),
          ],
          if (component.uiHints.keys.any((k) => k != 'inputs')) ...[
            const SizedBox(height: 10),
            _MetaLine(
                label: 'UI hints',
                value: component.uiHints.entries.where((e) => e.key != 'inputs').map((e) => '${e.key}: ${e.value}').join(' · ')),
          ],
        ],
      ),
    );
  }

  String _fieldSummary(Map<String, dynamic> schema) {
    final type = schema['type'] is List ? (schema['type'] as List).join(' | ') : (schema['type'] ?? 'any').toString();
    final details = <String>[type];
    if (schema['enum'] is List) details.add('options: ${(schema['enum'] as List).join(', ')}');
    if (schema['minimum'] != null) details.add('min ${schema['minimum']}');
    if (schema['maximum'] != null) details.add('max ${schema['maximum']}');
    if (schema['description'] is String && (schema['description'] as String).isNotEmpty) details.add(schema['description'] as String);
    return details.join(' · ');
  }
}

class _MetaLine extends StatelessWidget {
  const _MetaLine({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 7),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(width: 150, child: Text(label, style: const TextStyle(fontSize: 10.5, color: AppColors.textMutedDark))),
            Expanded(child: SelectableText(value, style: const TextStyle(fontSize: 10.5, fontFamily: 'monospace'))),
          ],
        ),
      );
}
