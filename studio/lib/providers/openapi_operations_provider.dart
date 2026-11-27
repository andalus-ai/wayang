import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/openapi_model.dart';

/// The operations last loaded for an `openapi` node (per node id, this session only), so the mapping editor can offer the chosen operation's
/// arguments as the rows of an input mapping.
final openApiOperationsProvider = StateProvider.family<List<OpenApiOperation>?, String>((ref, nodeId) => null);
