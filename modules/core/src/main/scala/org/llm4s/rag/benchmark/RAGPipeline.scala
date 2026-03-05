package org.llm4s.rag.benchmark

import org.llm4s.chunking.{ ChunkerFactory, DocumentChunk, DocumentChunker }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.llm4s.vectorstore._

/**
 * A configurable RAG pipeline for benchmark experiments.
 *
 * Wraps all RAG components (chunker, embeddings, vector store, keyword index)
 * and provides a unified interface for indexing documents and answering queries.
 *
 * @param config Experiment configuration
 * @param llmClient LLM client for answer generation
 * @param embeddingClient Embedding client for vectorization
 * @param embeddingModelConfig Model config for embedding requests
 * @param hybridSearcher Hybrid search with vector + keyword fusion
 * @param chunker Document chunker based on config strategy
 * @param tracer Optional tracer for cost tracking
 */
final class RAGPipeline private (
  val config: RAGExperimentConfig,
  val llmClient: LLMClient,
  val embeddingClient: EmbeddingClient,
  val embeddingModelConfig: EmbeddingModelConfig,
  val hybridSearcher: HybridSearcher,
  val chunker: DocumentChunker,
  private val tracer: Option[Tracing]
) {

  // Embedding client with tracing enabled if tracer is provided
  private val tracedEmbeddingClient: EmbeddingClient = tracer match {
    case Some(t) => embeddingClient.withTracing(t)
    case None    => embeddingClient
  }

  private var documentCount: Int = 0
  private var chunkCount: Int    = 0

  /**
   * Index a single document.
   *
   * @param id Document identifier
   * @param content Document text content
   * @param metadata Optional metadata
   * @return Number of chunks created or error
   */
  def indexDocument(
    id: String,
    content: String,
    metadata: Map[String, String] = Map.empty
  ): Result[Int] = {
    val chunks = chunker.chunk(content, config.chunkingConfig)
    indexChunks(id, chunks, metadata)
  }

  /**
   * Index multiple documents.
   *
   * @param documents Sequence of (id, content, metadata) tuples
   * @return Total chunks created or error
   */
  def indexDocuments(
    documents: Seq[(String, String, Map[String, String])]
  ): Result[Int] = {
    val result = documents.foldLeft[Result[Int]](Right(0)) { case (acc, (id, content, metadata)) =>
      acc.flatMap(totalSoFar => indexDocument(id, content, metadata).map(count => totalSoFar + count))
    }
    result.foreach(_ => documentCount += documents.size)
    result
  }

  /**
   * Index pre-chunked content.
   */
  private def indexChunks(
    docId: String,
    chunks: Seq[DocumentChunk],
    metadata: Map[String, String]
  ): Result[Int] =
    if (chunks.isEmpty) {
      Right(0)
    } else {
      val startTime                    = System.nanoTime()
      var embeddingTokens: Option[Int] = None

      // Get embeddings for all chunks
      val contents = chunks.map(_.content)
      val request =
        EmbeddingRequest(input = contents, model = embeddingModelConfig)

      val result = for {
        response <- tracedEmbeddingClient.withOperation("indexing").embed(request)
        _          = { embeddingTokens = response.usage.map(_.totalTokens) }
        embeddings = response.embeddings
        _ <- {
          // Create vector records
          val vectorRecords = chunks.zip(embeddings).map { case (chunk, embedding) =>
            VectorRecord(
              id = s"$docId-chunk-${chunk.index}",
              embedding = embedding.map(_.toFloat).toArray,
              content = Some(chunk.content),
              metadata = metadata + ("docId" -> docId) + ("chunkIndex" -> chunk.index.toString)
            )
          }
          hybridSearcher.vectorStore.upsertBatch(vectorRecords)
        }
        _ <- {
          // Create keyword documents
          val keywordDocs = chunks.map { chunk =>
            KeywordDocument(
              id = s"$docId-chunk-${chunk.index}",
              content = chunk.content,
              metadata = metadata + ("docId" -> docId) + ("chunkIndex" -> chunk.index.toString)
            )
          }
          hybridSearcher.keywordIndex.indexBatch(keywordDocs)
        }
      } yield {
        chunkCount += chunks.size
        documentCount += 1
        chunks.size
      }

      // Emit RAG operation completed event
      result.foreach { _ =>
        val durationMs = (System.nanoTime() - startTime) / 1_000_000
        tracer.foreach(
          _.traceRAGOperation(
            operation = "index",
            durationMs = durationMs,
            embeddingTokens = embeddingTokens
          )
        )
      }

      result
    }

  /**
   * Search for relevant chunks.
   *
   * @param query Search query
   * @param topK Number of results (default from config)
   * @return Search results
   */
  def search(query: String, topK: Option[Int] = None): Result[Seq[HybridSearchResult]] = {
    val startTime                    = System.nanoTime()
    var embeddingTokens: Option[Int] = None
    val k                            = topK.getOrElse(config.topK)

    // Get query embedding
    val request = EmbeddingRequest(input = Seq(query), model = embeddingModelConfig)

    val result = for {
      response <- tracedEmbeddingClient.withOperation("query").embed(request)
      _ = { embeddingTokens = response.usage.map(_.totalTokens) }
      queryEmbedding <- response.embeddings.headOption
        .toRight(EmbeddingError(None, "Embedding provider returned empty embeddings list", embeddingModelConfig.name))
        .map(_.map(_.toFloat).toArray)
      results <-
        if (config.useReranker) {
          hybridSearcher.searchWithReranking(
            queryEmbedding,
            query,
            k,
            config.rerankTopK,
            config.fusionStrategy
          )
        } else {
          hybridSearcher.search(
            queryEmbedding,
            query,
            k,
            config.fusionStrategy
          )
        }
    } yield results

    // Emit RAG operation completed event
    result.foreach { _ =>
      val durationMs = (System.nanoTime() - startTime) / 1_000_000
      tracer.foreach(
        _.traceRAGOperation(
          operation = "search",
          durationMs = durationMs,
          embeddingTokens = embeddingTokens
        )
      )
    }

    result
  }

  /**
   * Generate an answer to a question using retrieved context.
   *
   * @param question The question to answer
   * @param topK Number of chunks to retrieve (default from config)
   * @return The generated answer and retrieved contexts
   */
  def answer(question: String, topK: Option[Int] = None): Result[RAGAnswer] = {
    val startTime                        = System.nanoTime()
    var llmPromptTokens: Option[Int]     = None
    var llmCompletionTokens: Option[Int] = None

    val result = for {
      searchResults <- search(question, topK)
      contexts = searchResults.map(_.content)
      answerWithUsage <- generateAnswerWithUsage(question, contexts)
    } yield {
      val (answerText, usage) = answerWithUsage
      usage.foreach { u =>
        llmPromptTokens = Some(u.promptTokens)
        llmCompletionTokens = Some(u.completionTokens)
      }
      RAGAnswer(
        question = question,
        answer = answerText,
        contexts = contexts,
        searchResults = searchResults
      )
    }

    // Emit RAG operation completed event for answer generation
    result.foreach { _ =>
      val durationMs = (System.nanoTime() - startTime) / 1_000_000
      tracer.foreach(
        _.traceRAGOperation(
          operation = "answer",
          durationMs = durationMs,
          llmPromptTokens = llmPromptTokens,
          llmCompletionTokens = llmCompletionTokens
        )
      )
    }

    result
  }

  /**
   * Generate answer from question and contexts using LLM.
   * Returns answer and optional token usage.
   */
  private def generateAnswerWithUsage(
    question: String,
    contexts: Seq[String]
  ): Result[(String, Option[TokenUsage])] = {
    val contextText = contexts.zipWithIndex
      .map { case (ctx, i) => s"[${i + 1}] $ctx" }
      .mkString("\n\n")

    val systemPrompt =
      """You are a helpful assistant that answers questions based on the provided context.
        |Use only the information from the context to answer the question.
        |If the context doesn't contain enough information, say so.
        |Be concise and accurate.""".stripMargin

    val userPrompt =
      s"""Context:
         |$contextText
         |
         |Question: $question
         |
         |Answer:""".stripMargin

    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(userPrompt)
      )
    )

    val options = CompletionOptions(temperature = 0.0, maxTokens = Some(500))

    llmClient.complete(conversation, options).map(c => (c.content, c.usage))
  }

  /** Get the number of documents indexed */
  def getDocumentCount: Int = documentCount

  /** Get the number of chunks indexed */
  def getChunkCount: Int = chunkCount

  /** Clear all indexed data */
  def clear(): Result[Unit] =
    for {
      _ <- hybridSearcher.vectorStore.clear()
      _ <- hybridSearcher.keywordIndex.clear()
    } yield {
      documentCount = 0
      chunkCount = 0
    }

  /** Close resources */
  def close(): Unit = hybridSearcher.close()
}

/**
 * Result from RAG pipeline answer generation.
 *
 * @param question The original question
 * @param answer The generated answer
 * @param contexts Retrieved context chunks
 * @param searchResults Full search results with scores
 */
final case class RAGAnswer(
  question: String,
  answer: String,
  contexts: Seq[String],
  searchResults: Seq[HybridSearchResult]
)

object RAGPipeline {

  /**
   * Create a RAG pipeline from experiment configuration.
   *
   * @param config Experiment configuration
   * @param llmClient LLM client for answer generation
   * @param embeddingClient Embedding client for vectorization
   * @param tracer Optional tracer for cost tracking
   * @return Configured pipeline or error
   */
  def fromConfig(
    config: RAGExperimentConfig,
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    tracer: Option[Tracing] = None
  ): Result[RAGPipeline] = {
    val embeddingModelConfig = EmbeddingModelConfig(
      config.embeddingConfig.model,
      config.embeddingConfig.dimensions
    )

    // Create chunker based on strategy (semantic requires embedding client)
    val chunker: DocumentChunker = config.chunkingStrategy match {
      case ChunkerFactory.Strategy.Semantic =>
        ChunkerFactory.semantic(embeddingClient, embeddingModelConfig)
      case other =>
        ChunkerFactory.create(other)
    }

    // Create in-memory hybrid searcher
    HybridSearcher.inMemory().map { searcher =>
      new RAGPipeline(
        config = config,
        llmClient = llmClient,
        embeddingClient = embeddingClient,
        embeddingModelConfig = embeddingModelConfig,
        hybridSearcher = searcher,
        chunker = chunker,
        tracer = tracer
      )
    }
  }

  /**
   * Create a RAG pipeline with custom stores.
   *
   * @param config Experiment configuration
   * @param llmClient LLM client
   * @param embeddingClient Embedding client
   * @param vectorStore Custom vector store
   * @param keywordIndex Custom keyword index
   * @param tracer Optional tracer for cost tracking
   * @return Configured pipeline
   */
  def withStores(
    config: RAGExperimentConfig,
    llmClient: LLMClient,
    embeddingClient: EmbeddingClient,
    vectorStore: VectorStore,
    keywordIndex: KeywordIndex,
    tracer: Option[Tracing] = None
  ): RAGPipeline = {
    val embeddingModelConfig = EmbeddingModelConfig(
      config.embeddingConfig.model,
      config.embeddingConfig.dimensions
    )

    val chunker: DocumentChunker = config.chunkingStrategy match {
      case ChunkerFactory.Strategy.Semantic =>
        ChunkerFactory.semantic(embeddingClient, embeddingModelConfig)
      case other =>
        ChunkerFactory.create(other)
    }

    val searcher = HybridSearcher(vectorStore, keywordIndex, config.fusionStrategy)

    new RAGPipeline(
      config = config,
      llmClient = llmClient,
      embeddingClient = embeddingClient,
      embeddingModelConfig = embeddingModelConfig,
      hybridSearcher = searcher,
      chunker = chunker,
      tracer = tracer
    )
  }

  /**
   * Create an embedding client for a specific embedding config.
   *
   * Reads API keys from configuration (environment or application.conf):
   * - OpenAI: OPENAI_API_KEY
   * - Voyage: VOYAGE_API_KEY
   * - Ollama: No API key required
   *
   * @param config Embedding configuration
   * @return Embedding client or error
   */
  def createEmbeddingClient(
    config: EmbeddingConfig,
    resolveEmbeddingProvider: String => Result[EmbeddingProviderConfig]
  ): Result[EmbeddingClient] =
    resolveEmbeddingProvider(config.provider).flatMap { baseConfig =>
      val providerConfig = config match {
        case EmbeddingConfig.OpenAI(model, _) =>
          baseConfig.copy(model = model)
        case EmbeddingConfig.Voyage(model, _) =>
          baseConfig.copy(model = model)
        case EmbeddingConfig.Ollama(model, _, baseUrl) =>
          baseConfig.copy(model = model, baseUrl = baseUrl)
      }

      EmbeddingClient.from(config.provider, providerConfig)
    }
}
