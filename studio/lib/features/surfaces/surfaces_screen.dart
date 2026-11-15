import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets.dart';
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
        const ScreenHeader(
          title: 'UI surfaces',
          subtitle: 'Paste a declarative surface; the server checks it and Studio draws it. Read-only: no images, no submitting buttons.',
          icon: Icons.dashboard_customize_outlined,
        ),
        const SizedBox(height: 16),
        Expanded(
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Expanded(
                  child: TextField(
                    controller: _input,
                    maxLines: null,
                    expands: true,
                    style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                    decoration: const InputDecoration(border: OutlineInputBorder(), alignLabelWithHint: true, labelText: 'Surface JSON'),
                  ),
                ),
                const SizedBox(height: 8),
                Align(
                  alignment: Alignment.centerLeft,
                  child: FilledButton.icon(
                    onPressed: _busy ? null : _run,
                    icon: const Icon(Icons.check_rounded, size: 18),
                    label: Text(_busy ? 'Checking…' : 'Check and render'),
                  ),
                ),
              ]),
            ),
            const SizedBox(width: 16),
            Expanded(child: SingleChildScrollView(child: _result(check))),
          ]),
        ),
      ]),
    );
  }

  Widget _result(SurfaceCheck? check) {
    if (_error != null) return Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error));
    if (check == null) return const Text('Nothing checked yet.');
    if (!check.ok || check.surface == null) {
      return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        const Text('The server refused this surface:'),
        const SizedBox(height: 8),
        for (final p in check.problems) Padding(padding: const EdgeInsets.only(bottom: 4), child: Text('• $p')),
      ]);
    }
    return SurfaceView(surface: check.surface!, onAction: _act);
  }
}
