package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.{EmbeddingClient, LLMClient}
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}
import java.time.Instant
import scala.jdk.CollectionConverters._
import org.slf4j.LoggerFactory

case class CacheEntry(
  embedding: Seq[Double],
  response: Completion,
  timestamp: Instant
)

class CachingLLMClient(
  baseClient: LLMClient,
  embeddingClient: EmbeddingClient,
  embeddingModel: EmbeddingModelConfig,
  config: CacheConfig
) extends LLMClient {

  private val logger = LoggerFactory.getLogger(getClass)
  private val cache: ConcurrentMap[String, CacheEntry] = new ConcurrentHashMap()

  override def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] = {
    val promptText = conversation.lastMessage match {
      case Some(UserMessage(content)) => content
      case _ => conversation.messages.map(_.content).mkString("\n")
    }

    val embeddingReq = EmbeddingRequest(
      input = Seq(promptText),
      model = embeddingModel
    )

    embeddingClient.embed(embeddingReq) match {
      case Right(embeddingResponse) if embeddingResponse.embeddings.nonEmpty =>
        val currentEmbedding = embeddingResponse.embeddings.head

        val now = Instant.now()
        val bestMatch = cache.entrySet().asScala
          .filter(e => e.getValue.timestamp.plusNanos(config.ttl.toNanos).isAfter(now))
          .map { entry =>
            val similarity = CosineSimilarity.calculate(currentEmbedding, entry.getValue.embedding)
            (entry.getKey, entry.getValue, similarity)
          }
          .filter(_._3 >= config.similarityThreshold)
          .reduceOption((a, b) => if (a._3 > b._3) a else b)

        bestMatch match {
          case Some((_, entry, score)) =>
            logger.debug(s"Cache hit! Similarity: $score > ${config.similarityThreshold}")
            Right(entry.response)
          case None =>
            logger.debug("Cache miss. Calling base client.")
            executeAndCache(conversation, options, currentEmbedding)
        }

      case Right(_) =>
        logger.warn("Embedding response contained no embeddings. Skipping cache.")
        baseClient.complete(conversation, options)
        
      case Left(error) =>
        logger.warn(s"Failed to generate embedding: $error. Skipping cache.")
        baseClient.complete(conversation, options)
    }
  }

  private def executeAndCache(
    conversation: Conversation,
    options: CompletionOptions,
    embedding: Seq[Double]
  ): Result[Completion] = {
    baseClient.complete(conversation, options).map { completion =>
      cache.put(java.util.UUID.randomUUID().toString, CacheEntry(embedding, completion, Instant.now()))
      completion
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    baseClient.streamComplete(conversation, options, onChunk)
  }

  override def getContextWindow(): Int = baseClient.getContextWindow()

  override def getReserveCompletion(): Int = baseClient.getReserveCompletion()

  override def validate(): Result[Unit] = baseClient.validate()
  
  override def close(): Unit = baseClient.close()
}
