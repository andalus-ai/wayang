import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The output of the last successful "Test node" call per node id (this session only). The mapping editor uses it as the `result` of its
/// sample, so an output mapping can be built against real data instead of typed-in data.
final lastNodeTestOutputProvider = StateProvider.family<Object?, String>((ref, nodeId) => null);
