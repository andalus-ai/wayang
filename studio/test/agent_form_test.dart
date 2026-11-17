import 'package:flutter_test/flutter_test.dart';
import 'package:wayang_studio/data/agent_form.dart';

void main() {
  test('persona round-trips, drops empty fields, and removes itself and empty options when blank', () {
    var doc = withPersona({'id': 'a', 'model': 'm'}, const AgentPersonaForm(role: ' Support ', boundaries: ['no refunds', ' ']));
    expect(doc['options'], {
      'persona': {'role': 'Support', 'boundaries': ['no refunds']},
    });
    final p = AgentPersonaForm.read(doc);
    expect(p.role, 'Support');
    expect(p.boundaries, ['no refunds']);
    doc = withPersona(doc, const AgentPersonaForm());
    expect(doc.containsKey('options'), false);
    expect(doc['id'], 'a');
  });

  test('other options such as memory survive persona and guardrail edits', () {
    final base = <String, dynamic>{
      'id': 'a',
      'options': {'memory': {'key': 'k', 'turns': 3}},
    };
    var doc = withPersona(base, const AgentPersonaForm(tone: 'calm'));
    doc = withGuardrails(doc, const AgentGuardrailsForm(redact: ['phone', 'email', 'bogus']));
    expect((doc['options'] as Map)['memory'], {'key': 'k', 'turns': 3});
    expect((doc['options'] as Map)['guardrails'], {'redact': ['email', 'phone']});
    doc = withGuardrails(doc, const AgentGuardrailsForm());
    expect((doc['options'] as Map).containsKey('guardrails'), false);
  });

  test('preview composes exactly like the server block', () {
    const p = AgentPersonaForm(role: 'Concierge', tone: 'calm', language: 'Indonesian', boundaries: ['no refunds']);
    expect(p.compose('Help with billing.'),
        'Role: Concierge\nTone: calm\nReply in: Indonesian\nBoundaries (never do these):\n- no refunds\n\nHelp with billing.');
    expect(const AgentPersonaForm(role: 'x').compose(''), 'Role: x');
  });

  test('guardrails clamp the length cap and keep blocked terms trimmed', () {
    final doc = withGuardrails({}, const AgentGuardrailsForm(maxOutputChars: 999999, blockedTerms: [' secret plan ', '']));
    expect((doc['options'] as Map)['guardrails'], {'maxOutputChars': 100000, 'blockedTerms': ['secret plan']});
    expect(AgentGuardrailsForm.read(doc).maxOutputChars, 100000);
  });

  test('text and int fields remove their key when blank and clamp numbers', () {
    var doc = withText({'id': 'a'}, 'systemPrompt', 'Hi');
    expect(doc['systemPrompt'], 'Hi');
    expect(withText(doc, 'systemPrompt', '  ').containsKey('systemPrompt'), false);
    expect(withInt(doc, 'maxSteps', '500', 1, 50)['maxSteps'], 50);
    expect(withInt(doc, 'maxSteps', '0', 1, 50)['maxSteps'], 1);
    expect(withInt({'maxSteps': 5}, 'maxSteps', 'abc', 1, 50).containsKey('maxSteps'), false);
  });

  test('tools keep requireApproval in step and keep unknown config', () {
    final doc = withTools({'id': 'a', 'requireApproval': ['gone']}, const [
      AgentToolForm(name: 'lookup', type: 'http', config: {'url': 'https://x.test', 'extra': 1}),
      AgentToolForm(name: 'refund', type: 'http', description: 'Refund', needsApproval: true),
    ]);
    expect(doc['requireApproval'], ['refund']);
    final tools = readTools(doc);
    expect(tools.length, 2);
    expect(tools[0].config['extra'], 1);
    expect(tools[1].needsApproval, true);
    expect(tools[0].needsApproval, false);
    final none = withTools(doc, const []);
    expect(none.containsKey('tools'), false);
    expect(none.containsKey('requireApproval'), false);
  });

  test('tool hints mirror the publish checks and never repeat a value', () {
    const ok = AgentToolForm(name: 't', type: 'http', config: {
      'headers': {'Authorization': 'Bearer \${secret:KEY}'},
      'maxTokens': 5,
      'parameters': {'properties': {'password': {'type': 'string'}}},
    });
    expect(toolHints(ok), isEmpty);
    final bad = toolHints(const AgentToolForm(name: '', type: ' ', config: {'apiKey': 'sk-live-1', 'url': 'https://x/\${env:HOME}'}));
    expect(bad.length, 4);
    expect(bad.any((h) => h.contains('sk-live')), false);
    expect(bad.any((h) => h.contains('config.apiKey')), true);
  });
}
