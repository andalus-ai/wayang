import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'data/feedback_model.dart';
import 'providers/app_providers.dart';

void main() {
  final container = ProviderContainer();
  // Remember the last error the app caught so the person can report it with one click. Nothing is sent from here: the report only goes
  // out when they press Send in the feedback form.
  final previous = FlutterError.onError;
  FlutterError.onError = (details) {
    container.read(lastErrorProvider.notifier).state = CapturedError.of(details.exception, details.stack);
    (previous ?? FlutterError.presentError)(details);
  };
  PlatformDispatcher.instance.onError = (error, stack) {
    container.read(lastErrorProvider.notifier).state = CapturedError.of(error, stack);
    return false;
  };
  runApp(UncontrolledProviderScope(container: container, child: const WayangStudioApp()));
}
