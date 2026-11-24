# Query understanding evaluation

**Status: deterministic golden-set test harness implemented.** This is a contract-level regression test, not a benchmark of any hosted or local model provider.

## What is measured

`QueryUnderstandingEvaluationTest` evaluates the output of `ModelQueryMentionExtractor` against a small, version-controlled set of annotated query spans. A mention is a true positive only when entity type, surface text and exact start/end offsets match. The test reports:

- Precision = TP / (TP + FP)
- Recall = TP / (TP + FN)
- F1 = harmonic mean of precision and recall

The fixtures include multi-entity queries, missed mentions and an extra predicted mention. Invalid offsets and surface text that do not match the original query are rejected by the extractor before they can become candidates.

## Run

From the repository root:

```bash
mvn -pl wayang-rag -am -Dtest=QueryUnderstandingEvaluationTest,ModelQueryMentionExtractorTest,QueryEntityLinkerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The exact module test command may be adjusted to the active Maven reactor and dependency availability. The test itself requires no network, model, API key or external service.

## Interpretation and limits

- The fixture model is deterministic and synthetic. Its score verifies metric calculation and regression behavior only; it does not establish model accuracy.
- Production provider evaluations should use a separately versioned, representative, consented dataset and the actual provider adapter.
- Keep tenant/collection scope, ambiguous alias behavior, candidate caps and document-only fallback in the evaluation suite.
- Evaluate confidence thresholds against precision/recall trade-offs rather than assuming a model's confidence is calibrated.
- Never include private customer prompts or source passages in committed fixtures.
- Model adapter timeouts, retries, concurrency and provider costs remain application-level responsibilities.

Expand the fixture corpus as domain coverage grows. Any benchmark report should record the dataset revision, provider/model identifier, adapter version, thresholds, evaluation date and aggregate metrics.
