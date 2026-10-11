package org.llm4s.javaapi

import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, InputPurpose }
import org.llm4s.types.Result

import scala.jdk.CollectionConverters.*

/**
 * Java-friendly wrapper around core's [[EmbeddingClient]]: turns texts into vectors, for semantic search and
 * similarity, with no Scala type in sight.
 *
 * {{{
 * JEmbeddingClient embedder = Llm4s.createDefaultEmbeddingClient().get();
 * JEmbeddings embeddings = embedder.embed(List.of("The cat sat on the mat.", "A kitten lay on the rug.")).get();
 * List<float[]> vectors = embeddings.vectors();
 * System.out.println(JEmbeddings.cosineSimilarity(vectors.get(0), vectors.get(1)));
 * }}}
 *
 * Obtain one with [[Llm4s.createDefaultEmbeddingClient]], which reads the model from `llm4s.embeddings.model`
 * (`EMBEDDING_MODEL`).
 *
 * Every `embed` sends all its texts to the provider in one request, blocks the calling thread and never throws: a
 * failure - of the provider, the network or an argument - comes back inside the [[LlmResult]], and its
 * `getKind()` says which. An embedding provider's error response reads by its HTTP status, as a chat provider's does:
 * `401` and `403` as `AUTHENTICATION`, `429` as `RATE_LIMIT`, `400` as `VALIDATION`, any other as `SERVICE`.
 * Interrupting the blocked thread returns a failed result whose kind is `CANCELLED`, with the thread's interrupt flag
 * still set; `InterruptedException` is never thrown.
 */
final class JEmbeddingClient private[javaapi] (client: EmbeddingClient, modelConfig: EmbeddingModelConfig) {

  /** The embedding model this client asks for, as configured. */
  def model: String = modelConfig.name

  /** The length of the vectors the model makes, as its provider module declares it. */
  def dimensions: Int = modelConfig.dimensions

  /**
   * Embeds `texts` as documents to be indexed - core's default - returning one vector per text, in order. The same as
   * `embed(texts, JEmbeddingPurpose.DOCUMENT)`.
   */
  def embed(texts: java.util.List[String]): LlmResult[JEmbeddings] = embed(texts, JEmbeddingPurpose.DOCUMENT)

  /**
   * Embeds `texts` for `purpose` - documents to index, or queries to run against them - returning one vector per
   * text, in order, from a single request. An empty list returns no vectors and calls no provider.
   *
   * A `null` list, `null` text or `null` purpose yields a failed result of kind `VALIDATION`, never an exception.
   * Blocks the calling thread; an interrupt yields a failed result of kind `CANCELLED`, with the interrupt flag left
   * set.
   */
  def embed(texts: java.util.List[String], purpose: JEmbeddingPurpose): LlmResult[JEmbeddings] =
    if (texts == null) LlmResult.failure(ValidationError.required("texts"))
    else if (purpose == null) LlmResult.failure(ValidationError.required("purpose"))
    else {
      // copied first, so a list changed by another thread cannot make the check and the request disagree
      val input = texts.asScala.toVector
      input.indexOf(null) match {
        case -1 if input.isEmpty => LlmResult.success(JEmbeddings.of(model, dimensions, Array.empty))
        case -1                  => request(input, purpose)
        case i                   => LlmResult.failure(ValidationError.invalid(s"texts[$i]", "must not be null"))
      }
    }

  private def request(input: Vector[String], purpose: JEmbeddingPurpose): LlmResult[JEmbeddings] = {
    val sent = EmbeddingRequest(input = input, model = modelConfig, purpose = JEmbeddingClient.toCore(purpose))
    LlmResult.from(
      CancelledError.attempt("JEmbeddingClient.embed")(
        Safety.safely(client.embed(sent)).flatMap(identity).flatMap(reply => read(reply, input.size))
      )
    )
  }

  /**
   * The reply as Java reads it, refused when it cannot be read as one vector per text: a provider that returns another
   * count, or vectors of several lengths, would otherwise pair a text with another text's vector.
   */
  private def read(reply: EmbeddingResponse, texts: Int): Result[JEmbeddings] = {
    val vectors = reply.embeddings.iterator.map(_.iterator.map(_.toFloat).toArray).toArray
    val lengths = vectors.map(_.length).distinct
    if (vectors.length != texts)
      Left(ProcessingError("embed", s"the provider returned ${vectors.length} vectors for $texts texts"))
    else if (lengths.length != 1)
      Left(ProcessingError("embed", s"the provider returned vectors of several lengths: ${lengths.mkString(", ")}"))
    else Right(JEmbeddings.of(reply.metadata.get("model").filter(_.nonEmpty).getOrElse(model), lengths(0), vectors))
  }

  override def toString: String = s"JEmbeddingClient($model, $dimensions dimensions)"
}

object JEmbeddingClient {

  private def toCore(purpose: JEmbeddingPurpose): InputPurpose = purpose match {
    case JEmbeddingPurpose.DOCUMENT => InputPurpose.Document
    case JEmbeddingPurpose.QUERY    => InputPurpose.Query
  }
}
