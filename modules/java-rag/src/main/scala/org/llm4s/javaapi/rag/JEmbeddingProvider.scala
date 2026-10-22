package org.llm4s.javaapi.rag

import org.llm4s.javaapi.{ JEmbeddingClient, JEmbeddingPurpose }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, InputPurpose }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result

import scala.jdk.CollectionConverters.*

/**
 * Not part of the API: a caller's [[org.llm4s.javaapi.JEmbeddingClient JEmbeddingClient]] as the embedding provider of
 * `llm4s-rag`'s pipeline, through its public `embed` alone - `llm4s-java-api` keeps the core client it wraps to itself.
 * Every request is the client's own `embed` call, so the pipeline embeds with the client's model, purpose by purpose,
 * and reads a failure, an interrupt or a malformed reply as the client does.
 */
final private[rag] class JEmbeddingProvider(client: JEmbeddingClient) extends EmbeddingProvider {

  /** The model the client asks for, as the pipeline records it. */
  val model: EmbeddingModelConfig = EmbeddingModelConfig(client.model, client.dimensions.orElse(0))

  override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
    val purpose = request.purpose match {
      case InputPurpose.Query => JEmbeddingPurpose.QUERY
      case _                  => JEmbeddingPurpose.DOCUMENT
    }
    val result = client.embed(java.util.List.copyOf(request.input.asJava), purpose)
    if (result.isFailure) Left(result.getError().error)
    else {
      val embeddings = result.get()
      Right(
        EmbeddingResponse(
          embeddings = embeddings.vectors.asScala.toSeq.map(_.toSeq.map(_.toDouble)),
          metadata = Map("model" -> embeddings.model),
          dim = Some(embeddings.dimensions)
        )
      )
    }
  }
}
