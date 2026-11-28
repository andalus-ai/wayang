import 'package:flutter/material.dart';

import 'components.dart';
import 'tokens.dart';

/// Presentation states commonly used by execution-oriented Studio views.
///
/// These values describe how a state should look; the host maps its own
/// runtime lifecycle into this neutral UI vocabulary.
enum WdsExecutionStatus {
  pending,
  queued,
  running,
  succeeded,
  failed,
  skipped,
  cancelled,
  retrying,
}

/// A compact semantic status indicator for a workflow run or step.
class WdsExecutionStatusIndicator extends StatelessWidget {
  const WdsExecutionStatusIndicator({
    super.key,
    required this.status,
    this.label,
    this.compact = false,
    this.showIcon = true,
  });

  final WdsExecutionStatus status;
  final String? label;
  final bool compact;
  final bool showIcon;

  String get _label => label ?? switch (status) {
    WdsExecutionStatus.pending => 'Pending',
    WdsExecutionStatus.queued => 'Queued',
    WdsExecutionStatus.running => 'Running',
    WdsExecutionStatus.succeeded => 'Succeeded',
    WdsExecutionStatus.failed => 'Failed',
    WdsExecutionStatus.skipped => 'Skipped',
    WdsExecutionStatus.cancelled => 'Cancelled',
    WdsExecutionStatus.retrying => 'Retrying',
  };

  IconData get _icon => switch (status) {
    WdsExecutionStatus.pending => Icons.schedule_rounded,
    WdsExecutionStatus.queued => Icons.hourglass_top_rounded,
    WdsExecutionStatus.running => Icons.play_circle_outline_rounded,
    WdsExecutionStatus.succeeded => Icons.check_circle_outline_rounded,
    WdsExecutionStatus.failed => Icons.error_outline_rounded,
    WdsExecutionStatus.skipped => Icons.skip_next_rounded,
    WdsExecutionStatus.cancelled => Icons.cancel_outlined,
    WdsExecutionStatus.retrying => Icons.refresh_rounded,
  };

  Color _color(ColorScheme colors) => switch (status) {
    WdsExecutionStatus.pending || WdsExecutionStatus.queued ||
    WdsExecutionStatus.skipped || WdsExecutionStatus.cancelled =>
      colors.onSurfaceVariant,
    WdsExecutionStatus.running || WdsExecutionStatus.retrying =>
      colors.primary,
    WdsExecutionStatus.succeeded => colors.tertiary,
    WdsExecutionStatus.failed => colors.error,
  };

  @override
  Widget build(BuildContext context) {
    final color = _color(Theme.of(context).colorScheme);
    return Semantics(
      label: _label,
      child: Container(
        padding: EdgeInsets.symmetric(
          horizontal: compact ? WdsSpacing.sm : WdsSpacing.md,
          vertical: compact ? WdsSpacing.xxs + 1 : WdsSpacing.xs + 1,
        ),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(WdsRadius.pill),
          border: Border.all(color: color.withValues(alpha: 0.25)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (showIcon) ...[
              Icon(_icon, size: compact ? 12 : 14, color: color),
              const SizedBox(width: WdsSpacing.xs),
            ],
            Text(
              _label,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: color,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Immutable display data for one item in an execution timeline.
@immutable
class WdsExecutionStep {
  const WdsExecutionStep({
    required this.id,
    required this.title,
    required this.status,
    this.description,
    this.duration,
    this.attempt,
    this.message,
    this.startedAt,
    this.completedAt,
  }) : assert(attempt == null || attempt > 0);

  final String id;
  final String title;
  final WdsExecutionStatus status;
  final String? description;
  final Duration? duration;
  final int? attempt;
  final String? message;
  final DateTime? startedAt;
  final DateTime? completedAt;
}

/// Immutable event row data. Formatting and event acquisition stay with host.
@immutable
class WdsExecutionEvent {
  const WdsExecutionEvent({
    required this.id,
    required this.timestamp,
    required this.title,
    required this.message,
    this.level = WdsExecutionEventLevel.info,
    this.source,
    this.details,
  });

  final String id;
  final DateTime timestamp;
  final String title;
  final String message;
  final WdsExecutionEventLevel level;
  final String? source;
  final String? details;
}

enum WdsExecutionEventLevel { debug, info, warning, error }

/// Renders a chronological list of execution steps with controlled selection.
class WdsExecutionTimeline extends StatelessWidget {
  const WdsExecutionTimeline({
    super.key,
    required this.steps,
    this.activeStepId,
    this.onStepSelected,
    this.emptyTitle = 'No execution steps',
    this.emptyDescription = 'Steps will appear here when execution data is available.',
  });

  final List<WdsExecutionStep> steps;
  final String? activeStepId;
  final ValueChanged<WdsExecutionStep>? onStepSelected;
  final String emptyTitle;
  final String? emptyDescription;

  @override
  Widget build(BuildContext context) {
    if (steps.isEmpty) {
      return WdsEmptyState(
        icon: Icons.account_tree_outlined,
        title: emptyTitle,
        description: emptyDescription,
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (var index = 0; index < steps.length; index++)
          _WdsExecutionTimelineRow(
            step: steps[index],
            isLast: index == steps.length - 1,
            selected: steps[index].id == activeStepId,
            onTap: onStepSelected == null
                ? null
                : () => onStepSelected!(steps[index]),
          ),
      ],
    );
  }
}

class _WdsExecutionTimelineRow extends StatelessWidget {
  const _WdsExecutionTimelineRow({
    required this.step,
    required this.isLast,
    required this.selected,
    required this.onTap,
  });

  final WdsExecutionStep step;
  final bool isLast;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = switch (step.status) {
      WdsExecutionStatus.succeeded => theme.colorScheme.tertiary,
      WdsExecutionStatus.failed => theme.colorScheme.error,
      WdsExecutionStatus.running || WdsExecutionStatus.retrying =>
        theme.colorScheme.primary,
      _ => theme.colorScheme.outline,
    };
    final content = Container(
      margin: const EdgeInsets.only(bottom: WdsSpacing.sm),
      padding: const EdgeInsets.all(WdsSpacing.md),
      decoration: BoxDecoration(
        color: selected
            ? theme.colorScheme.primaryContainer.withValues(alpha: 0.35)
            : Colors.transparent,
        borderRadius: BorderRadius.circular(WdsRadius.md),
        border: Border.all(
          color: selected
              ? theme.colorScheme.primary.withValues(alpha: 0.55)
              : theme.colorScheme.outlineVariant.withValues(alpha: 0.45),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 24,
            child: Column(
              children: [
                Container(
                  width: 12,
                  height: 12,
                  decoration: BoxDecoration(
                    color: color.withValues(alpha: 0.14),
                    shape: BoxShape.circle,
                    border: Border.all(color: color, width: 2),
                  ),
                ),
                if (!isLast)
                  Container(
                    width: 2,
                    height: 42,
                    margin: const EdgeInsets.only(top: WdsSpacing.xs),
                    color: theme.colorScheme.outlineVariant,
                  ),
              ],
            ),
          ),
          const SizedBox(width: WdsSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        step.title,
                        style: theme.textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                    const SizedBox(width: WdsSpacing.sm),
                    WdsExecutionStatusIndicator(
                      status: step.status,
                      compact: true,
                    ),
                  ],
                ),
                if (step.description != null) ...[
                  const SizedBox(height: WdsSpacing.xs),
                  Text(step.description!, style: theme.textTheme.bodySmall),
                ],
                if (step.message != null) ...[
                  const SizedBox(height: WdsSpacing.xs),
                  Text(
                    step.message!,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
                if (step.duration != null || step.attempt != null) ...[
                  const SizedBox(height: WdsSpacing.sm),
                  Wrap(
                    spacing: WdsSpacing.md,
                    runSpacing: WdsSpacing.xs,
                    children: [
                      if (step.duration != null)
                        _WdsExecutionMetadata(
                          icon: Icons.timer_outlined,
                          label: formatWdsDuration(step.duration!),
                        ),
                      if (step.attempt != null)
                        _WdsExecutionMetadata(
                          icon: Icons.repeat_rounded,
                          label: 'Attempt ${step.attempt}',
                        ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
    if (onTap == null) return content;
    return Semantics(
      button: true,
      selected: selected,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(WdsRadius.md),
        child: content,
      ),
    );
  }
}

class _WdsExecutionMetadata extends StatelessWidget {
  const _WdsExecutionMetadata({required this.icon, required this.label});
  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(icon, size: 13, color: Theme.of(context).colorScheme.onSurfaceVariant),
      const SizedBox(width: WdsSpacing.xs),
      Text(label, style: Theme.of(context).textTheme.labelSmall),
    ],
  );
}

/// A themed event list with optional selection and expandable detail text.
class WdsExecutionEventList extends StatelessWidget {
  const WdsExecutionEventList({
    super.key,
    required this.events,
    this.onEventSelected,
    this.emptyTitle = 'No events yet',
    this.emptyDescription = 'Execution events will appear here.',
  });

  final List<WdsExecutionEvent> events;
  final ValueChanged<WdsExecutionEvent>? onEventSelected;
  final String emptyTitle;
  final String? emptyDescription;

  @override
  Widget build(BuildContext context) {
    if (events.isEmpty) {
      return WdsEmptyState(
        icon: Icons.receipt_long_outlined,
        title: emptyTitle,
        description: emptyDescription,
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final event in events)
          _WdsExecutionEventTile(
            event: event,
            onTap: onEventSelected == null
                ? null
                : () => onEventSelected!(event),
          ),
      ],
    );
  }
}

class _WdsExecutionEventTile extends StatelessWidget {
  const _WdsExecutionEventTile({required this.event, required this.onTap});
  final WdsExecutionEvent event;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final color = switch (event.level) {
      WdsExecutionEventLevel.debug => theme.colorScheme.onSurfaceVariant,
      WdsExecutionEventLevel.info => theme.colorScheme.primary,
      WdsExecutionEventLevel.warning => theme.colorScheme.tertiary,
      WdsExecutionEventLevel.error => theme.colorScheme.error,
    };
    final icon = switch (event.level) {
      WdsExecutionEventLevel.debug => Icons.bug_report_outlined,
      WdsExecutionEventLevel.info => Icons.info_outline_rounded,
      WdsExecutionEventLevel.warning => Icons.warning_amber_rounded,
      WdsExecutionEventLevel.error => Icons.error_outline_rounded,
    };
    final tile = Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: WdsSpacing.md,
        vertical: WdsSpacing.sm,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 17, color: color),
          const SizedBox(width: WdsSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  crossAxisAlignment: WrapCrossAlignment.center,
                  spacing: WdsSpacing.sm,
                  runSpacing: WdsSpacing.xs,
                  children: [
                    Text(
                      event.title,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    WdsBadge(
                      label: event.level.name.toUpperCase(),
                      dense: true,
                      tone: switch (event.level) {
                        WdsExecutionEventLevel.debug => WdsBadgeTone.neutral,
                        WdsExecutionEventLevel.info => WdsBadgeTone.info,
                        WdsExecutionEventLevel.warning => WdsBadgeTone.warning,
                        WdsExecutionEventLevel.error => WdsBadgeTone.danger,
                      },
                    ),
                  ],
                ),
                const SizedBox(height: WdsSpacing.xxs),
                Text(event.message, style: theme.textTheme.bodySmall),
                if (event.source != null || event.details != null) ...[
                  const SizedBox(height: WdsSpacing.xs),
                  if (event.source != null)
                    Text(
                      event.source!,
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                  if (event.details != null)
                    Text(
                      event.details!,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ],
            ),
          ),
          const SizedBox(width: WdsSpacing.sm),
          Text(
            formatWdsTimestamp(event.timestamp),
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
    if (onTap == null) return tile;
    return InkWell(onTap: onTap, child: tile);
  }
}

/// Formats a duration without requiring a locale or intl dependency.
String formatWdsDuration(Duration duration) {
  final milliseconds = duration.inMilliseconds.abs();
  if (milliseconds < 1000) return '${milliseconds}ms';
  if (milliseconds < 60000) {
    final seconds = milliseconds / 1000;
    return '${seconds.toStringAsFixed(seconds == seconds.roundToDouble() ? 0 : 1)}s';
  }
  final minutes = milliseconds ~/ 60000;
  final seconds = (milliseconds % 60000) ~/ 1000;
  return seconds == 0 ? '${minutes}m' : '${minutes}m ${seconds}s';
}

/// Formats a local timestamp as HH:mm:ss for compact event rows.
String formatWdsTimestamp(DateTime timestamp) {
  String two(int value) => value.toString().padLeft(2, '0');
  return '${two(timestamp.hour)}:${two(timestamp.minute)}:${two(timestamp.second)}';
}
