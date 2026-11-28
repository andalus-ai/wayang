import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'components.dart';
import 'tokens.dart';

/// A single example in the WDS component catalog.
///
/// The preview is a normal Flutter widget. The host owns any state or domain
/// behavior used by that preview; the showcase only displays it.
@immutable
class WdsShowcaseExample {
  const WdsShowcaseExample({
    required this.title,
    required this.category,
    required this.description,
    required this.preview,
    required this.code,
    this.tags = const [],
  });

  final String title;
  final String category;
  final String description;
  final Widget preview;
  final String code;
  final List<String> tags;

  bool matches(String query) {
    final normalized = query.trim().toLowerCase();
    if (normalized.isEmpty) return true;
    return <String>[title, category, description, ...tags]
        .any((value) => value.toLowerCase().contains(normalized));
  }
}

/// A searchable, theme-switchable catalog for reviewing WDS components.
///
/// This widget is intended for a showcase route or an internal design-system
/// page. It does not register routes, change the application theme, or own
/// component business logic.
class WdsComponentShowcase extends StatefulWidget {
  const WdsComponentShowcase({
    super.key,
    required this.examples,
    this.title = 'Component showcase',
    this.description = 'Explore reusable Wayang Design System components.',
  });

  final List<WdsShowcaseExample> examples;
  final String title;
  final String description;

  @override
  State<WdsComponentShowcase> createState() => _WdsComponentShowcaseState();
}

class _WdsComponentShowcaseState extends State<WdsComponentShowcase> {
  final _searchController = TextEditingController();
  String _selectedCategory = 'All';
  bool _darkPreview = false;

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<String> get _categories {
    final categories = widget.examples.map((example) => example.category).toSet().toList()
      ..sort();
    return ['All', ...categories];
  }

  List<WdsShowcaseExample> get _visibleExamples => widget.examples.where((example) {
        final categoryMatches = _selectedCategory == 'All' || example.category == _selectedCategory;
        return categoryMatches && example.matches(_searchController.text);
      }).toList();

  ThemeData _previewTheme(ThemeData source) {
    final brightness = _darkPreview ? Brightness.dark : Brightness.light;
    final generatedScheme = ColorScheme.fromSeed(
      seedColor: source.colorScheme.primary,
      brightness: brightness,
    );
    final scheme = generatedScheme.copyWith(
      primary: source.colorScheme.primary,
      secondary: source.colorScheme.secondary,
      error: source.colorScheme.error,
    );
    return source.copyWith(
      brightness: brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: scheme.surface,
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final visible = _visibleExamples;
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < WdsBreakpoint.medium;
        final columns = constraints.maxWidth >= WdsBreakpoint.wide
            ? 3
            : constraints.maxWidth >= WdsBreakpoint.medium
                ? 2
                : 1;

        return SingleChildScrollView(
          padding: const EdgeInsets.all(WdsSpacing.xl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              WdsPageHeader(
                title: widget.title,
                description: widget.description,
                eyebrow: 'Design system',
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      _darkPreview ? Icons.dark_mode_outlined : Icons.light_mode_outlined,
                      size: 18,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                    const SizedBox(width: WdsSpacing.xs),
                    Text(_darkPreview ? 'Dark preview' : 'Light preview'),
                    const SizedBox(width: WdsSpacing.xs),
                    Switch(
                      value: _darkPreview,
                      onChanged: (value) => setState(() => _darkPreview = value),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: WdsSpacing.xl),
              WdsSearchField(
                hint: 'Search components, categories or tags…',
                controller: _searchController,
                onChanged: (_) => setState(() {}),
              ),
              const SizedBox(height: WdsSpacing.md),
              Wrap(
                spacing: WdsSpacing.sm,
                runSpacing: WdsSpacing.sm,
                children: [
                  for (final category in _categories)
                    ChoiceChip(
                      label: Text(category),
                      selected: _selectedCategory == category,
                      onSelected: (_) => setState(() => _selectedCategory = category),
                    ),
                ],
              ),
              const SizedBox(height: WdsSpacing.lg),
              Row(
                children: [
                  Text(
                    '${visible.length} components',
                    style: theme.textTheme.labelLarge,
                  ),
                  const Spacer(),
                  if (_selectedCategory != 'All' || _searchController.text.isNotEmpty)
                    TextButton.icon(
                      onPressed: () => setState(() {
                        _selectedCategory = 'All';
                        _searchController.clear();
                      }),
                      icon: const Icon(Icons.clear, size: 16),
                      label: const Text('Clear filters'),
                    ),
                ],
              ),
              const SizedBox(height: WdsSpacing.md),
              if (visible.isEmpty)
                const WdsEmptyState(
                  icon: Icons.widgets_outlined,
                  title: 'No components found',
                  description: 'Try another search term or clear the selected category.',
                )
              else
                GridView.builder(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  itemCount: visible.length,
                  gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: columns,
                    crossAxisSpacing: WdsSpacing.lg,
                    mainAxisSpacing: WdsSpacing.lg,
                    mainAxisExtent: compact ? 340 : 360,
                  ),
                  itemBuilder: (context, index) => _WdsShowcaseCard(
                    example: visible[index],
                    previewTheme: _previewTheme(theme),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

class _WdsShowcaseCard extends StatelessWidget {
  const _WdsShowcaseCard({
    required this.example,
    required this.previewTheme,
  });

  final WdsShowcaseExample example;
  final ThemeData previewTheme;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return WdsSurface(
      padding: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(WdsSpacing.lg, WdsSpacing.md, WdsSpacing.md, WdsSpacing.md),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(example.title, style: theme.textTheme.titleSmall),
                      const SizedBox(height: WdsSpacing.xxs),
                      Text(example.category, style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.primary,
                      )),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: 'Copy Dart example',
                  icon: const Icon(Icons.copy_outlined, size: 18),
                  onPressed: () => Clipboard.setData(ClipboardData(text: example.code)),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: WdsSpacing.lg),
            child: Text(example.description, style: theme.textTheme.bodySmall),
          ),
          const SizedBox(height: WdsSpacing.md),
          Expanded(
            child: Container(
              margin: const EdgeInsets.symmetric(horizontal: WdsSpacing.md),
              padding: const EdgeInsets.all(WdsSpacing.md),
              decoration: BoxDecoration(
                color: previewTheme.colorScheme.surfaceContainerHighest,
                borderRadius: BorderRadius.circular(WdsRadius.md),
                border: Border.all(color: previewTheme.colorScheme.outlineVariant),
              ),
              alignment: Alignment.center,
              child: Theme(
                data: previewTheme,
                child: Builder(builder: (context) => example.preview),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(WdsSpacing.lg, WdsSpacing.sm, WdsSpacing.lg, WdsSpacing.md),
            child: ExpansionTile(
              tilePadding: EdgeInsets.zero,
              childrenPadding: const EdgeInsets.only(bottom: WdsSpacing.sm),
              title: const Text('Dart usage'),
              children: [
                Align(
                  alignment: Alignment.centerLeft,
                  child: SelectableText(
                    example.code,
                    style: theme.textTheme.bodySmall?.copyWith(
                      fontFamily: 'monospace',
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Small starter catalog. Applications can provide their own examples and
/// extend this list without changing the showcase component.
class WdsShowcaseCatalog {
  WdsShowcaseCatalog._();

  static List<WdsShowcaseExample> get examples => [
        WdsShowcaseExample(
          title: 'Action buttons',
          category: 'Actions',
          description: 'Semantic variants for primary, secondary and tertiary actions.',
          tags: const ['button', 'action', 'primary', 'secondary'],
          preview: const Wrap(
            alignment: WrapAlignment.center,
            spacing: WdsSpacing.sm,
            runSpacing: WdsSpacing.sm,
            children: [
              WdsButton(label: 'Create', onPressed: _noop),
              WdsButton(label: 'Preview', variant: WdsButtonVariant.secondary, onPressed: _noop),
              WdsButton(label: 'More', variant: WdsButtonVariant.tertiary, onPressed: _noop),
            ],
          ),
          code: """Wrap(
  spacing: WdsSpacing.sm,
  children: [
    WdsButton(label: 'Create', onPressed: onCreate),
    WdsButton(
      label: 'Preview',
      variant: WdsButtonVariant.secondary,
      onPressed: onPreview,
    ),
  ],
)""",
        ),
        WdsShowcaseExample(
          title: 'Status badges',
          category: 'Feedback',
          description: 'Compact semantic status indicators for lists and detail views.',
          tags: const ['badge', 'status', 'success', 'warning', 'error'],
          preview: const Wrap(
            alignment: WrapAlignment.center,
            spacing: WdsSpacing.sm,
            runSpacing: WdsSpacing.sm,
            children: [
              WdsBadge(label: 'Published', tone: WdsBadgeTone.success),
              WdsBadge(label: 'Draft', tone: WdsBadgeTone.neutral),
              WdsBadge(label: 'Attention', tone: WdsBadgeTone.warning),
            ],
          ),
          code: """WdsBadge(
  label: 'Published',
  tone: WdsBadgeTone.success,
)""",
        ),
        WdsShowcaseExample(
          title: 'Empty state',
          category: 'Feedback',
          description: 'A consistent recovery-oriented state for empty catalogs and lists.',
          tags: const ['empty', 'placeholder', 'no data'],
          preview: const WdsEmptyState(
            icon: Icons.inbox_outlined,
            title: 'Nothing here yet',
            description: 'Create your first item to get started.',
          ),
          code: """WdsEmptyState(
  icon: Icons.inbox_outlined,
  title: 'Nothing here yet',
  description: 'Create your first item to get started.',
  action: WdsButton(
    label: 'Create item',
    onPressed: onCreate,
  ),
)""",
        ),
      ];
}

void _noop() {}
