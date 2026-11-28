import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../design_system/wds.dart';
import '../../data/api_client.dart';
import '../../data/surface_model.dart';
import '../../providers/app_providers.dart';
import 'surface_view.dart';

/// Playground for declarative UI surfaces: paste one, let the server check it, see it drawn.
class SurfacesScreen extends ConsumerStatefulWidget {
  const SurfacesScreen({super.key});

  @override
  ConsumerState<SurfacesScreen> createState() => _SurfacesScreenState();
}

class _SurfacesScreenState extends ConsumerState<SurfacesScreen> {
  final _input = TextEditingController(text: sampleSurfaceJson);
  SurfaceCheck? _check;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  Future<void> _run() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final r = await ref.read(repositoryProvider).validateSurface(_input.text);
      if (mounted) setState(() => _check = r);
    } on WayangApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) setState(() => _error = 'Could not check the surface.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _act(SurfaceAction a) {
    if (a.kind == 'refresh') {
      _run();
    } else if (a.kind == 'navigate' && a.route != null) {
      final key = a.route!.split('/').where((s) => s.isNotEmpty).firstOrNull;
      final known = [platformSectionDashboard, ...platformSectionsAfterPlugins].any((s) => s.key == key);
      if (known) ref.read(selectedNavKeyProvider.notifier).state = key!;
    }
  }

  @override
  Widget build(BuildContext context) {
    final check = _check;
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const WdsPageHeader(
          title: 'UI surfaces',
          eyebrow: 'Studio · Preview',
          description: 'Validate declarative UI JSON with the server, then inspect its read-only rendering. Pasted surfaces cannot submit actions or fetch images.',
        ),
        const SizedBox(height: 16),
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final compact = constraints.maxWidth < 760;
              final editor = _editor();
              final preview = _preview(check);
              if (compact) {
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    SizedBox(height: constraints.maxHeight * 0.48, child: editor),
                    const SizedBox(height: 16),
                    Expanded(child: preview),
                  ],
                );
              }
              return Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Expanded(child: editor),
                  const SizedBox(width: 16),
                  Expanded(child: preview),
                ],
              );
            },
          ),
        ),
      ]),
    );
  }

  Widget _editor() => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const WdsSectionHeader(title: 'Surface definition', description: 'JSON input · server-side validation'),
          const SizedBox(height: 12),
          Expanded(
            child: TextField(
              controller: _input,
              maxLines: null,
              expands: true,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
              decoration: const InputDecoration(
                border: OutlineInputBorder(),
                alignLabelWithHint: true,
                labelText: 'Surface JSON',
              ),
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              WdsButton(
                onPressed: _busy ? null : _run,
                icon: Icons.check_rounded,
                label: _busy ? 'Checking…' : 'Check and render',
                isLoading: _busy,
              ),
              WdsButton(
                variant: WdsButtonVariant.tertiary,
                onPressed: () => setState(() { _input.text = sampleSurfaceJson; _check = null; _error = null; }),
                label: 'Sample',
              ),
              WdsButton(
                variant: WdsButtonVariant.tertiary,
                onPressed: () => setState(() { _input.text = sampleA2uiJson; _check = null; _error = null; }),
                label: 'A2UI sample',
              ),
            ],
          ),
        ],
      );

  Widget _preview(SurfaceCheck? check) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          WdsSectionHeader(
            title: 'Rendered preview',
            description: check == null ? 'No validation result yet' : 'Server validation result',
            actions: [
              if (_busy) const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
              if (!_busy && check != null)
                WdsBadge(
                  label: check.ok && check.surface != null ? 'VALID' : 'REJECTED',
                  tone: check.ok && check.surface != null ? WdsBadgeTone.success : WdsBadgeTone.danger,
                ),
            ],
          ),
          const SizedBox(height: 12),
          Expanded(
            child: SingleChildScrollView(
              child: WdsSurface(
                padding: const EdgeInsets.all(20),
                child: _result(check),
              ),
            ),
          ),
        ],
      );

  Widget _result(SurfaceCheck? check) {
    if (_error != null) return WdsFieldMessage(message: _error!);
    if (check == null) return const WdsEmptyState(icon: Icons.preview_outlined, title: 'Nothing to preview', description: 'Choose a sample or paste a surface definition, then run server validation.');
    if (!check.ok || check.surface == null) {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const WdsFieldMessage(message: 'The server refused this surface.'),
        const SizedBox(height: 8),
        for (final p in check.problems) Padding(padding: const EdgeInsets.only(bottom: 4), child: WdsFieldMessage(message: p)),
      ]);
    }
    return SurfaceView(surface: check.surface!, onAction: _act);
  }
}
