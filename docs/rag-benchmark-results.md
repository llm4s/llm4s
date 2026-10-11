# RAG Benchmark Results Documentation

This document describes the RAG benchmarking harness and provides actual benchmark results from running the system.

> **Results predate the `SentenceChunker` fix ([#1718](https://github.com/llm4s/llm4s/issues/1718),
> fixed by [#1724](https://github.com/llm4s/llm4s/pull/1724)); see [#1725](https://github.com/llm4s/llm4s/issues/1725).**
> Every number on this page was produced in December 2025, when `SentenceChunker` split on
> `([.!?])(\s+)([A-Z])` with `Regex.split`. That deleted the punctuation, the whitespace **and the first letter of
> the next sentence** at every sentence boundary, glued the pieces back together and so almost never split:
> `"Paris is the capital and largest city of France. It is located in ..."` was indexed and embedded as
> `"Paris is the capital and largest city of Francet is located in ..."`, and a document's final `.` was
> sometimes dropped too (when its last word ended like an abbreviation, e.g. `processed.`). Since #1724 sentences are cut from the input itself, every character is kept, and the sentences in a chunk
> keep the whitespace that separated them.
>
> **Which rows are affected:** every experiment whose chunking strategy is `sentence`. That is the default
> (`RAGExperimentConfig.chunkingStrategy`), so it covers **all six fusion rows, all four embedding rows, and the
> `sentence-default`, `sentence-large` and `sentence-small` chunking rows**. Only `simple-default` and
> `markdown-default` were chunked by an unaffected chunker, and they are ranked against corrupted rows. The
> "Expected Results", "Sample Console Output" and "JSON Report Format" blocks are illustrative, not measured,
> and use the same configurations. Treat the rankings and the findings drawn from them as unverified until the
> suites are re-run.
>
> **To regenerate** (needs an LLM provider for the RAGAS judge and an embedding API key, so it has not been
> re-run yet; see [Running the Benchmarks](#running-the-benchmarks) for the configuration):
>
> ```bash
> ./scripts/download-datasets.sh ragbench
> sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite chunking --quick"
> sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite fusion --quick"
> sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite embedding --quick"
> ```
>
> The December 2025 run used a 5-question RAGBench sample; `--quick` limits each suite to 5 samples, and
> dropping it runs the whole dataset. Each run writes
> `data/results/<suite-name>-<timestamp>.json`.

## Actual Benchmark Results (December 15, 2025)

### Test Configuration
- **Dataset**: RAGBench sample (5 questions)
- **LLM**: OpenAI GPT-4o
- **Embeddings**: OpenAI text-embedding-3-small (1536 dimensions)
- **Base URL**: https://api.openai.com

---

## Fusion Strategy Benchmark Results

The fusion comparison benchmark compares different hybrid search strategies:

> **Predates the `SentenceChunker` fix (#1718, #1724):** all six rows used sentence chunking (800 / 1200 / 150,
> the defaults), so every row was indexed with corrupted text. Not yet re-run.

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

WINNER: rrf-20 (RAGAS: 0.911)
======================================================================
```

### Key Findings

**RRF with k=20 performed best** with:
- **RAGAS Score**: 0.911
- **Faithfulness**: 1.000 (answers fully supported by context)
- **Answer Relevancy**: 0.844 (answers highly relevant to questions)

**Hybrid Search Outperforms Single-Method Search**:
- All RRF and weighted fusion strategies outperformed vector-only (0.884)
- Keyword-only performed worst (0.809), but still provides value in hybrid fusion
- The combination of semantic and lexical search improves retrieval quality

---

## Chunking Strategy Benchmark Results

> **Predates the `SentenceChunker` fix (#1718, #1724):** `sentence-small`, `sentence-large` and
> `sentence-default` (ranks 1-3) were measured on corrupted, rarely split chunks. `markdown-default` and
> `simple-default` were not chunked by `SentenceChunker`, but their ranks are relative to those rows. The
> finding that sentence chunking beats simple chunking rests on the corrupted rows. Not yet re-run.

```
======================================================================
BENCHMARK RESULTS: chunking-comparison
Compare different chunking strategies
======================================================================

Duration: 273.34s
Experiments: 5 (5 passed, 0 failed)

RANKINGS BY RAGAS SCORE
----------------------------------------------------------------------
Rank   Config                    RAGAS      Faith      Relevancy
----------------------------------------------------------------------
1      sentence-small            0.910      1.000      0.840
2      sentence-large            0.893      1.000      0.838
3      sentence-default          0.892      1.000      0.834
4      markdown-default          0.885      0.960      0.847
5      simple-default            0.882      0.960      0.833

WINNER: sentence-small (RAGAS: 0.910)
======================================================================
```

### Key Findings

**Sentence Chunking with Smaller Chunks (400 target) performed best** with:
- **RAGAS Score**: 0.910
- **Faithfulness**: 1.000 (answers fully supported by context)
- **Answer Relevancy**: 0.840 (answers highly relevant to questions)

**All Chunking Strategies Produce Quality Results**:
- All strategies achieve RAGAS scores above 0.88
- Smaller chunks (400 tokens) work well for focused factual queries
- Sentence-aware chunking consistently outperforms simple character-based splitting

---

## Embedding Provider Benchmark Results

> **Predates the `SentenceChunker` fix (#1718, #1724):** every row used sentence chunking (the default); the one
> row that ran (`openai-text-embedding-3-small`) embedded corrupted chunks. Not yet re-run.

```
======================================================================
BENCHMARK RESULTS: embedding-comparison
Duration: 9.99s
Experiments: 4 (1 passed, 3 failed)

RANKINGS BY RAGAS SCORE
----------------------------------------------------------------------
Rank   Config                         RAGAS      Status
----------------------------------------------------------------------
1      openai-text-embedding-3-small  0.000      Passed
2      openai-text-embedding-3-large  -          Failed (URL issue)
3      voyage-voyage-3                -          Failed (No API key)
4      ollama-nomic-embed-text        -          Failed (Ollama not running)
======================================================================
```

**Notes**:
- Only OpenAI small model tested successfully
- Large model needs URL configuration fix
- Voyage requires `VOYAGE_API_KEY` environment variable
- Ollama requires local Ollama server running

---

## Running the Benchmarks

### Prerequisites

```bash
# Download dataset
./scripts/download-datasets.sh ragbench

# Set environment variables
export LLM4S_PROVIDER=openai-main   # an openai section in the samples' application.local.conf
export OPENAI_API_KEY=sk-...        # used by that section and by OpenAI embeddings
export EMBEDDING_MODEL=openai/text-embedding-3-small  # Unified provider/model format
```

### Available Benchmark Suites

```bash
# Chunking strategies comparison
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite chunking"

# Fusion strategies comparison
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite fusion"

# Embedding providers comparison
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite embedding"

# Quick test (5 samples)
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --quick"
```

---

## Benchmark Suite: Chunking Strategies

Compares different document chunking approaches with their impact on RAG performance.

### Configurations Tested

| Config | Strategy | Target Size | Max Size | Overlap |
|--------|----------|-------------|----------|---------|
| simple-default | Simple | 800 | 1200 | 150 |
| sentence-default | Sentence | 800 | 1200 | 150 |
| markdown-default | Markdown | 800 | 1200 | 150 |
| sentence-large | Sentence | 1200 | 1800 | 200 |
| sentence-small | Sentence | 400 | 600 | 100 |

### Expected Results (Typical Performance)

> **Illustrative, and predates the `SentenceChunker` fix (#1718, #1724):** these figures were not measured. The
> three `sentence-*` configurations chunk with `SentenceChunker`; see the note at the top.

```
======================================================================
BENCHMARK RESULTS: chunking-comparison
Compare different chunking strategies
======================================================================

RANKINGS BY RAGAS SCORE
----------------------------------------------------------------------
Rank   Config                    RAGAS      Faithfulness  Relevancy
----------------------------------------------------------------------
1      sentence-default          0.892      0.950         0.834
2      markdown-default          0.876      0.920         0.832
3      sentence-large            0.858      0.900         0.816
4      simple-default            0.851      0.890         0.812
5      sentence-small            0.821      0.860         0.782

======================================================================
WINNER: sentence-default (RAGAS: 0.892)
======================================================================
```

### Analysis

1. **Sentence Chunking (Default)** - Best overall performance
   - Preserves semantic boundaries at sentence level
   - 800 token target size provides good balance between context and precision
   - Highest faithfulness score due to complete sentence preservation

2. **Markdown Chunking** - Strong for structured documents
   - Respects document structure (headers, lists, code blocks)
   - Performs best when documents have clear markdown formatting
   - Slightly lower than sentence for unstructured text

3. **Sentence (Large Chunks)** - Good for complex queries
   - 1200 token chunks capture more context
   - Better for questions requiring multi-paragraph reasoning
   - Lower precision for simple fact-based queries

4. **Simple Chunking** - Baseline performance
   - Character-based splitting with no semantic awareness
   - Can split mid-sentence, reducing faithfulness
   - Fast but less accurate

5. **Sentence (Small Chunks)** - Precise but limited context
   - 400 token chunks are highly focused
   - Good for simple factual queries
   - Struggles with complex reasoning questions

### Recommendations

- **General Use**: `sentence-default` with 800 token target
- **Technical Docs**: `markdown-default` for code/structured content
- **Complex Reasoning**: `sentence-large` with 1200 token target
- **FAQ/Simple Q&A**: `sentence-small` with 400 token target

---

## Benchmark Suite: Fusion Strategies

Compares hybrid search fusion algorithms combining vector and keyword search.

### Configurations Tested

| Config | Strategy | Parameters |
|--------|----------|------------|
| rrf-60 | RRF | k=60 |
| rrf-20 | RRF | k=20 |
| weighted-70-30 | Weighted | vector=0.7, keyword=0.3 |
| weighted-50-50 | Weighted | vector=0.5, keyword=0.5 |
| vector-only | VectorOnly | - |
| keyword-only | KeywordOnly | - |

### Expected Results (Typical Performance)

> **Illustrative, and predates the `SentenceChunker` fix (#1718, #1724):** these figures were not measured. Every
> configuration here chunks with `SentenceChunker` (the default); see the note at the top.

```
======================================================================
BENCHMARK RESULTS: fusion-comparison
Compare different search fusion strategies
======================================================================

RANKINGS BY RAGAS SCORE
----------------------------------------------------------------------
Rank   Config                    RAGAS      Faithfulness  Relevancy
----------------------------------------------------------------------
1      rrf-60                    0.901      0.945         0.857
2      weighted-70-30            0.889      0.930         0.848
3      rrf-20                    0.882      0.925         0.839
4      weighted-50-50            0.865      0.910         0.820
5      vector-only               0.852      0.900         0.804
6      keyword-only              0.798      0.840         0.756

======================================================================
WINNER: rrf-60 (RAGAS: 0.901)
======================================================================
```

### Analysis

1. **RRF k=60** - Best overall performance
   - Reciprocal Rank Fusion balances vector and keyword results
   - k=60 provides good balance for diverse result sets
   - Most robust across different query types

2. **Weighted 70-30** - Strong second choice
   - 70% vector + 30% keyword weights
   - Good when semantic similarity is more important
   - Simpler to understand and tune

3. **RRF k=20** - More aggressive fusion
   - Lower k value gives more weight to top-ranked results
   - Better when confident in top matches
   - Can miss relevant results ranked lower

4. **Weighted 50-50** - Balanced approach
   - Equal weight to vector and keyword
   - Good starting point for experimentation
   - May dilute strong signals from either source

5. **Vector-Only** - Semantic search baseline
   - Pure embedding similarity search
   - Misses exact keyword matches
   - Good for conceptual queries

6. **Keyword-Only** - BM25 baseline
   - Traditional keyword matching
   - Misses semantic relationships
   - Good for exact term matching

### Recommendations

- **General Use**: `rrf-60` for best overall performance
- **Semantic Focus**: `weighted-70-30` when concepts matter more than terms
- **Precision Focus**: `rrf-20` when top results are most important
- **Legacy Comparison**: `keyword-only` as baseline for improvement metrics

---

## Benchmark Suite: Embedding Providers

Compares different embedding model providers.

### Configurations Tested

| Config | Provider | Model | Dimensions |
|--------|----------|-------|------------|
| openai-small | OpenAI | text-embedding-3-small | 1536 |
| openai-large | OpenAI | text-embedding-3-large | 3072 |
| voyage-3 | Voyage | voyage-3 | 1024 |
| ollama-nomic | Ollama | nomic-embed-text | 768 |

### Expected Results (Typical Performance)

> **Illustrative, and predates the `SentenceChunker` fix (#1718, #1724):** these figures were not measured. Every
> configuration here chunks with `SentenceChunker` (the default); see the note at the top.

```
======================================================================
BENCHMARK RESULTS: embedding-comparison
Compare different embedding providers
======================================================================

RANKINGS BY RAGAS SCORE
----------------------------------------------------------------------
Rank   Config                    RAGAS      Faithfulness  Relevancy
----------------------------------------------------------------------
1      openai-large              0.912      0.955         0.869
2      voyage-3                  0.898      0.940         0.856
3      openai-small              0.885      0.930         0.840
4      ollama-nomic              0.842      0.890         0.794

======================================================================
WINNER: openai-large (RAGAS: 0.912)
======================================================================
```

### Analysis

1. **OpenAI text-embedding-3-large** - Highest quality
   - 3072 dimensions capture fine-grained semantics
   - Best for production systems requiring top accuracy
   - Higher cost and latency

2. **Voyage voyage-3** - Excellent balance
   - Specialized for retrieval tasks
   - 1024 dimensions with strong performance
   - Good cost/performance ratio

3. **OpenAI text-embedding-3-small** - Good default
   - 1536 dimensions, lower cost than large
   - Fast and reliable
   - Good for most use cases

4. **Ollama nomic-embed-text** - Local option
   - 768 dimensions
   - Free, runs locally
   - Lower quality but no API costs

### Cost Considerations

| Provider | Cost per 1M tokens | Latency |
|----------|-------------------|---------|
| OpenAI Large | $0.13 | ~200ms |
| OpenAI Small | $0.02 | ~100ms |
| Voyage | $0.10 | ~150ms |
| Ollama | $0 (local) | ~50ms |

### Recommendations

- **Highest Quality**: `openai-large` for production systems
- **Best Value**: `openai-small` for cost-effective quality
- **Retrieval Focus**: `voyage-3` optimized for RAG
- **Local/Free**: `ollama-nomic` for development or cost-sensitive apps

---

## RAGAS Metrics Explained

### Faithfulness (0.0 - 1.0)
Measures whether the generated answer is grounded in the retrieved context.
- **1.0**: Answer fully supported by context
- **0.0**: Answer contains unsupported claims

### Answer Relevancy (0.0 - 1.0)
Measures how relevant the answer is to the question.
- **1.0**: Answer directly addresses the question
- **0.0**: Answer is completely off-topic

### Context Precision (0.0 - 1.0)
Measures whether retrieved contexts are relevant to the question.
- **1.0**: All retrieved chunks are relevant
- **0.0**: No retrieved chunks are relevant

### Context Recall (0.0 - 1.0)
Measures whether all ground truth information was retrieved.
- **1.0**: All necessary information was retrieved
- **0.0**: No relevant information was retrieved

### Overall RAGAS Score
Harmonic mean of all metrics, providing a single quality score.

---

## Sample Console Output

> **Illustrative:** an example of the report format, not a measured run. Its `sentence-*` rows predate the
> `SentenceChunker` fix (#1718, #1724).

```
======================================================================
BENCHMARK RESULTS: chunking-comparison
Compare different chunking strategies
======================================================================

Started:     2024-12-15 10:30:45
Completed:   2024-12-15 10:35:22
Duration:    4m 37s
Experiments: 5 (5 passed, 0 failed)

RANKINGS BY RAGAS SCORE
----------------------------------------------------------------------
Rank   Config                    RAGAS      Faithfulness  Relevancy
----------------------------------------------------------------------
1      sentence-default          0.892      0.950         0.834
2      markdown-default          0.876      0.920         0.832
3      sentence-large            0.858      0.900         0.816
4      simple-default            0.851      0.890         0.812
5      sentence-small            0.821      0.860         0.782

TIMING BREAKDOWN
----------------------------------------------------------------------
Config              Index     Search    Evaluate    Total
----------------------------------------------------------------------
sentence-default    12.3s     8.5s      34.2s       55.0s
markdown-default    14.1s     8.2s      33.8s       56.1s
sentence-large      10.8s     7.9s      32.1s       50.8s
simple-default      8.2s      8.1s      33.5s       49.8s
sentence-small      15.4s     8.8s      35.2s       59.4s

======================================================================
WINNER: sentence-default (RAGAS: 0.892)
======================================================================

RECOMMENDATION: Use 'sentence-default' configuration
               sentence | openai/text-embedding-3-small | RRF(60)
======================================================================
```

---

## JSON Report Format

Reports are saved to `data/results/<suite-name>-<timestamp>.json`:

```json
{
  "suite": {
    "name": "chunking-comparison",
    "description": "Compare different chunking strategies",
    "datasetPath": "data/datasets/ragbench/test.jsonl",
    "experimentCount": 5
  },
  "experiments": [
    {
      "config": {
        "name": "sentence-default",
        "chunkingStrategy": "sentence",
        "chunkingConfig": {
          "targetSize": 800,
          "maxSize": 1200,
          "overlap": 150
        },
        "embeddingProvider": "openai",
        "embeddingModel": "text-embedding-3-small",
        "fusionStrategy": "RRF(60)"
      },
      "metrics": {
        "ragasScore": 0.892,
        "faithfulness": 0.950,
        "answerRelevancy": 0.834,
        "contextPrecision": 0.912,
        "contextRecall": 0.878
      },
      "timing": {
        "indexingMs": 12300,
        "searchMs": 8500,
        "evaluationMs": 34200
      },
      "stats": {
        "documentCount": 100,
        "chunkCount": 450,
        "queryCount": 100
      },
      "success": true
    }
  ],
  "summary": {
    "successCount": 5,
    "failureCount": 0,
    "winner": "sentence-default",
    "averageRagas": 0.860,
    "totalDurationMs": 271100
  }
}
```

---

## Running Your Own Benchmarks

### Custom Dataset Format

Create a JSONL file with the following format:

```jsonl
{"question": "What is X?", "documents": ["Context 1", "Context 2"], "answer": "Ground truth answer"}
```

### Programmatic Usage

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.rag.benchmark._
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }

// Initialize clients
val runner = for {
  providerConfig <- Llm4sConfig.provider()
  llm <- LLMConnect.getClient(providerConfig)
  (embedProvider, embedConfig) <- Llm4sConfig.embeddings()
  embed <- EmbeddingClient.from(embedProvider, embedConfig)
} yield BenchmarkRunner(llm, embed)

// Run a suite
runner.flatMap { r =>
  val suite = BenchmarkSuite.chunkingSuite("my-dataset.jsonl")
  r.runSuite(suite)
}.foreach { results =>
  println(BenchmarkReport.console(results))
}
```

### Custom Experiment Configuration

```scala
val customConfig = RAGExperimentConfig(
  name = "my-experiment",
  description = "Custom configuration",
  chunkingStrategy = ChunkerFactory.Strategy.Sentence,
  chunkingConfig = ChunkingConfig(targetSize = 1000, maxSize = 1500, overlap = 200),
  embeddingConfig = EmbeddingConfig.OpenAI("text-embedding-3-large", 3072),
  fusionStrategy = FusionStrategy.RRF(40),
  topK = 10
)
```

---

## Conclusion

The RAG benchmarking harness enables systematic comparison of different configurations to optimize retrieval-augmented generation quality. Key findings from actual benchmarks:

> **Predates the `SentenceChunker` fix (#1718, #1724):** every finding below comes from runs that chunked with
> the corrupting `SentenceChunker` (findings 1-3 directly; finding 4 was not measured). Re-run the suites with the
> commands at the top of this page before relying on them.

1. **Chunking**: Sentence-based chunking with smaller chunks (400 tokens) performs best for focused queries
2. **Fusion**: RRF with k=20 outperforms all other strategies including pure vector search
3. **Hybrid Search**: Combining vector and keyword search improves retrieval quality over either method alone
4. **Embeddings**: Larger models improve quality but at higher cost

Use these benchmarks to find the optimal configuration for your specific use case and dataset.
