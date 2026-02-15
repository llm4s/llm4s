package org.llm4s.reranker

import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

/**
 * Factory for creating Reranker instances.
 *
 * Supports multiple backends:
 * - Cohere: Cloud-based cross-encoder reranking API
 * - LLM: Use existing LLMClient for reranking with structured prompts
 * - None: Passthrough that preserves original order
 *
 * Usage:
 * {{{
 * // Cohere reranker from explicit config
 * val reranker = RerankerFactory.cohere(config)
 *
 * // LLM-based reranker
 * val llmReranker = RerankerFactory.llm(llmClient)
 *
 * // From provided config
 * val reranker = RerankerFactory.fromConfig(Some(config))
 * }}}
 */
object RerankerFactory {

  /** Reranker backend type */
  sealed trait Backend {
    def name: String
  }

  object Backend {
    case object Cohere extends Backend { val name = "cohere" }
    case object LLM    extends Backend { val name = "llm"    }
    case object None   extends Backend { val name = "none"   }

    def fromString(s: String): Option[Backend] = s.toLowerCase.trim match {
      case "cohere"    => Some(Cohere)
      case "llm"       => Some(LLM)
      case "none" | "" => Some(None)
      case _           => scala.None
    }
  }

  /**
   * Create a Cohere reranker from config.
   *
   * @param config Provider configuration
   * @return Cohere reranker
   */
  def cohere(config: RerankProviderConfig): Reranker =
    CohereReranker(config)

  /**
   * Create a Cohere reranker from individual parameters.
   *
   * @param apiKey Cohere API key
   * @param model Reranking model (default: rerank-english-v3.0)
   * @param baseUrl API base URL (default: https://api.cohere.com)
   * @return Cohere reranker
   */
  def cohere(
    apiKey: String,
    model: String = CohereReranker.DEFAULT_MODEL,
    baseUrl: String = CohereReranker.DEFAULT_BASE_URL
  ): Reranker =
    CohereReranker(apiKey, model, baseUrl)

  /**
   * Create an LLM-based reranker.
   *
   * Uses a language model to score document relevance on a 0-1 scale.
   * This is more flexible than Cohere (works with any LLM) but slower
   * and may be more expensive depending on the model.
   *
   * @param client LLM client for generating scores
   * @param batchSize Number of documents to score per LLM call (default: 10)
   * @param systemPrompt Custom system prompt (optional)
   * @return LLM-based reranker
   */
  def llm(
    client: LLMClient,
    batchSize: Int = LLMReranker.DEFAULT_BATCH_SIZE,
    systemPrompt: Option[String] = None
  ): Reranker =
    LLMReranker(client, batchSize, systemPrompt)

  /**
   * Create a reranker from an optional typed configuration.
   *
   * @param config Optional provider configuration
   * @return Optional reranker (None if disabled)
   */
  def fromConfig(config: Option[RerankProviderConfig]): Result[Option[Reranker]] =
    config match {
      case None      => Right(None)
      case Some(cfg) => Right(Some(cohere(cfg)))
    }

  /**
   * No-op reranker that passes through results unchanged.
   *
   * Useful for testing or when reranking is disabled.
   * Results preserve original order with monotonically decreasing scores.
   */
  val passthrough: Reranker = new Reranker {
    override def rerank(request: RerankRequest): Result[RerankResponse] = {
      val results = request.documents.zipWithIndex.map { case (doc, idx) =>
        // Assign decreasing scores to preserve original order
        RerankResult(index = idx, score = 1.0 - (idx * 0.001), document = doc)
      }

      val topK = request.topK.getOrElse(results.size)

      Right(
        RerankResponse(
          results = results.take(topK),
          metadata = Map("provider" -> "passthrough")
        )
      )
    }
  }
}
