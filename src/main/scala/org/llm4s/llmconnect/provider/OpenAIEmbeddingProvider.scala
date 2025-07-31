package org.llm4s.llmconnect.provider

import sttp.client4._
import ujson.{ Obj, Arr, read }
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.utils.LoggerUtils

object OpenAIEmbeddingProvider extends EmbeddingProvider {

  private val backend = DefaultSyncBackend()

  override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
    val cfg   = EmbeddingConfig.openAI
    val model = request.model.name
    val input = request.input

    val payload = Obj(
      "input" -> Arr.from(input),
      "model" -> model
    )

    val url = uri"${cfg.baseUrl}/v1/embeddings"

    LoggerUtils.info(
      s"[OpenAIEmbeddingProvider] Sending embedding request to $url with model=$model and ${input.size} input(s)"
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
            "provider" -> "openai",
            "model"    -> model,
            "count"    -> input.size.toString
          )

          LoggerUtils.info(s"[OpenAIEmbeddingProvider] Successfully received ${vectors.size} embeddings.")
          Right(EmbeddingResponse(embeddings = vectors, metadata = metadata))
        } catch {
          case ex: Exception =>
            LoggerUtils.error(s"[OpenAIEmbeddingProvider] Failed to parse OpenAI response: ${ex.getMessage}")
            Left(EmbeddingError(None, s"Parsing error: ${ex.getMessage}", "openai"))
        }

      case Left(errorMsg) =>
        LoggerUtils.error(s"[OpenAIEmbeddingProvider] HTTP error from OpenAI: $errorMsg")
        Left(EmbeddingError(None, errorMsg, "openai"))
    }
  }
}
