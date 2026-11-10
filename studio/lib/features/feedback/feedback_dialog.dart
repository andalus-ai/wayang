import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme.dart';
import '../../core/widgets.dart';
import '../../data/api_client.dart';
import '../../data/feedback_model.dart';
import '../../providers/app_providers.dart';

/// Opens the feedback form. [error] pre-fills an error report with what the app caught (the person can edit or remove it before sending);
/// [details] adds short named details such as a run id. Nothing leaves the app until the person presses Send, and the form shows exactly
/// what will be sent.
Future<void> showFeedbackDialog(
  BuildContext context, {
  FeedbackKind kind = FeedbackKind.comment,
  CapturedError? error,
  Map<String, String> details = const {},
  String? message,
}) =>
    showDialog<void>(
      context: context,
      builder: (_) => FeedbackDialog(kind: kind, error: error, extra: details, message: message),
    );

class FeedbackDialog extends ConsumerStatefulWidget {
  const FeedbackDialog({super.key, required this.kind, this.error, this.extra = const {}, this.message});

  final FeedbackKind kind;
  final CapturedError? error;
  final Map<String, String> extra;
  final String? message;

  @override
  ConsumerState<FeedbackDialog> createState() => _FeedbackDialogState();
}

class _FeedbackDialogState extends ConsumerState<FeedbackDialog> {
  late FeedbackKind _kind = widget.error != null ? FeedbackKind.error : widget.kind;
  late final TextEditingController _message = TextEditingController(text: widget.message ?? widget.error?.message ?? '');
  late bool _withStack = widget.error != null && widget.error!.stack.isNotEmpty;
  String? _rating;
  bool _busy = false;
  String? _problem;

  @override
  void initState() {
    super.initState();
    _message.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  Map<String, dynamic> _request() => feedbackRequest(
        kind: _kind,
        rating: _rating,
        message: _message.text,
        context: {
          'screen': ref.read(selectedNavKeyProvider),
          'platform': defaultTargetPlatform.name,
          ...widget.extra,
          if (_withStack && widget.error != null) 'stack': widget.error!.stack,
        },
      );

  Future<void> _send() async {
    final problem = feedbackProblem(_kind, rating: _rating, message: _message.text);
    if (problem != null) {
      setState(() => _problem = problem);
      return;
    }
    setState(() {
      _busy = true;
      _problem = null;
    });
    try {
      await ref.read(repositoryProvider).sendFeedback(_request());
      if (!mounted) return;
      if (widget.error != null) ref.read(lastErrorProvider.notifier).state = null;
      Navigator.pop(context);
      showToast(context, 'Thank you: feedback sent');
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _problem = errText(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Send feedback'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Wrap(spacing: 8, children: [
                for (final k in FeedbackKind.values)
                  ChoiceChip(label: Text(k.label), selected: _kind == k, onSelected: _busy ? null : (_) => setState(() => _kind = k)),
              ]),
              if (_kind == FeedbackKind.rating) ...[
                const SizedBox(height: 10),
                Row(children: [
                  for (final r in const ['up', 'down'])
                    IconButton(
                      tooltip: r == 'up' ? 'Helpful' : 'Not helpful',
                      icon: Icon(r == 'up' ? Icons.thumb_up_alt_rounded : Icons.thumb_down_alt_rounded, color: _rating == r ? AppColors.primaryBright : null),
                      onPressed: _busy ? null : () => setState(() => _rating = r),
                    ),
                ]),
              ],
              const SizedBox(height: 10),
              TextField(
                controller: _message,
                minLines: 3,
                maxLines: 8,
                maxLength: feedbackMaxMessage,
                enabled: !_busy,
                decoration: InputDecoration(
                  labelText: _kind == FeedbackKind.error ? 'What happened?' : _kind == FeedbackKind.rating ? 'Anything to add? (optional)' : 'Your feedback',
                  helperText: 'Do not paste passwords or keys. Anything that looks like one is masked, but check first.',
                  helperMaxLines: 2,
                ),
              ),
              if (widget.error != null && widget.error!.stack.isNotEmpty)
                SwitchListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Include the technical details (stack trace)', style: TextStyle(fontSize: 12.5)),
                  value: _withStack,
                  onChanged: _busy ? null : (v) => setState(() => _withStack = v),
                ),
              ExpansionTile(
                tilePadding: EdgeInsets.zero,
                title: const Text('What will be sent', style: TextStyle(fontSize: 12.5)),
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: SelectableText(feedbackPreview(_request()), style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
                  ),
                ],
              ),
              if (_problem != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(_problem!, style: const TextStyle(fontSize: 12, color: AppColors.danger)),
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.pop(context), child: const Text('Cancel')),
        FilledButton(onPressed: _busy ? null : _send, child: Text(_busy ? 'Sending...' : 'Send')),
      ],
    );
  }
}
