/// Pure helpers behind the Agents form. Every function takes the agent document and returns a new one, keeping the keys the form does not know
/// about (the JSON view stays authoritative, and the server validates). No Flutter imports: unit-tested on their own.
library;

const agentRedactKinds = ['email', 'phone', 'card'];

Map<String, dynamic> _copy(Map<String, dynamic> doc) => Map<String, dynamic>.from(doc);

Map<String, dynamic> _options(Map<String, dynamic> doc) {
  final o = doc['options'];
  return o is Map ? Map<String, dynamic>.from(o) : <String, dynamic>{};
}

Map<String, dynamic> _withOption(Map<String, dynamic> doc, String key, Object? value) {
  final out = _copy(doc);
  final o = _options(doc);
  if (value == null) {
    o.remove(key);
  } else {
    o[key] = value;
  }
  if (o.isEmpty) {
    out.remove('options');
  } else {
    out['options'] = o;
  }
  return out;
}

String _text(Object? v) => v is String ? v : '';

/// Sets a text field; an empty text removes the key.
Map<String, dynamic> withText(Map<String, dynamic> doc, String key, String value) {
  final out = _copy(doc);
  if (value.trim().isEmpty) {
    out.remove(key);
  } else {
    out[key] = value;
  }
  return out;
}

/// Sets a whole-number field clamped to [min, max]; unparseable or empty text removes the key (the server then applies its default).
Map<String, dynamic> withInt(Map<String, dynamic> doc, String key, String value, int min, int max) {
  final out = _copy(doc);
  final n = int.tryParse(value.trim());
  if (n == null) {
    out.remove(key);
  } else {
    out[key] = n < min ? min : (n > max ? max : n);
  }
  return out;
}

class AgentPersonaForm {
  const AgentPersonaForm({this.role = '', this.tone = '', this.language = '', this.boundaries = const []});
  final String role;
  final String tone;
  final String language;
  final List<String> boundaries;

  bool get isEmpty => role.trim().isEmpty && tone.trim().isEmpty && language.trim().isEmpty && boundaries.every((b) => b.trim().isEmpty);

  static AgentPersonaForm read(Map<String, dynamic> doc) {
    final p = _options(doc)['persona'];
    if (p is! Map) return const AgentPersonaForm();
    final b = p['boundaries'];
    return AgentPersonaForm(
      role: _text(p['role']),
      tone: _text(p['tone']),
      language: _text(p['language']),
      boundaries: b is List ? [for (final x in b) _text(x)] : const [],
    );
  }

  /// The same block the server puts before the system prompt (AgentPersona.compose), over the raw (not yet templated) prompt.
  String compose(String systemPrompt) {
    final sb = StringBuffer();
    if (role.trim().isNotEmpty) sb.write('Role: ${role.trim()}\n');
    if (tone.trim().isNotEmpty) sb.write('Tone: ${tone.trim()}\n');
    if (language.trim().isNotEmpty) sb.write('Reply in: ${language.trim()}\n');
    final lines = [for (final b in boundaries) if (b.trim().isNotEmpty) b.trim()];
    if (lines.isNotEmpty) {
      sb.write('Boundaries (never do these):\n');
      for (final l in lines) {
        sb.write('- $l\n');
      }
    }
    if (systemPrompt.trim().isNotEmpty) sb.write('\n$systemPrompt');
    return sb.toString().trimRight();
  }
}

Map<String, dynamic> withPersona(Map<String, dynamic> doc, AgentPersonaForm p) {
  if (p.isEmpty) return _withOption(doc, 'persona', null);
  final m = <String, dynamic>{};
  if (p.role.trim().isNotEmpty) m['role'] = p.role.trim();
  if (p.tone.trim().isNotEmpty) m['tone'] = p.tone.trim();
  if (p.language.trim().isNotEmpty) m['language'] = p.language.trim();
  final lines = [for (final b in p.boundaries) if (b.trim().isNotEmpty) b.trim()];
  if (lines.isNotEmpty) m['boundaries'] = lines;
  return _withOption(doc, 'persona', m);
}

class AgentGuardrailsForm {
  const AgentGuardrailsForm({this.maxOutputChars, this.redact = const [], this.blockedTerms = const []});
  final int? maxOutputChars;
  final List<String> redact;
  final List<String> blockedTerms;

  bool get isEmpty => maxOutputChars == null && redact.isEmpty && blockedTerms.every((t) => t.trim().isEmpty);

  static AgentGuardrailsForm read(Map<String, dynamic> doc) {
    final g = _options(doc)['guardrails'];
    if (g is! Map) return const AgentGuardrailsForm();
    final r = g['redact'];
    final t = g['blockedTerms'];
    final max = g['maxOutputChars'];
    return AgentGuardrailsForm(
      maxOutputChars: max is int ? max : null,
      redact: r is List ? [for (final x in r) if (x is String) x] : const [],
      blockedTerms: t is List ? [for (final x in t) _text(x)] : const [],
    );
  }
}

Map<String, dynamic> withGuardrails(Map<String, dynamic> doc, AgentGuardrailsForm g) {
  if (g.isEmpty) return _withOption(doc, 'guardrails', null);
  final m = <String, dynamic>{};
  if (g.maxOutputChars != null) m['maxOutputChars'] = g.maxOutputChars!.clamp(1, 100000);
  final redact = [for (final k in agentRedactKinds) if (g.redact.contains(k)) k];
  if (redact.isNotEmpty) m['redact'] = redact;
  final terms = [for (final t in g.blockedTerms) if (t.trim().isNotEmpty) t.trim()];
  if (terms.isNotEmpty) m['blockedTerms'] = terms;
  return _withOption(doc, 'guardrails', m);
}

/// One editable tool. [config] is kept as the parsed JSON object so unknown settings survive.
class AgentToolForm {
  const AgentToolForm({this.name = '', this.type = '', this.description = '', this.config = const {}, this.needsApproval = false});
  final String name;
  final String type;
  final String description;
  final Map<String, dynamic> config;
  final bool needsApproval;
}

List<AgentToolForm> readTools(Map<String, dynamic> doc) {
  final t = doc['tools'];
  final approval = doc['requireApproval'];
  final approved = approval is List ? approval.whereType<String>().toSet() : <String>{};
  if (t is! List) return <AgentToolForm>[];
  return <AgentToolForm>[
    for (final x in t)
      if (x is Map)
        AgentToolForm(
          name: _text(x['name']),
          type: _text(x['type']),
          description: _text(x['description']),
          config: x['config'] is Map ? Map<String, dynamic>.from(x['config'] as Map) : const <String, dynamic>{},
          needsApproval: approved.contains(_text(x['name'])),
        ),
  ];
}

/// Writes the tool list back, and keeps `requireApproval` in step: it lists exactly the tools that need approval and
/// no name that is no longer a tool (the server rejects an unknown name).
Map<String, dynamic> withTools(Map<String, dynamic> doc, List<AgentToolForm> tools) {
  final out = _copy(doc);
  if (tools.isEmpty) {
    out.remove('tools');
    out.remove('requireApproval');
    return out;
  }
  out['tools'] = [
    for (final t in tools)
      {
        'name': t.name,
        if (t.type.trim().isNotEmpty) 'type': t.type.trim(),
        if (t.description.trim().isNotEmpty) 'description': t.description,
        if (t.config.isNotEmpty) 'config': t.config,
      },
  ];
  final approval = [for (final t in tools) if (t.needsApproval && t.name.isNotEmpty) t.name];
  if (approval.isEmpty) {
    out.remove('requireApproval');
  } else {
    out['requireApproval'] = approval;
  }
  return out;
}

final _ref = RegExp(r'\$\{(secret|config):[A-Za-z0-9_.-]{1,100}\}');
final _credentialKey = RegExp(r'(.*(secret|password|passwd|apikey|api_key|authorization|credential|cookie).*|.*tokens?)', caseSensitive: false);
final _counterKey = RegExp(r'((max|min|num).*|.*(count|limit|size))', caseSensitive: false);

/// A ready-made retrieval tool: the model supplies only the query; the knowledge base is fixed by the author.
AgentToolForm knowledgeSearchTool(String name, String knowledge) => AgentToolForm(
      name: name,
      type: 'rag-search',
      description: 'Search the "$knowledge" documents and return the most relevant passages with their sources.',
      config: {
        'knowledge': knowledge,
        'retrieval': {'method': 'bm25', 'topK': 5},
        'parameters': {
          'type': 'object',
          'properties': {
            'query': {'type': 'string', 'description': 'What to look for'},
          },
          'required': ['query'],
        },
      },
    );

/// Early hints for the form, mirroring the server's publish checks (AgentToolCheck). The server stays authoritative.
List<String> toolHints(AgentToolForm t) {
  final out = <String>[];
  if (t.name.trim().isEmpty) out.add('A tool needs a name.');
  if (t.type.trim().isEmpty) out.add('Name the connector this tool calls (type).');
  void walk(String path, Object? v, String? key) {
    if (v is Map) {
      if (key == 'parameters') return;
      v.forEach((k, x) => walk('$path.$k', x, '$k'));
    } else if (v is List) {
      for (var i = 0; i < v.length; i++) {
        walk('$path[$i]', v[i], key);
      }
    } else if (v is String) {
      final rest = v.replaceAll(_ref, '');
      if (rest.contains(r'${')) {
        out.add('$path has a malformed reference: use \${secret:NAME} or \${config:NAME}.');
      } else if (key != null && _credentialKey.hasMatch(key) && !_counterKey.hasMatch(key) && v.isNotEmpty) {
        final r = rest.trim().toLowerCase();
        final refOnly = _ref.hasMatch(v) && (r.isEmpty || r == 'bearer' || r == 'basic');
        if (!refOnly) out.add('$path looks like a credential: store it as a secret and use \${secret:NAME}.');
      }
    }
  }

  if (t.type.trim() == 'rag-search' || t.type.trim() == 'rag-ingest') {
    final k = t.config['knowledge'];
    if (k is! String || !RegExp(r'^[A-Za-z][A-Za-z0-9_-]{0,63}$').hasMatch(k)) {
      out.add('Name the knowledge base in config.knowledge (letters, digits, - and _).');
    }
    final p = t.config['parameters'];
    if (t.type.trim() == 'rag-search' && !(p is Map && p['properties'] is Map && (p['properties'] as Map).containsKey('query'))) {
      out.add('A rag-search tool needs a "query" parameter in config.parameters.');
    }
  }
  walk('config', t.config, null);
  return out;
}
