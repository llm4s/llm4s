package org.llm4s.llmconnect.provider

import sttp.client4._
import ujson._
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.model._

object VoyageAIEmbeddingProvider extends EmbeddingProvider {
  override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
    val cfg = EmbeddingConfig.voyage
    val backend = DefaultSyncBackend()

    val response = basicRequest
      .post(uri"${cfg.baseUrl}/embeddings")
      .header("Authorization", s"Bearer ${cfg.apiKey}")
      .header("Content-Type", "application/json")
      .body(ujson.Obj("input" -> ujson.Arr.from(request.input), "model" -> cfg.model).render())
      .send(backend)

    response.body match {
      case Right(body) =>
        val json = ujson.read(body)
        val vectors = json("data").arr.map { record =>
          record("embedding").arr.map(_.num.toDouble).toVector
        }.toSeq

        Right(EmbeddingResponse(vectors))

      case Left(error) =>
        Left(EmbeddingError(
          code = None,
          message = error,
          provider = "voyage"
        ))

    }
  }
}