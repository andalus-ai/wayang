# Studio <-> API contract fixtures

These JSON documents are exactly what the Flutter Studio's mappers (`studio/lib/data/*_mapper.dart`) emit and
accept. They are checked from both sides:

* **Java** (`ContractFixturesTest`, runs in CI/locally): the server validates, stores, publishes and *executes* them.
* **Dart** (`studio/test/mapper_test.dart`): builds the same diagram/table in code and expects the mapper to produce
  the fixture. **Not run in the environment that produced this change** (no Dart SDK there).

If you change a fixture, change the mapper and both tests.
