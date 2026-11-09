import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/workspace_model.dart';
import '../../providers/app_providers.dart';
import 'report_view.dart';

/// Every starter template the server offers, with a form built from the server's own parameter list. A new template needs no
/// Studio change. Checking is a dry run; creating never starts anything (triggers arrive disabled).
class TemplateGallery extends ConsumerStatefulWidget {
  const TemplateGallery({super.key, required this.onCreated});

  /// Called after a project was created, so the caller can refresh its lists.
  final VoidCallback onCreated;

  @override
  ConsumerState<TemplateGallery> createState() => _TemplateGalleryState();
}

class _TemplateGalleryState extends ConsumerState<TemplateGallery> {
  String? _selected;
  final Map<String, TextEditingController> _text = {};
  final Map<String, bool> _flags = {};
  ImportReport? _report;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in _text.values) {
      c.dispose();
    }
    super.dispose();
  }

  void _select(TemplateInfo t) {
    setState(() {
      _selected = t.id;
      _report = null;
      for (final c in _text.values) {
        c.dispose();
      }
      _text.clear();
      _flags.clear();
      for (final p in t.params) {
        if (p.isFlag) {
          _flags[p.name] = p.defaultValue == 'true';
        } else {
          _text[p.name] = TextEditingController(text: p.defaultValue ?? '');
        }
      }
    });
  }

  Map<String, String> _values() => {
        for (final e in _text.entries) e.key: e.value.text,
        for (final e in _flags.entries) e.key: '${e.value}',
      };

  Future<void> _run(TemplateInfo t, {required bool dry}) async {
    setState(() => _busy = true);
    try {
      final r = await ref.read(repositoryProvider).instantiateTemplate(t.id, t.request(_values()), dryRun: dry);
      if (!mounted) return;
      setState(() => _report = r);
      if (!dry) {
        widget.onCreated();
        if (r.ok) {
          showToast(context, t.secrets.isEmpty
              ? 'Created. Triggers are off until you enable them.'
              : 'Created. Triggers are off: set ${t.secrets.join(', ')} under Secrets, then enable them.');
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() => _report = null);
        showToast(context, errText(e));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final templates = ref.watch(templatesProvider);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('Template gallery', style: TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 8),
          templates.when(
            loading: () => const LinearProgressIndicator(),
            error: (e, _) => Text(errText(e)),
            data: (list) {
              if (list.isEmpty) return const Text('This server offers no templates (its profile has no agents).');
              final t = list.where((x) => x.id == _selected).firstOrNull;
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Wrap(spacing: 8, runSpacing: 8, children: [
                  for (final x in list) ChoiceChip(label: Text(x.name), selected: x.id == _selected, onSelected: (_) => _select(x)),
                ]),
                if (t != null) ..._form(t),
              ]);
            },
          ),
        ]),
      ),
    );
  }

  List<Widget> _form(TemplateInfo t) {
    final problems = t.problems(_values());
    return [
      const SizedBox(height: 12),
      Text(t.description),
      if (t.secrets.isNotEmpty) ...[
        const SizedBox(height: 6),
        Text('Needs the secrets: ${t.secrets.join(', ')} (names only; set them under Secrets).', style: const TextStyle(fontSize: 12)),
      ],
      const SizedBox(height: 8),
      Wrap(spacing: 12, runSpacing: 8, children: [
        for (final p in t.params.where((p) => !p.isFlag))
          SizedBox(
            width: 260,
            child: TextField(
              controller: _text[p.name],
              decoration: InputDecoration(
                labelText: p.required ? '${p.name} *' : p.name,
                helperText: p.description,
                helperMaxLines: 3,
                errorText: problems[p.name],
                isDense: true,
              ),
              onChanged: (_) => setState(() {}),
            ),
          ),
      ]),
      for (final p in t.params.where((p) => p.isFlag))
        SwitchListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          title: Text(p.name),
          subtitle: Text(p.description),
          value: _flags[p.name] ?? false,
          onChanged: (v) => setState(() => _flags[p.name] = v),
        ),
      const SizedBox(height: 8),
      Row(children: [
        OutlinedButton(onPressed: _busy || problems.isNotEmpty ? null : () => _run(t, dry: true), child: const Text('Check')),
        const SizedBox(width: 8),
        ElevatedButton(onPressed: _busy || problems.isNotEmpty ? null : () => _run(t, dry: false), child: const Text('Create project')),
      ]),
      if (_report != null) ...[const SizedBox(height: 8), ReportView(report: _report!)],
    ];
  }
}
