import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
import '../../data/component_inputs.dart';
import '../../design_system/wds.dart';
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
          const WdsPageHeader(
            eyebrow: 'Runtime',
            title: 'Component Catalog',
            description: 'Reusable capabilities available in this runtime profile, including their configuration contracts.',
          ),
          const SizedBox(height: 16),
          WdsFilterToolbar(
            search: WdsSearchField(
              hint: 'Search components (name, category, capability…)',
              onChanged: (value) => setState(() => _query = value),
            ),
          ),
          const SizedBox(height: 16),
          Expanded(
            child: catalog.when(
              loading: () => const LoadingView(label: 'Loading component catalog…'),
              error: (error, _) => ErrorView(
                message: '$error',
                onRetry: () => ref.invalidate(componentCatalogProvider),
              ),
              data: (items) => items.isEmpty
                  ? const WdsEmptyState(
                      icon: Icons.widgets_outlined,
                      title: 'No components available',
                      description: 'Check the active runtime profile and installed extensions.',
                    )
                  : _list(filterComponents(items, _query)),
            ),
          ),
        ],
      ),
    );
  }
}

Widget _list(List<ComponentCatalogEntry> shown) {
  if (shown.isEmpty) {
    return const WdsEmptyState(
      icon: Icons.search_off,
      title: 'No component matches',
      description: 'Try fewer or different words.',
    );
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

    return WdsSurface(
      padding: EdgeInsets.zero,
      child: ExpansionTile(
        tilePadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        leading: Icon(Icons.extension_outlined, color: Theme.of(context).colorScheme.primary),
        title: Row(
          children: [
            Expanded(
              child: Text(
                component.displayName,
                style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
            WdsBadge(label: component.version, tone: WdsBadgeTone.neutral, dense: true),
          ],
        ),
        subtitle: Text(
          '${component.category} · ${component.kind} · ${component.executorType}',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
        ),
        children: [
          if (component.description.isNotEmpty)
            Align(
              alignment: Alignment.centerLeft,
              child: Text(component.description, style: Theme.of(context).textTheme.bodyMedium),
            ),
          const SizedBox(height: 12),
          WdsFieldGroup(
            spacing: 10,
            children: [
              _MetaLine(label: 'Component ID', value: component.id),
              _MetaLine(label: 'Isolation', value: component.isolationLevel),
              if (component.requiredFeatures.isNotEmpty)
                _MetaLine(label: 'Required profile features', value: component.requiredFeatures.join(', ')),
              if (component.capabilities.isNotEmpty)
                _MetaLine(label: 'Capabilities', value: component.capabilities.join(', ')),
              if (dependencies.isNotEmpty)
                _MetaLine(
                  label: 'Dependencies',
                  value: dependencies.map((dependency) => '${dependency['componentId']}@${dependency['version']}').join(', '),
                ),
            ],
          ),
          const SizedBox(height: 18),
          WdsInspectorSection(
            title: 'Configuration contract',
            description: 'Schema fields exposed by this runtime component',
            child: properties.isEmpty
                ? Text('No configuration fields.', style: Theme.of(context).textTheme.bodySmall)
                : WdsFieldGroup(
                    spacing: 10,
                    children: [
                      for (final field in properties.entries)
                        WdsPropertyRow(
                          label: '${field.key}${requiredFields.contains(field.key) ? ' *' : ''}',
                          value: SelectableText(
                            _fieldSummary(asMap(field.value)),
                            style: Theme.of(context).textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
                          ),
                          valueAlignment: CrossAxisAlignment.start,
                        ),
                    ],
                  ),
          ),
          if (componentInputs(component).isNotEmpty) ...[
            const SizedBox(height: 14),
            _MetaLine(
              label: 'Inputs (mandatory ones only warn when missing)',
              value: componentInputs(component)
                  .map((input) => '${input.name}: ${input.type}${input.mandatory ? ' (mandatory)' : ''}')
                  .join(' · '),
            ),
          ],
          if (component.uiHints.keys.any((key) => key != 'inputs')) ...[
            const SizedBox(height: 10),
            _MetaLine(
              label: 'UI hints',
              value: component.uiHints.entries
                  .where((entry) => entry.key != 'inputs')
                  .map((entry) => '${entry.key}: ${entry.value}')
                  .join(' · '),
            ),
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
    if (schema['description'] is String && (schema['description'] as String).isNotEmpty) {
      details.add(schema['description'] as String);
    }
    return details.join(' · ');
  }
}

class _MetaLine extends StatelessWidget {
  const _MetaLine({required this.label, required this.value});
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => WdsPropertyRow(
        label: label,
        value: SelectableText(value, style: Theme.of(context).textTheme.bodySmall?.copyWith(fontFamily: 'monospace')),
        valueAlignment: CrossAxisAlignment.start,
      );
}
