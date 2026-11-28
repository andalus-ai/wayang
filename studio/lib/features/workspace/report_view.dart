import 'package:flutter/material.dart';

import '../../data/workspace_model.dart';
import '../../design_system/wds.dart';

/// The outcome of an import or template run: problems, warnings, what still needs setting up, and each item.
class ReportView extends StatelessWidget {
  const ReportView({required this.report});
  final ImportReport report;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    Widget lines(String title, List<String> xs, {Color? color}) => xs.isEmpty
        ? const SizedBox.shrink()
        : Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: TextStyle(fontWeight: FontWeight.w700, color: color)),
              for (final x in xs) Text('• $x', style: const TextStyle(fontSize: 12)),
            ]),
          );
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      WdsBadge(
        label: report.ok
            ? (report.dryRun ? 'Check passed · nothing written' : 'Completed')
            : (report.dryRun ? 'Check failed · nothing written' : 'Stopped'),
        tone: report.ok ? WdsBadgeTone.success : WdsBadgeTone.danger,
        icon: report.ok ? Icons.check_circle_outline_rounded : Icons.error_outline_rounded,
      ),
      lines('Problems', report.problems, color: scheme.error),
      lines('Warnings', report.warnings),
      if (report.needsSetup) ...[
        lines(report.secretsChecked ? 'Secrets missing here' : 'Secrets this needs (could not check)', report.missingSecrets),
        lines('Settings missing here', report.missingConfig),
        lines('Connected accounts to set up', report.oauthConnections),
      ],
      if (report.webhookSecrets.isNotEmpty) ...[
        const SizedBox(height: 8),
        const Text('New webhook secrets (shown once)', style: TextStyle(fontWeight: FontWeight.w700)),
        for (final e in report.webhookSecrets.entries) SelectableText('${e.key}: ${e.value}', style: const TextStyle(fontFamily: 'monospace', fontSize: 11)),
      ],
      if (report.tally != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(report.tally!, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600))),
      const SizedBox(height: 8),
      for (final i in report.items)
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 1),
          child: Text('${i.isError ? '✗' : i.isLeftAlone ? '–' : '✓'} ${i.type} ${i.id}: ${i.action}${i.message.isEmpty ? '' : ' (${i.message})'}',
              style: TextStyle(fontSize: 12, color: i.isError ? scheme.error : (i.isLeftAlone ? scheme.outline : null))),
        ),
    ]);
  }
}
