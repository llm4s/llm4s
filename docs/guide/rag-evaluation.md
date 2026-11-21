---
layout: page
title: RAG Evaluation
description: "Measure the quality of a RAG pipeline in Scala with RAGAS metrics (faithfulness, relevancy, context precision and recall) and the LLM4S benchmark harness."
search_title: "Evaluate RAG pipelines in Scala with RAGAS metrics"
parent: User Guide
nav_order: 18
---

# RAG Evaluation & Benchmarking
{: .no_toc }

Systematically measure and improve your RAG pipeline quality using RAGAS metrics and the benchmarking harness.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Building a RAG (Retrieval-Augmented Generation) system is just the beginning. The real challenge is knowing whether your retrieval is actually helping your LLM produce better answers. LLM4S provides two complementary tools:

1. **RAGAS Evaluator** - Measure retrieval and generation quality with industry-standard metrics
2. **Benchmarking Harness** - Systematically compare different RAG configurations

**Key Benefits:**
- Data-driven optimization of chunking strategies
- Objective comparison of fusion methods (RRF vs weighted vs vector-only)
- Embedding provider evaluation
- Reproducible experiments with JSON reports

---

## RAGAS Metrics Explained

[RAGAS](https://docs.ragas.io/) (Retrieval Augmented Generation Assessment) provides four core metrics:

### Faithfulness (0.0 - 1.0)

Measures whether the generated answer is grounded in the retrieved context.

- **1.0**: Answer fully supported by context - no hallucination
- **0.0**: Answer contains claims not in the retrieved documents

```
Question: "What year was Scala released?"
Context: "Scala was first released in 2004."
Answer: "Scala was released in 2004."
Faithfulness: 1.0 (fully grounded)
```

### Answer Relevancy (0.0 - 1.0)

Measures how relevant the answer is to the question asked.

- **1.0**: Answer directly addresses the question
- **0.0**: Answer is completely off-topic

```
Question: "What is Scala?"
Answer: "Scala is a programming language."
Relevancy: ~0.85 (relevant but could be more complete)
```

### Context Precision (0.0 - 1.0)

Measures whether the retrieved chunks are relevant to answering the question.

- **1.0**: All retrieved chunks contribute to the answer
- **0.0**: None of the retrieved chunks are relevant

*High precision = no wasted context tokens*

### Context Recall (0.0 - 1.0)

Measures whether all information needed to answer was retrieved.

- **1.0**: All necessary information was retrieved
- **0.0**: Critical information was missed

*High recall = comprehensive retrieval*

### Overall RAGAS Score

The mean of the metrics that could be computed for a sample, providing a single quality score. This is what the benchmarking harness uses for ranking.

A metric is computed only when the sample has the inputs it needs: Context Precision and Context Recall need a `groundTruth`, so a sample without one is scored on Faithfulness and Answer Relevancy alone.

---

## Quick Start: Evaluate Your RAG

### Basic Evaluation

The evaluator needs a chat client (the judge for every metric) and an embedding client (for
Answer Relevancy). `RAGASFactory.fromConfigs` builds both from the default chat provider and the
configured embeddings:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.evaluation._
import org.llm4s.types.Result

// Create an evaluation sample
val sample = EvalSample(
  question = "What is Scala?",
  answer = "Scala is a programming language that runs on the JVM.",
  contexts = Seq("Scala is a functional and object-oriented programming language."),
  groundTruth = Some("Scala is a programming language.")
)

// Build the evaluator and evaluate
val result: Result[EvalResult] = for {
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  providerCfg <- Llm4sConfig.defaultProvider()
  embedding   <- Llm4sConfig.embeddings()
  evaluator   <- RAGASFactory.fromConfigs(providerCfg, embedding)
  evalResult  <- evaluator.evaluate(sample)
} yield evalResult

result.foreach { evalResult =>
  evalResult.getMetric("faithfulness").foreach(m => println(s"Faithfulness: ${m.score}"))
  evalResult.getMetric("answer_relevancy").foreach(m => println(s"Answer Relevancy: ${m.score}"))
  evalResult.getMetric("context_precision").foreach(m => println(s"Context Precision: ${m.score}"))
  evalResult.getMetric("context_recall").foreach(m => println(s"Context Recall: ${m.score}"))
  println(s"Overall RAGAS: ${evalResult.ragasScore}")
}
```

`getMetric` returns `None` for a metric that was not computed (see above). To list every computed
metric, iterate `evalResult.metrics`, whose elements carry `metricName` and `score`.

If you already have the clients, `RAGASFactory.create(llmClient, embeddingClient, embeddingModelConfig)`
(or `RAGASEvaluator(...)`) builds the evaluator directly, as
`modules/samples/src/main/scala/org/llm4s/samples/rag/RAGASEvaluationExample.scala` does.

### Batch Evaluation

`evaluateBatch` returns an `EvalSummary`: the per-sample results, the average of each metric, and
the average RAGAS score.

```scala
import org.llm4s.rag.evaluation._

def printSummary(evaluator: RAGASEvaluator, samples: Seq[EvalSample]): Unit =
  evaluator.evaluateBatch(samples).foreach { summary =>
    println(s"Samples evaluated: ${summary.sampleCount}")
    summary.averages.foreach { case (metric, avg) => println(s"  $metric: $avg") }
    println(s"Average RAGAS Score: ${summary.overallRagasScore}")
  }
```

A sample on which every metric fails is left out of the summary; the batch fails only when every
sample fails.

---

## Benchmarking Harness

The benchmarking harness lets you systematically compare different RAG configurations to find what works best for your data.

### Running Pre-Built Benchmarks

```bash
# Download test dataset
./scripts/download-datasets.sh ragbench

# Configure providers (see the note below), then:
export OPENAI_API_KEY=sk-...
export LLM4S_PROVIDER=openai-main                     # bound by the samples' application.conf
export EMBEDDING_MODEL=openai/text-embedding-3-small  # bound by llm4s-core's reference.conf

# Run fusion strategy comparison
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite fusion"

# Run chunking strategy comparison
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite chunking"

# Run embedding provider comparison
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite embedding"

# Quick test (5 samples)
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --quick"
```

The samples read `modules/samples/src/main/resources/application.conf`, whose only provider
section is `ollama-local`, so add an OpenAI chat section to
`modules/samples/src/main/resources/application.local.conf` (not committed; included by
`application.conf`). Neither it nor OpenAI embeddings need an `apiKey` line: both use
`OPENAI_API_KEY`, which `llm4s-openai` binds to OpenAI's shared key.

```hocon
llm4s {
  providers {
    openai-main {
      provider = "openai"
      model    = "gpt-4o"
    }
  }
}
```

See [Running the samples](../getting-started/configuration#running-the-samples).

### Understanding the Output

```
======================================================================
BENCHMARK RESULTS: fusion-comparison
Compare different search fusion strategies
======================================================================

Duration: 327.83s
Experiments: 6 (6 passed, 0 failed)

RANKINGS BY RAGAS SCORE
----------------------------------------------------------------------
Rank   Config           RAGAS      Faith      Relevancy
----------------------------------------------------------------------
1      rrf-20           0.911      1.000      0.844
2      rrf-60           0.894      1.000      0.841
3      weighted-70v     0.893      1.000      0.841
4      weighted-50v     0.892      1.000      0.836
5      vector-only      0.884      0.960      0.845
6      keyword-only     0.809      N/A        0.809

======================================================================
WINNER: rrf-20 (RAGAS: 0.911)
======================================================================
```

---

## What Each Benchmark Tests

### Fusion Strategy Benchmark

Compares how to combine vector (semantic) and keyword (BM25) search results.

| Strategy | Description | When to Use |
|----------|-------------|-------------|
| `rrf-20` | Reciprocal Rank Fusion, k=20 | Best overall performance |
| `rrf-60` | Reciprocal Rank Fusion, k=60 | More conservative ranking |
| `weighted-70v` | 70% vector + 30% keyword | Semantic-focused |
| `weighted-50v` | 50% vector + 50% keyword | Balanced |
| `vector-only` | Pure embedding similarity | Conceptual queries |
| `keyword-only` | Pure BM25 | Exact term matching |

**Typical Finding:** Hybrid search (RRF) outperforms single-method search by 3-5%.

### Chunking Strategy Benchmark

Compares different document splitting approaches.

| Strategy | Target Size | Best For |
|----------|-------------|----------|
| `sentence-small` | 400 chars | Focused factual queries |
| `sentence-default` | 800 chars | General use |
| `sentence-large` | 1200 chars | Complex reasoning |
| `markdown-default` | 800 chars | Structured documents |
| `simple-default` | 800 chars | Baseline comparison |

**Typical Finding:** Sentence-aware chunking with smaller chunks improves precision.

### Embedding Provider Benchmark

Compares embedding quality across providers.

| Provider | Model | Dimensions | Notes |
|----------|-------|------------|-------|
| OpenAI | text-embedding-3-small | 1536 | Good balance of cost/quality |
| OpenAI | text-embedding-3-large | 3072 | Highest quality |
| Voyage | voyage-3 | 1024 | Optimized for retrieval |
| Ollama | nomic-embed-text | 768 | Free, local |

---

## Programmatic Benchmarking

### Custom Benchmark Suite

`BenchmarkRunner` takes a chat client, an embedding client, and `resolveEmbeddingProvider`, which
returns the embedding configuration for a provider an experiment names (each experiment's
`embeddingConfig`; the default is OpenAI). It also needs a `ModelRegistryService` in scope.

```scala
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.benchmark._
import org.llm4s.types.Result
import org.llm4s.vectorstore.FusionStrategy

// Initialize the runner
val runner: Result[BenchmarkRunner] = for {
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  providerCfg <- Llm4sConfig.defaultProvider()
  llm         <- LLMConnect.getClient(providerCfg)
  embedding   <- Llm4sConfig.embeddings()
  (embeddingProvider, embeddingCfg) = embedding
  embed <- EmbeddingClient.from(embeddingProvider, embeddingCfg)
  resolveEmbedding = (name: String) =>
    if (name.equalsIgnoreCase(embeddingProvider)) Right(embeddingCfg)
    else Left(ConfigurationError(s"No embedding configuration for provider '$name'"))
} yield BenchmarkRunner(llm, embed, resolveEmbedding)

// Define custom experiments
val experiments = Seq(
  RAGExperimentConfig(
    name = "my-config-1",
    chunkingStrategy = ChunkerFactory.Strategy.Sentence,
    fusionStrategy = FusionStrategy.RRF(30),
    topK = 5
  ),
  RAGExperimentConfig(
    name = "my-config-2",
    chunkingStrategy = ChunkerFactory.Strategy.Markdown,
    fusionStrategy = FusionStrategy.WeightedScore(0.8, 0.2),
    topK = 10
  )
)

// Create suite
val suite = BenchmarkSuite(
  name = "my-comparison",
  description = "Custom experiment comparison",
  experiments = experiments,
  datasetPath = "path/to/dataset.jsonl"
)

// Run and report: runSuite returns a Result, so report on its value
val results: Result[BenchmarkResults] = runner.flatMap(_.runSuite(suite))
results.foreach(r => println(BenchmarkReport.console(r)))
val saved: Result[Unit] = results.flatMap(r => BenchmarkReport.save(r, "data/results/my-comparison.json"))
```

### Custom Dataset Format

Create a JSONL file with your own test data:

```jsonl
{"question": "What is Scala?", "documents": ["Scala is a programming language..."], "answer": "Scala is a JVM language."}
{"question": "How does pattern matching work?", "documents": ["Pattern matching in Scala..."], "answer": "Pattern matching uses case expressions."}
```

---

## Improving Your RAG with Benchmarks

### Optimization Workflow

```
1. BASELINE
   Run benchmark with default settings
   Record baseline RAGAS score

2. HYPOTHESIS
   "Smaller chunks might improve precision"

3. EXPERIMENT
   Run chunking benchmark
   Compare sentence-small vs sentence-default

4. ANALYZE
   Did RAGAS score improve?
   Which sub-metrics changed?

5. ITERATE
   Apply winning config
   Test new hypothesis
```

### Common Optimization Patterns

#### Low Faithfulness Score

**Problem:** LLM generating claims not in context.

**Solutions:**
- Increase `topK` to retrieve more context
- Switch to larger chunk sizes
- Add reranking to improve context quality
- Use more explicit RAG prompts

#### Low Context Precision

**Problem:** Retrieving irrelevant chunks.

**Solutions:**
- Try smaller chunk sizes for tighter matches
- Use hybrid search (RRF) to combine semantic + keyword
- Add metadata filtering
- Tune embedding model

#### Low Context Recall

**Problem:** Missing relevant information.

**Solutions:**
- Increase `topK` retrieval count
- Use larger chunk overlap
- Add query expansion
- Try different embedding models

#### Low Answer Relevancy

**Problem:** Answers don't address the question directly.

**Solutions:**
- Improve RAG prompt template
- Use query rewriting
- Check if context contains the answer

---

## Interpreting Results

### What's a Good RAGAS Score?

| Score Range | Interpretation |
|-------------|----------------|
| 0.90+ | Excellent - production ready |
| 0.80-0.90 | Good - suitable for most use cases |
| 0.70-0.80 | Acceptable - room for improvement |
| 0.60-0.70 | Fair - needs optimization |
| <0.60 | Poor - significant issues |

### Comparing Configurations

When comparing configurations:

1. **Statistical Significance**: Small differences (<0.02) may be noise
2. **Trade-offs**: Higher precision often means lower recall
3. **Domain Specificity**: Best config varies by data type
4. **Cost Considerations**: More retrieval = more embedding calls

---

## Benchmark Results Reference

Based on actual benchmarks with RAGBench dataset:

### Fusion Strategy Results

| Config | RAGAS | Faithfulness | Relevancy |
|--------|-------|--------------|-----------|
| rrf-20 | 0.911 | 1.000 | 0.844 |
| rrf-60 | 0.894 | 1.000 | 0.841 |
| weighted-70v | 0.893 | 1.000 | 0.841 |
| weighted-50v | 0.892 | 1.000 | 0.836 |
| vector-only | 0.884 | 0.960 | 0.845 |
| keyword-only | 0.809 | N/A | 0.809 |

**Key Insight:** Hybrid search (RRF) outperforms pure vector or keyword search.

### Chunking Strategy Results

| Config | RAGAS | Faithfulness | Relevancy |
|--------|-------|--------------|-----------|
| sentence-small | 0.910 | 1.000 | 0.840 |
| sentence-large | 0.893 | 1.000 | 0.838 |
| sentence-default | 0.892 | 1.000 | 0.834 |
| markdown-default | 0.885 | 0.960 | 0.847 |
| simple-default | 0.882 | 0.960 | 0.833 |

**Key Insight:** Smaller, sentence-aware chunks perform best for focused queries.

---

## Best Practices

### 1. Start with Baselines

Always run benchmarks with default settings first to establish a baseline.

### 2. Change One Variable at a Time

When optimizing, change only one configuration at a time to understand impact.

### 3. Use Representative Data

Your benchmark dataset should reflect real-world queries your system will handle.

### 4. Consider the Full Picture

Don't optimize for one metric at the expense of others. Balance is key.

### 5. Monitor in Production

Benchmark scores are offline metrics. Monitor real user satisfaction in production.

### 6. Re-benchmark After Changes

When you update your document corpus, re-run benchmarks to verify quality.

---

## API Reference

The signatures below are copied from `modules/rag/src/main/scala/org/llm4s/rag/`; Scaladoc and
method bodies are omitted.

### RAGASEvaluator

```scala
class RAGASEvaluator(
  llmClient: LLMClient,
  embeddingClient: EmbeddingClient,
  embeddingModelConfig: EmbeddingModelConfig,
  metrics: Seq[RAGASMetric] = Seq.empty, // empty: faithfulness, answer relevancy, context precision and recall
  options: EvaluatorOptions = EvaluatorOptions(),
  private val tracer: Option[Tracing] = None
) {
  def getActiveMetrics: Seq[RAGASMetric]
  def evaluate(sample: EvalSample): Result[EvalResult]
  def evaluateBatch(samples: Seq[EvalSample]): Result[EvalSummary]
  def evaluateDataset(dataset: TestDataset): Result[EvalSummary]
  def evaluateMetric(sample: EvalSample, metricName: String): Result[MetricResult]
  def withMetrics(metricNames: Set[String]): RAGASEvaluator
  def withOptions(newOptions: EvaluatorOptions): RAGASEvaluator
  def withTracing(newTracer: Tracing): RAGASEvaluator
}
```

### RAGASFactory

```scala
object RAGASFactory {
  def fromConfigs(
    providerCfg: ProviderConfig,
    embedding: (String, EmbeddingProviderConfig)
  )(using ModelRegistryService, ProviderRegistry): Result[RAGASEvaluator]

  def create(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig
  ): RAGASEvaluator

  def withMetrics(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    embeddingModelConfig: EmbeddingModelConfig,
    metricNames: Set[String] // faithfulness, answer_relevancy, context_precision, context_recall
  ): RAGASEvaluator
}
```

### EvalSample

```scala
final case class EvalSample(
  question: String,
  answer: String,
  contexts: Seq[String],
  groundTruth: Option[String] = None,
  metadata: Map[String, String] = Map.empty
)
```

### EvalResult, MetricResult and EvalSummary

```scala
final case class EvalResult(
  sample: EvalSample,
  metrics: Seq[MetricResult],
  ragasScore: Double, // mean of the computed metrics
  evaluatedAt: Long = System.currentTimeMillis()
) {
  def getMetric(name: String): Option[MetricResult]
}

final case class MetricResult(
  metricName: String,
  score: Double,
  details: Map[String, Any] = Map.empty
)

final case class EvalSummary(
  results: Seq[EvalResult],
  averages: Map[String, Double], // metric name -> average score
  overallRagasScore: Double,
  sampleCount: Int
)
```

### BenchmarkRunner

```scala
class BenchmarkRunner(
  llmClient: LLMClient,
  embeddingClient: EmbeddingClient,
  resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig],
  datasetManager: DatasetManager = DatasetManager(),
  val options: BenchmarkRunnerOptions = BenchmarkRunnerOptions()
)(using ModelRegistryService) {
  def runSuite(suite: BenchmarkSuite): Result[BenchmarkResults]
  def runExperiment(config: RAGExperimentConfig, dataset: TestDataset): Result[ExperimentResult]
  def quickTest(config: RAGExperimentConfig, datasetPath: String, sampleCount: Int = 5): Result[ExperimentResult]
  def compareConfigs(
    config1: RAGExperimentConfig,
    config2: RAGExperimentConfig,
    datasetPath: String,
    sampleCount: Option[Int] = None
  ): Result[ExperimentComparison]
}

object BenchmarkRunner {
  def apply(
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig],
    options: BenchmarkRunnerOptions = BenchmarkRunnerOptions()
  )(using ModelRegistryService): BenchmarkRunner
}
```

### BenchmarkReport

```scala
object BenchmarkReport {
  def console(results: BenchmarkResults): String
  def consoleDetailed(results: BenchmarkResults): String
  def json(results: BenchmarkResults, pretty: Boolean = true): String
  def markdown(results: BenchmarkResults): String
  def save(results: BenchmarkResults, path: String, format: ReportFormat = ReportFormat.Json): Result[Unit]
  def comparison(comparison: ExperimentComparison): String
}
```

---

## Next Steps

- **[Vector Store Guide](vector-store)** - Configure vector backends
- **[Benchmark Results](../rag-benchmark-results.md)** - Detailed benchmark data
- **[Examples Gallery](/examples/)** - RAG implementation examples
