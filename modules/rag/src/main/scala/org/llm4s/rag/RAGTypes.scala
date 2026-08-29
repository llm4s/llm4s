package org.llm4s.rag

import org.llm4s.llmconnect.model.TokenUsage

/**
 * Embedding provider selection for RAG pipeline.
 */
sealed trait EmbeddingProvider {
  def name: String
}

object EmbeddingProvider {
  case object OpenAI extends EmbeddingProvider { val name = "openai" }
  case object Voyage extends EmbeddingProvider { val name = "voyage" }
  case object Ollama extends EmbeddingProvider { val name = "ollama" }

  def fromString(s: String): Option[EmbeddingProvider] = s.toLowerCase match {
    case "openai" => Some(OpenAI)
    case "voyage" => Some(Voyage)
    case "ollama" => Some(Ollama)
    case _        => None
  }

  val values: Seq[EmbeddingProvider] = Seq(OpenAI, Voyage, Ollama)
}

/**
 * Reranking strategy for RAG pipeline.
 */
sealed trait RerankingStrategy

object RerankingStrategy {

  /** No reranking - use fusion scores directly */
  case object None extends RerankingStrategy

  /** Use Cohere's cross-encoder reranking API */
  final case class Cohere(model: String = "rerank-english-v3.0") extends RerankingStrategy

  /** Use LLM as judge for reranking */
  case object LLM extends RerankingStrategy
}

/**
 * Result from RAG search operation.
 *
 * @param id Chunk identifier
 * @param content The text content of the chunk
 * @param score Combined relevance score
 * @param metadata Additional metadata (source, docId, etc.)
 * @param vectorScore Optional vector similarity score
 * @param keywordScore Optional keyword match score
 */
final case class RAGSearchResult(
  id: String,
  content: String,
  score: Double,
  metadata: Map[String, String] = Map.empty,
  vectorScore: Option[Double] = None,
  keywordScore: Option[Double] = None
)

/**
 * Result from RAG answer generation.
 *
 * @param answer The generated answer text
 * @param question The original question
 * @param contexts The chunks used as context for generation
 * @param usage Optional token usage statistics
 */
final case class RAGAnswerResult(
  answer: String,
  question: String,
  contexts: Seq[RAGSearchResult],
  usage: Option[TokenUsage] = None
)

/**
 * RAG pipeline statistics.
 *
 * @param documentCount Number of documents indexed
 * @param chunkCount Number of chunks indexed
 * @param vectorCount Number of vectors in store
 */
final case class RAGStats(
  documentCount: Int,
  chunkCount: Int,
  vectorCount: Long
)
