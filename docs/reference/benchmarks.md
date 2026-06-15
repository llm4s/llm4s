# Performance Benchmarks

Baseline JMH benchmark results for llm4s critical hot paths.
Run with: `sbt "benchmarks/Jmh/run -rf json -rff results.json"`

> Results below are from a reference run on a MacBook Pro M3, JDK 21, Scala 3.
> Your numbers will differ by hardware; track **relative** changes for regression detection.

## Token Counting — `ConversationTokenCounter`

| Benchmark | Messages | Score (µs/op) | Error (±µs) |
|-----------|----------|--------------|-------------|
| `countTokens10Messages` | 10 | TBD | TBD |
| `countTokens100Messages` | 100 | TBD | TBD |
| `countTokens1000Messages` | 1 000 | TBD | TBD |

Tokenizer: `cl100k_base` (OpenAI). Counts include 4-token-per-message overhead.

## Context Compression — `DeterministicCompressor`

| Benchmark | Messages | Cap (tokens) | Score (ms/op) | Error (±ms) |
|-----------|----------|-------------|--------------|-------------|
| `compressToolOutput20Messages` | 20 | 500 | TBD | TBD |
| `compressToolOutput200Messages` | 200 | 2 000 | TBD | TBD |
| `compressWithSubjectiveEdits500Messages` | 500 | 4 000 | TBD | TBD |

Subjective edits include filler-word removal and repetition deduplication.

## Tool Dispatch — `ToolRegistry`

| Benchmark | Tools | Strategy | Score (µs/op) | Error (±µs) |
|-----------|-------|----------|--------------|-------------|
| `dispatchSingleTool` | 1 | — | TBD | TBD |
| `dispatchBatch10Sequential` | 10 | Sequential | TBD | TBD |
| `dispatchBatch10Parallel` | 10 | Parallel | TBD | TBD |

## Keyword Search — `SQLiteKeywordIndex` (FTS5 / BM25, in-memory, 1 000 docs)

| Benchmark | Query | topK | Score (µs/op) | Error (±µs) |
|-----------|-------|------|--------------|-------------|
| `searchSingleTerm` | `"scala"` | 10 | TBD | TBD |
| `searchMultiTerm` | `"scala programming language"` | 10 | TBD | TBD |
| `indexSingleDocument` | — | — | TBD | TBD |

## Running Benchmarks Locally

```bash
# Smoke test (fast — runs each method once)
sbt "benchmarks/test"

# Full JMH run (slow — minutes per benchmark)
sbt "benchmarks/Jmh/run"

# Run a specific benchmark
sbt "benchmarks/Jmh/run .*TokenCounting.*"

# Output JSON for comparison
sbt "benchmarks/Jmh/run -rf json -rff target/jmh-results.json"
```

JMH options cheatsheet:

| Flag | Meaning |
|------|---------|
| `-f 1` | 1 JVM fork |
| `-wi 3 -w 1s` | 3 warm-up iterations × 1 s each |
| `-i 5 -r 1s` | 5 measurement iterations × 1 s each |
| `-bm thrpt` | Throughput mode (ops/s) |
| `-bm avgt` | Average time mode (µs/op) |

## Regression Detection

The CI benchmark job (`benchmarks.yml`) runs on pushes to `main` and release tags.
It fails when any benchmark regresses by more than **10 %** vs the stored baseline in
`docs/reference/benchmarks-baseline.json`.

To update the baseline after an intentional performance change:

```bash
sbt "benchmarks/Jmh/run -rf json -rff docs/reference/benchmarks-baseline.json"
git add docs/reference/benchmarks-baseline.json
git commit -m "chore(benchmarks): update baseline after <description>"
```
