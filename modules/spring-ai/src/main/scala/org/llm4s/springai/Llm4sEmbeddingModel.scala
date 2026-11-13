package org.llm4s.springai

import org.llm4s.annotation.Experimental
import org.llm4s.javaapi.{ JEmbeddingClient, Llm4s }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.{
  Embedding,
  EmbeddingModel,
  EmbeddingRequest,
  EmbeddingResponse,
  EmbeddingResponseMetadata
}

import java.util.Objects
import scala.jdk.CollectionConverters.*

/**
 * An llm4s embedding client as a Spring AI `EmbeddingModel` (#2278), so a Spring AI vector store or evaluator embeds
 * through any llm4s embedding provider.
 *
 * {{{
 * EmbeddingModel model = new Llm4sEmbeddingModel(Llm4s.createEmbeddingClient(config).get());
 * float[] vector = model.embed("hello");
 * }}}
 *
 * The request's texts are sent in one llm4s request, as documents; its options are not read (the model is the client's
 * own). The response's metadata names the model that answered. A failure throws the
 * [[org.llm4s.javaapi.LlmException LlmException]] the client's result holds. `dimensions()` is the model's declared
 * length when its provider module lists it, and otherwise Spring AI's default, which embeds a test string.
 */
@Experimental
final class Llm4sEmbeddingModel(client: JEmbeddingClient) extends EmbeddingModel {

  Objects.requireNonNull(client, "client must not be null")

  override def call(request: EmbeddingRequest): EmbeddingResponse = {
    val embedded = client.embed(request.getInstructions).get()
    val vectors  = embedded.vectors.asScala.toList.zipWithIndex.map((v, i) => new Embedding(v, Integer.valueOf(i)))
    val metadata = new EmbeddingResponseMetadata()
    metadata.setModel(embedded.model)
    new EmbeddingResponse(vectors.asJava, metadata)
  }

  override def embed(document: Document): Array[Float] = embed(getEmbeddingContent(document))

  /** A declared length of 0 is none, as the facade reads it. */
  override def dimensions(): Int = {
    val declared = client.dimensions
    if (declared.isPresent && declared.getAsInt > 0) declared.getAsInt else super[EmbeddingModel].dimensions()
  }
}

@Experimental
object Llm4sEmbeddingModel {

  /** The model over a Scala `EmbeddingClient` that asks for `model`. */
  def of(client: EmbeddingClient, model: EmbeddingModelConfig): Llm4sEmbeddingModel =
    new Llm4sEmbeddingModel(Llm4s.wrapEmbeddingClient(client, model))
}
