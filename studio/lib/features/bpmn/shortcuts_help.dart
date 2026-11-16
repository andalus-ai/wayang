import 'package:flutter/material.dart' hide DiagnosticLevel;

/// One line of the cheat sheet: the keys (or gesture) and what they do.
class ShortcutHelp {
  const ShortcutHelp(this.keys, this.does);
  final String keys;
  final String does;
}

/// Everything the BPMN canvas answers to, grouped. Keep this in step with the bindings in `bpmn_canvas.dart`.
const Map<String, List<ShortcutHelp>> bpmnShortcutHelp = {
  'Select': [
    ShortcutHelp('Click', 'Select a node'),
    ShortcutHelp('Shift / Ctrl / Cmd + click', 'Add or remove a node from the selection'),
    ShortcutHelp('Box select button, then drag', 'Select every node the rectangle touches'),
    ShortcutHelp('Ctrl/Cmd + A', 'Select all nodes'),
    ShortcutHelp('Esc', 'Clear the selection'),
    ShortcutHelp('Ctrl/Cmd + F', 'Find a node by name, id or type'),
  ],
  'Edit': [
    ShortcutHelp('Delete / Backspace', 'Delete the selection'),
    ShortcutHelp('Ctrl/Cmd + C / X / V', 'Copy, cut, paste (also between processes)'),
    ShortcutHelp('Ctrl/Cmd + Z', 'Undo'),
    ShortcutHelp('Ctrl + Y or Ctrl/Cmd + Shift + Z', 'Redo'),
    ShortcutHelp('Arrow keys', 'Move the selection one grid cell (Shift: one pixel)'),
    ShortcutHelp('Drag a node', 'Move it; a group moves together; guides and the grid snap'),
    ShortcutHelp('Right-click', 'Context menu: duplicate, align, tidy layout, add element, paste'),
  ],
  'View': [
    ShortcutHelp('Scroll / drag on empty canvas', 'Zoom / pan'),
    ShortcutHelp('Minimap (bottom left)', 'Click or drag to move the view'),
    ShortcutHelp('? or F1', 'This list'),
  ],
};

Future<void> showBpmnShortcutHelp(BuildContext context) => showDialog<void>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Canvas shortcuts'),
        content: SizedBox(
          width: 520,
          height: 420,
          child: ListView(children: [
            for (final group in bpmnShortcutHelp.entries) ...[
              Padding(padding: const EdgeInsets.only(top: 10, bottom: 4), child: Text(group.key.toUpperCase(), style: const TextStyle(fontSize: 11, letterSpacing: 0.6, fontWeight: FontWeight.w700))),
              for (final s in group.value)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    SizedBox(width: 210, child: Text(s.keys, style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
                    Expanded(child: Text(s.does, style: const TextStyle(fontSize: 12.5))),
                  ]),
                ),
            ],
          ]),
        ),
        actions: [Builder(builder: (c) => TextButton(onPressed: () => Navigator.pop(c), child: const Text('Close')))],
      ),
    );
