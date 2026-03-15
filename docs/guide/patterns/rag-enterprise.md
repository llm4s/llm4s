---
layout: page
title: RAG for Enterprise
parent: Real-World Application Patterns
nav_order: 2
---

# RAG for Enterprise

> **Note:** Code examples in this guide are illustrative pseudocode showing recommended patterns. For working examples using the actual LLM4S API, see [modules/samples](../../../modules/samples/).

Learn production-ready strategies for building Retrieval-Augmented Generation (RAG) systems at scale. This guide covers document ingestion, hybrid search, cost optimization, and quality assurance.

## Overview

Enterprise RAG requires careful attention to several challenges:

- **Document Volume**: Processing thousands or millions of documents
- **Freshness**: Keeping retrieved information current
- **Quality**: Ensuring search results are relevant and accurate
- **Cost**: Minimizing embedding and LLM API costs
- **Compliance**: Handling sensitive data and audit trails

---

## Document Ingestion at Scale

### Chunking Strategies

#### 1. Fixed-Size Chunks with Overlap

```text
object ChunkingStrategies {
  
  case class Chunk(
    text: String,
    source: String,
    chunkIndex: Int,
    metadata: Map[String, String]
  )
  
  def fixedSizeChunks(
    text: String,
    chunkSize: Int = 512,
    overlapSize: Int = 50,
    source: String
  ): List[Chunk] = {
    val chunks = scala.collection.mutable.ListBuffer[Chunk]()
    var startIdx = 0
    var chunkIndex = 0
    
    while (startIdx < text.length) {
      val endIdx = Math.min(startIdx + chunkSize, text.length)
      val chunk = text.substring(startIdx, endIdx)
      
      chunks += Chunk(
        text = chunk,
        source = source,
        chunkIndex = chunkIndex,
        metadata = Map(
          "startIdx" -> startIdx.toString,
          "endIdx" -> endIdx.toString
        )
      )
      
      startIdx = endIdx - overlapSize
      chunkIndex += 1
    }
    
    chunks.toList
  }
  
  // Example: Fixed 512-token chunks with 50-token overlap
  val document = "Your long document text here..."
  val chunks = fixedSizeChunks(document, 512, 50, "documents/whitepaper.pdf")
}
```

#### 2. Semantic Chunking

```text
object SemanticChunking {
  
  case class SemanticChunk(
    text: String,
    topic: String,
    source: String,
    metadata: Map[String, String]
  )
  
  def semanticChunks(
    text: String,
    source: String,
    embeddingClient: EmbeddingClient
  ): Result[List[SemanticChunk]] = {
    // Split by paragraphs first
    val paragraphs = text.split("\n\n").toList
    
    val chunks = scala.collection.mutable.ListBuffer[SemanticChunk]()
    var currentChunk = ""
    var currentTopic = ""
    
    paragraphs.foreach { paragraph =>
      currentChunk += "\n" + paragraph
      
      // Check if chunk is getting too large
      if (currentChunk.length > 1000) {
        // Create chunk
        chunks += SemanticChunk(
          text = currentChunk,
          topic = currentTopic,
          source = source,
          metadata = Map("type" -> "semantic")
        )
        currentChunk = ""
      }
    }
    
    // Add remaining chunk
    if (currentChunk.nonEmpty) {
      chunks += SemanticChunk(
        text = currentChunk,
        topic = currentTopic,
        source = source,
        metadata = Map("type" -> "semantic")
      )
    }
    
    Result.success(chunks.toList)
  }
}
```

#### 3. Document-Aware Chunking

```text
object DocumentAwareChunking {
  
  sealed trait DocumentType
  case object ResearchPaper extends DocumentType
  case object LegalDocument extends DocumentType
  case object ProductSpec extends DocumentType
  case object WebPage extends DocumentType
  
  def chunkByType(
    text: String,
    docType: DocumentType,
    source: String
  ): List[String] = docType match {
    // Research papers: chunk by sections
    case ResearchPaper =>
      text.split("(?=^## |^# )").toList
    
    // Legal documents: chunk by clauses
    case LegalDocument =>
      text.split("(?=\\d+\\.|[A-Z][A-Z ]+\\s*\\n)").toList
    
    // Product specs: chunk by features
    case ProductSpec =>
      text.split("(?=### |^Features|^Specifications)").toList
    
    // Web pages: chunk by sections
    case WebPage =>
      text.split("(?=<h1|<h2|<h3)").toList
  }
}
```

### Batch Processing Large Document Sets

```text
object BatchIngestion {
  
  import scala.concurrent.{Future, ExecutionContext}
  
  case class IngestionJob(
    documents: List[String],
    batchSize: Int = 50,
    embeddingClient: EmbeddingClient,
    vectorStore: VectorStore
  )
  
  def ingestInBatches(
    job: IngestionJob
  )(implicit ec: ExecutionContext): Future[Result[Int]] = {
    val batches = job.documents.grouped(job.batchSize).toList
    
    // Process batches sequentially to avoid rate limits
    val results = batches.foldLeft(Future.successful(0)) { (accFuture, batch) =>
      accFuture.flatMap { processedCount =>
        Future {
          // Embed batch
          val embeddings = batch.map { doc =>
            job.embeddingClient.embed(doc)
          }
          
          // Store in vector database
          embeddings.foreach { embedding =>
            job.vectorStore.store(embedding)
          }
          
          processedCount + batch.length
        }.flatMap(identity)
      }
    }
    
    results.map(count => Result.success(count))
  }
  
  // With progress tracking
  case class IngestionProgress(
    processed: Int,
    total: Int,
    rate: Double // docs/second
  ) {
    def percentage: Double = (processed.toDouble / total) * 100
  }
  
  def ingestWithProgress(
    job: IngestionJob,
    onProgress: IngestionProgress => Unit
  )(implicit ec: ExecutionContext): Future[Result[Int]] = {
    val batches = job.documents.grouped(job.batchSize).toList
    val total = job.documents.length
    val startTime = System.currentTimeMillis()
    
    val results = batches.zipWithIndex.foldLeft(Future.successful(0)) { 
      case (accFuture, (batch, batchIdx)) =>
        accFuture.flatMap { processedCount =>
          Future {
            // Process batch...
            val newProcessedCount = processedCount + batch.length
            
            // Report progress
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val rate = newProcessedCount / elapsed
            onProgress(IngestionProgress(newProcessedCount, total, rate))
            
            newProcessedCount
          }
        }
    }
    
    results.map(count => Result.success(count))
  }
}
```

---

## Hybrid Search Implementation

### Vector + Keyword Search

```text
object HybridSearch {
  
  case class SearchResult(
    content: String,
    source: String,
    vectorScore: Double,
    keywordScore: Double,
    hybridScore: Double // weighted combination
  )
  
  def hybridSearch(
    query: String,
    vectorStore: VectorStore,
    keywordIndex: KeywordIndex,
    alpha: Double = 0.7 // weight for vector score
  ): Result[List[SearchResult]] = for {
    // Vector search
    vectorResults <- vectorStore.search(query, topK = 10)
    
    // Keyword search
    keywordResults <- keywordIndex.search(query, topK = 10)
    
    // Normalize scores
    normalizedVector = vectorResults.map(r => 
      r.copy(score = r.score / vectorResults.map(_.score).max)
    )
    
    normalizedKeyword = keywordResults.map(r => 
      r.copy(score = r.score / keywordResults.map(_.score).max)
    )
    
    // Combine results
    combined = combineResults(
      normalizedVector,
      normalizedKeyword,
      alpha
    )
  } yield combined
  
  private def combineResults(
    vectorResults: List[SearchResult],
    keywordResults: List[SearchResult],
    alpha: Double
  ): List[SearchResult] = {
    val resultMap = scala.collection.mutable.Map[String, SearchResult]()
    
    // Add vector results
    vectorResults.foreach { r =>
      resultMap(r.source) = r.copy(
        hybridScore = alpha * r.vectorScore
      )
    }
    
    // Add keyword results
    keywordResults.foreach { r =>
      if (resultMap.contains(r.source)) {
        val existing = resultMap(r.source)
        resultMap(r.source) = existing.copy(
          hybridScore = existing.hybridScore + (1 - alpha) * r.keywordScore
        )
      } else {
        resultMap(r.source) = r.copy(
          hybridScore = (1 - alpha) * r.keywordScore
        )
      }
    }
    
    resultMap.values.toList.sortBy(_.hybridScore).reverse
  }
}
```

### Multi-Stage Retrieval

```text
object MultiStageRetrieval {
  
  def multiStageRetrieval(
    query: String,
    vectorStore: VectorStore,
    reranker: RerankerModel,
    grounding: GroundingSystem
  ): Result[List[RankedResult]] = for {
    // Stage 1: Broad retrieval
    candidates <- vectorStore.search(query, topK = 100)
    
    // Stage 2: Re-ranking with smaller model
    reranked <- Future(
      reranker.rank(query, candidates)
    ).toResult
    
    topK = reranked.take(10)
    
    // Stage 3: Grounding check
    grounded <- Future(
      topK.filter(r => grounding.isWellGrounded(r.content, query))
    ).toResult
  } yield grounded
  
  case class RankedResult(
    content: String,
    source: String,
    score: Double,
    rerankerScore: Option[Double] = None,
    isGrounded: Boolean = false
  )
}
```

---

## Cost Optimization

### Embedding Caching

```text
object EmbeddingCache {
  
  import scala.collection.mutable
  
  class SmartEmbeddingCache(embeddingClient: EmbeddingClient) {
    private val cache = mutable.Map[String, Vector[Double]]()
    private var hitRate = 0.0
    private var totalQueries = 0
    
    def embed(text: String): Vector[Double] = {
      totalQueries += 1
      
      cache.get(text) match {
        case Some(embedding) =>
          hitRate = (hitRate * (totalQueries - 1) + 1) / totalQueries
          embedding
        case None =>
          hitRate = (hitRate * (totalQueries - 1)) / totalQueries
          val embedding = embeddingClient.embed(text)
          cache(text) = embedding
          embedding
      }
    }
    
    def stats(): CacheStats = CacheStats(
      hitRate = hitRate,
      cachedItems = cache.size,
      estimatedSavings = cache.size * 0.0002 // estimate ~$0.0002 per embedding
    )
  }
  
  case class CacheStats(
    hitRate: Double,
    cachedItems: Int,
    estimatedSavings: Double
  )
}
```

### Token Cost Tracking

```text
object CostTracking {
  
  case class CostBreakdown(
    embeddings: Double,
    retrievals: Double,
    llmCalls: Double,
    total: Double
  )
  
  case class PerQueryCost(
    query: String,
    embeddingCost: Double,
    retrievalCost: Double,
    llmCost: Double
  ) {
    def total: Double = embeddingCost + retrievalCost + llmCost
  }
  
  class RAGCostTracker {
    private val queryLogs = scala.collection.mutable.ListBuffer[PerQueryCost]()
    
    def logQuery(
      query: String,
      embeddingTokens: Int,
      retrievalTime: Long,
      llmInputTokens: Int,
      llmOutputTokens: Int
    ): Unit = {
      val embeddingCost = embeddingTokens * 0.00002 // OpenAI pricing
      val retrievalCost = (retrievalTime / 1000.0) * 0.001 // $0.001 per second
      val llmCost = (llmInputTokens * 0.000003 + llmOutputTokens * 0.000006) // GPT-4 pricing
      
      queryLogs += PerQueryCost(
        query = query,
        embeddingCost = embeddingCost,
        retrievalCost = retrievalCost,
        llmCost = llmCost
      )
    }
    
    def getCostSummary(): CostBreakdown = {
      val totalEmbeddings = queryLogs.map(_.embeddingCost).sum
      val totalRetrievals = queryLogs.map(_.retrievalCost).sum
      val totalLLM = queryLogs.map(_.llmCost).sum
      
      CostBreakdown(
        embeddings = totalEmbeddings,
        retrievals = totalRetrievals,
        llmCalls = totalLLM,
        total = totalEmbeddings + totalRetrievals + totalLLM
      )
    }
    
    def averageCostPerQuery(): Double = {
      if (queryLogs.isEmpty) 0.0
      else getCostSummary().total / queryLogs.length
    }
  }
}
```

### Query Optimization

```text
object QueryOptimization {
  
  def optimizeQuery(
    userQuery: String,
    optimizationAgent: Agent
  ): Result[String] = {
    // Use LLM to reformulate query for better retrieval
    optimizationAgent.run(
      s"""Reformulate this search query to maximize relevance in a RAG system:
        |Original: "$userQuery"
        |
        |Requirements:
        |1. Add semantic keywords
        |2. Specify document types if relevant
        |3. Include related concepts
        |4. Keep under 20 words
        |
        |Optimized query:""".stripMargin
    ).map(_.message)
  }
  
  def multiQueryRetrieval(
    userQuery: String,
    optimizationAgent: Agent,
    vectorStore: VectorStore
  ): Result[List[SearchResult]] = {
    // Generate multiple search variants
    optimizationAgent.run(
      s"""Generate 3 alternative search queries for: "$userQuery"
        |Format: Query 1: ..., Query 2: ..., Query 3: ...""".stripMargin
    ).flatMap { result =>
      val queries = result.message.split(",").map(_.trim).toList
      
      // Search with each variant
      val results = queries.flatMap { q =>
        vectorStore.search(q, topK = 5)
      }
      
      Result.success(results)
    }
  }
}
```

---

## Quality Assurance

### Grounding System

```text
object Grounding {
  
  case class GroundingScore(
    score: Double, // 0.0 to 1.0
    reasoning: String,
    isHallucination: Boolean
  )
  
  class GroundingChecker(
    groundingAgent: Agent,
    sourceDocuments: List[String]
  ) {
    def check(
      claim: String,
      retrievedContext: String
    ): Result[GroundingScore] = {
      groundingAgent.run(
        s"""Evaluate if this claim is grounded in the provided context:
          |
          |Claim: "$claim"
          |
          |Context:
          |$retrievedContext
          |
          |Score (0.0-1.0): [provide number]
          |Is hallucination: [yes/no]
          |Reasoning: [brief explanation]""".stripMargin
      ).map { response =>
        // Parse response to extract score
        val score = extractScore(response.message)
        val isHallucination = response.message.contains("yes")
        
        GroundingScore(
          score = score,
          reasoning = response.message,
          isHallucination = isHallucination
        )
      }
    }
    
    private def extractScore(response: String): Double = {
      val pattern = """Score.*?(\d+\.\d+)""".r
      pattern.findFirstMatchIn(response)
        .map(_.group(1).toDouble)
        .getOrElse(0.0)
    }
  }
}
```

### Relevance Metrics

```text
object RelevanceMetrics {
  
  case class RelevanceMetrics(
    mrr: Double, // Mean Reciprocal Rank
    ndcg: Double, // Normalized Discounted Cumulative Gain
    precision: Double, // Precision@10
    recall: Double // Recall
  )
  
  def evaluateRetrieval(
    query: String,
    retrievedResults: List[SearchResult],
    relevantDocuments: List[String]
  ): RelevanceMetrics = {
    val mrr = calculateMRR(retrievedResults, relevantDocuments)
    val ndcg = calculateNDCG(retrievedResults, relevantDocuments)
    val precision = calculatePrecision(retrievedResults, relevantDocuments)
    val recall = calculateRecall(retrievedResults, relevantDocuments)
    
    RelevanceMetrics(
      mrr = mrr,
      ndcg = ndcg,
      precision = precision,
      recall = recall
    )
  }
  
  private def calculateMRR(
    retrieved: List[SearchResult],
    relevant: List[String]
  ): Double = {
    retrieved.zipWithIndex.collectFirst {
      case (result, idx) if relevant.contains(result.source) => 1.0 / (idx + 1)
    }.getOrElse(0.0)
  }
  
  private def calculateNDCG(
    retrieved: List[SearchResult],
    relevant: List[String]
  ): Double = {
    val dcg = retrieved.zipWithIndex.map { case (result, idx) =>
      val relevance = if (relevant.contains(result.source)) 1.0 else 0.0
      relevance / Math.log(idx + 2) // log base 2
    }.sum
    
    val idcg = Math.min(relevant.size, retrieved.size)
    if (idcg == 0) 0.0 else dcg / idcg
  }
  
  private def calculatePrecision(
    retrieved: List[SearchResult],
    relevant: List[String]
  ): Double = {
    if (retrieved.isEmpty) 0.0
    else {
      val correct = retrieved.count(r => relevant.contains(r.source))
      correct.toDouble / Math.min(10, retrieved.size) // Precision@10
    }
  }
  
  private def calculateRecall(
    retrieved: List[SearchResult],
    relevant: List[String]
  ): Double = {
    if (relevant.isEmpty) 0.0
    else {
      val correct = retrieved.count(r => relevant.contains(r.source))
      correct.toDouble / relevant.size
    }
  }
}
```

---

## Production RAG Pipeline

```text
object ProductionRAG {
  
  case class RAGConfig(
    embeddingBatchSize: Int = 50,
    vectorSearchTopK: Int = 10,
    hybridAlpha: Double = 0.7,
    groundingThreshold: Double = 0.7,
    costTrackingEnabled: Boolean = true
  )
  
  class ProductionRAGPipeline(
    config: RAGConfig,
    embeddingClient: EmbeddingClient,
    vectorStore: VectorStore,
    groundingChecker: GroundingChecker,
    costTracker: CostTracker
  ) {
    
    def answerQuestion(
      question: String,
      agent: Agent
    ): Result[String] = for {
      // Step 1: Search for relevant documents
      startTime <- Result.success(System.currentTimeMillis())
      
      searchResults <- vectorStore.search(question, config.vectorSearchTopK)
      context = searchResults.map(_.content).mkString("\n\n")
      
      // Step 2: Generate answer with context
      answer <- agent.run(
        s"""Answer this question using the provided context.
          |If the answer is not in the context, say "I don't have enough information".
          |
          |Question: $question
          |
          |Context:
          |$context""".stripMargin
      )
      
      // Step 3: Check grounding
      groundingScore <- groundingChecker.check(answer.message, context)
      
      finalAnswer <- if (groundingScore.score >= config.groundingThreshold) {
        Result.success(answer.message)
      } else {
        Result.failure(
          s"Answer not sufficiently grounded (score: ${groundingScore.score}). " +
          s"Retrieved context may be insufficient."
        )
      }
      
      // Step 4: Track costs
      elapsed <- Result.success(System.currentTimeMillis() - startTime)
      _ <- if (config.costTrackingEnabled) {
        costTracker.logQuery(
          question = question,
          embeddingTokens = question.split(" ").length * 2,
          retrievalTime = elapsed,
          llmInputTokens = (question + context).split(" ").length,
          llmOutputTokens = finalAnswer.split(" ").length
        )
        Result.success(())
      } else {
        Result.success(())
      }
      
    } yield finalAnswer
  }
}
```

---

## Best Practices

### ✅ Do's

- **Version your embeddings**: Track which embedding model was used
- **Monitor relevance**: Track NDCG and MRR over time
- **Batch process documents**: Reduce API costs with batch operations
- **Use hybrid search**: Combine vector and keyword for better results
- **Track groundedness**: Ensure retrieved context supports answers
- **Set TTLs on cache**: Refresh stale embeddings periodically
- **A/B test retrieval**: Compare different chunking strategies

### ❌ Don'ts

- **Don't ignore chunk quality**: Poor chunks hurt relevance
- **Don't forget deduplication**: Avoid indexing same document twice
- **Don't hardcode thresholds**: Make grounding scores configurable
- **Don't skip validation**: Always verify retrieval quality
- **Don't neglect cost tracking**: Monitor and optimize spending
- **Don't lose metadata**: Track source, date, version in embeddings
- **Don't assume embedding stability**: Embeddings can change between models

---

## See Also

- [Production Monitoring](./production-monitoring.md) - Monitor RAG system health
- [Error Recovery](./error-recovery.md) - Handle retrieval failures
- [Scaling Strategies](./scaling-strategies.md) - Scale RAG to large datasets

---

**Last Updated:** February 2026  
**Status:** Production Ready
