import 'dart:convert';

import 'package:flutter/material.dart';

import '../../core/theme.dart';
import '../../core/utils.dart';
import '../../data/agent_form.dart';

const _json = JsonEncoder.withIndent('  ');

/// Form view of an agent definition. The JSON text stays the source of truth: every edit rewrites it (unknown keys are kept), and a change made
/// elsewhere (opening another agent, the JSON view) is picked up. The server validates; the hints here only come earlier.
class AgentBuilder extends StatefulWidget {
  const AgentBuilder({super.key, required this.controller});
  final TextEditingController controller;

  @override
  State<AgentBuilder> createState() => _AgentBuilderState();
}

class _AgentBuilderState extends State<AgentBuilder> {
  String? _written;
  int _epoch = 0;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onController);
  }

  @override
  void didUpdateWidget(covariant AgentBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onController);
      widget.controller.addListener(_onController);
      _epoch++;
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onController);
    super.dispose();
  }

  void _onController() {
    if (!mounted) return;
    if (widget.controller.text != _written) {
      // changed from outside the form: rebuild every field from the new text
      setState(() => _epoch++);
    }
  }

  Map<String, dynamic>? get _doc => tryParseJsonObject(widget.controller.text);

  void _put(Map<String, dynamic> doc, {bool rebuild = false}) {
    final text = _json.convert(doc);
    _written = text;
    widget.controller.text = text;
    if (mounted) {
      setState(() {
        if (rebuild) _epoch++;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final doc = _doc;
    if (doc == null) {
      return const Center(child: Text('The JSON is not an object. Fix it in the JSON view, then come back.'));
    }
    final persona = AgentPersonaForm.read(doc);
    final guard = AgentGuardrailsForm.read(doc);
    final tools = readTools(doc);
    final system = doc['systemPrompt'] is String ? doc['systemPrompt'] as String : '';
    return ListView(
      children: [
        _section('Basics'),
        _field('model', doc['model'], 'Model (e.g. openai/gpt-4o)', (v) => _put(withText(_doc ?? doc, 'model', v))),
        _field('name', doc['name'], 'Name', (v) => _put(withText(_doc ?? doc, 'name', v))),
        Row(children: [
          Expanded(child: _field('maxSteps', doc['maxSteps'], 'Max steps (1-50)', (v) => _put(withInt(_doc ?? doc, 'maxSteps', v, 1, 50)), number: true)),
          const SizedBox(width: 12),
          Expanded(child: _field('timeoutSeconds', doc['timeoutSeconds'], 'Timeout seconds (1-3600)', (v) => _put(withInt(_doc ?? doc, 'timeoutSeconds', v, 1, 3600)), number: true)),
        ]),
        _section('Persona'),
        _field('role', persona.role, 'Role', (v) => _put(withPersona(_doc ?? doc, _persona(role: v)))),
        _field('tone', persona.tone, 'Tone', (v) => _put(withPersona(_doc ?? doc, _persona(tone: v)))),
        _field('language', persona.language, 'Reply language', (v) => _put(withPersona(_doc ?? doc, _persona(language: v)))),
        _field('boundaries', persona.boundaries.join('\n'), 'Boundaries, one per line (never do these)',
            (v) => _put(withPersona(_doc ?? doc, _persona(boundaries: v.split('\n')))), lines: 3),
        _section('Instructions'),
        _field('systemPrompt', system, 'System prompt ({{ variable }} placeholders allowed)', (v) => _put(withText(_doc ?? doc, 'systemPrompt', v)), lines: 5),
        _field('inputTemplate', doc['inputTemplate'], 'Input template (default {{ input }})', (v) => _put(withText(_doc ?? doc, 'inputTemplate', v)), lines: 2),
        _preview(persona.compose(system)),
        _section('Guardrails (enforced on what the agent returns)'),
        Wrap(spacing: 8, children: [
          for (final k in agentRedactKinds)
            FilterChip(
              label: Text('Redact $k'),
              selected: guard.redact.contains(k),
              onSelected: (on) {
                final cur = AgentGuardrailsForm.read(_doc ?? doc);
                final next = [for (final r in cur.redact) if (r != k) r, if (on) k];
                _put(withGuardrails(_doc ?? doc, AgentGuardrailsForm(maxOutputChars: cur.maxOutputChars, redact: next, blockedTerms: cur.blockedTerms)), rebuild: true);
              },
            ),
        ]),
        _field('maxOutputChars', guard.maxOutputChars, 'Max output characters (1-100000, empty = no cap)', (v) {
          final cur = AgentGuardrailsForm.read(_doc ?? doc);
          final n = int.tryParse(v.trim());
          _put(withGuardrails(_doc ?? doc, AgentGuardrailsForm(maxOutputChars: n, redact: cur.redact, blockedTerms: cur.blockedTerms)));
        }, number: true),
        _field('blockedTerms', guard.blockedTerms.join('\n'), 'Blocked terms, one per line (the whole answer is withheld if one appears)', (v) {
          final cur = AgentGuardrailsForm.read(_doc ?? doc);
          _put(withGuardrails(_doc ?? doc, AgentGuardrailsForm(maxOutputChars: cur.maxOutputChars, redact: cur.redact, blockedTerms: v.split('\n'))));
        }, lines: 2),
        const Padding(
          padding: EdgeInsets.only(top: 4),
          child: Text('Redaction is best effort. Guardrails do not filter the prompt or tool calls; tools that need approval are set below.',
              style: TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
        ),
        _section('Tools'),
        for (var i = 0; i < tools.length; i++) _tool(doc, tools, i),
        Wrap(
          spacing: 8,
          children: [
            TextButton.icon(
              onPressed: () => _put(withTools(_doc ?? doc, [...tools, AgentToolForm(name: _freshToolName(tools))]), rebuild: true),
              icon: const Icon(Icons.add_rounded, size: 18),
              label: const Text('Add tool'),
            ),
            TextButton.icon(
              key: const ValueKey('add-knowledge-tool'),
              onPressed: () => _put(withTools(_doc ?? doc, [...tools, knowledgeSearchTool('search_knowledge', 'my-knowledge')]), rebuild: true),
              icon: const Icon(Icons.menu_book_rounded, size: 18),
              label: const Text('Add knowledge search'),
            ),
          ],
        ),
        const SizedBox(height: 24),
      ],
    );
  }

  AgentPersonaForm _persona({String? role, String? tone, String? language, List<String>? boundaries}) {
    final cur = AgentPersonaForm.read(_doc ?? const <String, dynamic>{});
    return AgentPersonaForm(
      role: role ?? cur.role,
      tone: tone ?? cur.tone,
      language: language ?? cur.language,
      boundaries: boundaries ?? cur.boundaries,
    );
  }

  String _freshToolName(List<AgentToolForm> tools) {
    final names = {for (final t in tools) t.name};
    var n = tools.length + 1;
    while (names.contains('tool$n')) {
      n++;
    }
    return 'tool$n';
  }

  Widget _tool(Map<String, dynamic> doc, List<AgentToolForm> tools, int i) {
    final t = tools[i];
    void update(AgentToolForm next, {bool rebuild = false}) {
      final cur = readTools(_doc ?? doc);
      if (i >= cur.length) return;
      cur[i] = next;
      _put(withTools(_doc ?? doc, cur), rebuild: rebuild);
    }

    final hints = toolHints(t);
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Expanded(child: _field('tool$i-name', t.name, 'Tool name', (v) => update(AgentToolForm(name: v.trim(), type: t.type, description: t.description, config: t.config, needsApproval: t.needsApproval)))),
            const SizedBox(width: 12),
            Expanded(child: _field('tool$i-type', t.type, 'Connector type (http, mcp, ...)', (v) => update(AgentToolForm(name: t.name, type: v, description: t.description, config: t.config, needsApproval: t.needsApproval)))),
            IconButton(
              tooltip: 'Remove tool',
              icon: const Icon(Icons.delete_outline_rounded, size: 18),
              onPressed: () {
                final cur = readTools(_doc ?? doc)..removeAt(i);
                _put(withTools(_doc ?? doc, cur), rebuild: true);
              },
            ),
          ]),
          _field('tool$i-desc', t.description, 'Description the model sees', (v) => update(AgentToolForm(name: t.name, type: t.type, description: v, config: t.config, needsApproval: t.needsApproval))),
          SwitchListTile(
            dense: true,
            contentPadding: EdgeInsets.zero,
            title: const Text('Needs human approval before each call', style: TextStyle(fontSize: 13)),
            value: t.needsApproval,
            onChanged: (on) => update(AgentToolForm(name: t.name, type: t.type, description: t.description, config: t.config, needsApproval: on), rebuild: true),
          ),
          _ConfigField(
            key: ValueKey('tool$i-config-$_epoch'),
            initial: t.config,
            onValid: (m) => update(AgentToolForm(name: t.name, type: t.type, description: t.description, config: m, needsApproval: t.needsApproval)),
          ),
          for (final h in hints) Padding(padding: const EdgeInsets.only(top: 4), child: Text(h, style: const TextStyle(fontSize: 11, color: AppColors.warning))),
        ]),
      ),
    );
  }

  Widget _section(String title) => Padding(
        padding: const EdgeInsets.only(top: 18, bottom: 6),
        child: Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
      );

  Widget _field(String name, Object? value, String label, void Function(String) onChanged, {int lines = 1, bool number = false}) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: TextFormField(
        key: ValueKey('$name-$_epoch'),
        initialValue: value == null ? '' : '$value',
        minLines: lines,
        maxLines: lines == 1 ? 1 : lines + 4,
        keyboardType: number ? TextInputType.number : null,
        decoration: InputDecoration(labelText: label, isDense: true, border: const OutlineInputBorder()),
        onChanged: onChanged,
      ),
    );
  }

  Widget _preview(String composed) => Container(
        width: double.infinity,
        margin: const EdgeInsets.only(top: 4),
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(border: Border.all(color: AppColors.textMutedDark.withValues(alpha: 0.4)), borderRadius: BorderRadius.circular(6)),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          const Text('What the model is told (placeholders are filled in at run time)', style: TextStyle(fontSize: 11, color: AppColors.textMutedDark)),
          const SizedBox(height: 4),
          SelectableText(composed.isEmpty ? '(nothing yet)' : composed, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
        ]),
      );
}

/// A tool's free-form config as JSON: written back only while it parses as an object.
class _ConfigField extends StatefulWidget {
  const _ConfigField({super.key, required this.initial, required this.onValid});
  final Map<String, dynamic> initial;
  final void Function(Map<String, dynamic>) onValid;

  @override
  State<_ConfigField> createState() => _ConfigFieldState();
}

class _ConfigFieldState extends State<_ConfigField> {
  late final TextEditingController _c = TextEditingController(text: widget.initial.isEmpty ? '' : _json.convert(widget.initial));
  String? _error;

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: _c,
      minLines: 2,
      maxLines: 8,
      style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
      decoration: InputDecoration(
        labelText: 'Config (JSON; use \${secret:NAME} for credentials)',
        isDense: true,
        border: const OutlineInputBorder(),
        errorText: _error,
      ),
      onChanged: (text) {
        final m = tryParseJsonObject(text);
        if (m == null) {
          setState(() => _error = 'Not a JSON object: not saved yet');
        } else {
          setState(() => _error = null);
          widget.onValid(m);
        }
      },
    );
  }
}
