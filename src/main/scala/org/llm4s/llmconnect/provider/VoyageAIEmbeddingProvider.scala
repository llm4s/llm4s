package org.llm4s.llmconnect.provider

import sttp.client4._
import ujson.{ Obj, Arr, read }
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.LoggerUtils

object VoyageAIEmbeddingProvider extends EmbeddingProvider {

  private val backend = DefaultSyncBackend()

  override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
    val cfg   = EmbeddingConfig.voyage
    val model = request.model.name
    val input = request.input

    val payload = Obj(
      "input" -> Arr.from(input),
      "model" -> model
    )

    val url = uri"${cfg.baseUrl}/v1/embeddings"

    LoggerUtils.info(
      s"[VoyageAIEmbeddingProvider] Sending embedding request to $url with model=$model and ${input.size} input(s)"
    )

    val response = basicRequest
      .post(url)
      .header("Authorization", s"Bearer ${cfg.apiKey}")
      .header("Content-Type", "application/json")
      .body(payload.render())
      .send(backend)

    response.body match {
      case Right(body) =>
        try {
          val json    = read(body)
          val vectors = json("data").arr.map(record => record("embedding").arr.map(_.num.toDouble).toVector).toSeq

          val metadata = Map(
            "provider" -> "voyage",
            "model"    -> model,
            "count"    -> input.size.toString
          )

          LoggerUtils.info(s"[VoyageAIEmbeddingProvider] Successfully received ${vectors.size} embeddings.")
          Right(EmbeddingResponse(embeddings = vectors, metadata = metadata))
        } catch {
          case ex: Exception =>
            LoggerUtils.error(s"[VoyageAIEmbeddingProvider] Failed to parse Voyage AI response: ${ex.getMessage}")
            Left(EmbeddingError(None, s"Parsing error: ${ex.getMessage}", "voyage"))
        }

      case Left(errorMsg) =>
        LoggerUtils.error(s"[VoyageAIEmbeddingProvider] HTTP error from Voyage AI: $errorMsg")
        Left(EmbeddingError(None, errorMsg, "voyage"))
    }
  }
}
