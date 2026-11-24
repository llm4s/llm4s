---
layout: page
title: Running the JMH Benchmarks
description: "How to run the LLM4S JMH benchmarks for token counting, context compression, tool dispatch and keyword search, and how regression detection works."
search_title: "LLM4S JMH benchmarks: how to run them"
parent: Reference
nav_order: 18
---

# Running the JMH Benchmarks

`modules/benchmarks` holds JMH benchmarks for the library's hot paths. No reference results are published: scores
depend on the hardware, so run them on your own machine and track **relative** changes. For how llm4s compares with
LangChain4j and Spring AI at runtime, see [Comparative Benchmarks](comparative-benchmarks).

## What is measured

| Benchmark class | Hot path | Methods |
|---|---|---|
| `TokenCountingBenchmark` | `ConversationTokenCounter` (`cl100k_base`, with the 4-token-per-message overhead) on 10, 100 and 1,000 messages | `countTokens10Messages`, `countTokens100Messages`, `countTokens1000Messages` |
| `ContextCompressionBenchmark` | `DeterministicCompressor` on 20, 200 and 500 messages, with filler-word removal and repetition deduplication in the last | `compressToolOutput20Messages`, `compressToolOutput200Messages`, `compressWithSubjectiveEdits500Messages` |
| `ToolRegistryBenchmark` | `ToolRegistry` dispatch of one tool and of a batch of 10, sequential and parallel | `dispatchSingleTool`, `dispatchBatch10Sequential`, `dispatchBatch10Parallel` |
| `KeywordIndexBenchmark` | `SQLiteKeywordIndex` (FTS5 / BM25, in memory, 1,000 documents) | `searchSingleTerm`, `searchMultiTerm`, `indexSingleDocument` |

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
Regression detection is currently disabled because
`docs/reference/benchmarks-baseline.json` is empty (`[]`). The workflow skips comparison
until a baseline is committed. With a populated baseline, it fails when a measured
benchmark regresses by more than **10 %** against that baseline.

To update the baseline after an intentional performance change:

```bash
sbt "benchmarks/Jmh/run -rf json -rff docs/reference/benchmarks-baseline.json"
git add docs/reference/benchmarks-baseline.json
git commit -m "chore(benchmarks): update baseline after <description>"
```
