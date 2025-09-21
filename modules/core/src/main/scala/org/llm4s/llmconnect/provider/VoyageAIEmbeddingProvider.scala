package org.llm4s.llmconnect.provider

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.{ EmbeddingConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory
import sttp.client4._
import ujson.{ Arr, Obj, read }

import scala.util.Try

class VoyageAIEmbeddingProvider(config: ConfigReader) extends EmbeddingProvider {

  private val backend = DefaultSyncBackend()
  private val logger  = LoggerFactory.getLogger(getClass)

  override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
    val model = request.model.name
    val input = request.input

    // Lazily read provider config; surface missing envs as a clean EmbeddingError
    val cfgEither: Either[EmbeddingError, EmbeddingProviderConfig] =
      Try(EmbeddingConfig.voyage(config)).toEither.left
        .map(e =>
          EmbeddingError(
            code = Some("400"),
            message = s"Missing Voyage configuration: ${e.getMessage}",
            provider = "voyage"
          )
        )

    cfgEither.flatMap { cfg =>
      val payload = Obj(
        "input" -> Arr.from(input),
        "model" -> model
      )

      val url = uri"${cfg.baseUrl}/v1/embeddings"

      logger.debug(s"[VoyageAIEmbeddingProvider] POST $url model=$model inputs=${input.size}")

      val respEither: Either[EmbeddingError, Response[Either[String, String]]] =
        Try(
          basicRequest
            .post(url)
            .header("Authorization", s"Bearer ${cfg.apiKey}")
            .header("Content-Type", "application/json")
            .body(payload.render())
            .send(backend)
        ).toEither.left
          .map(e =>
            EmbeddingError(code = Some("502"), message = s"HTTP request failed: ${e.getMessage}", provider = "voyage")
          )

      respEither.flatMap { response =>
        response.body match {
          case Right(body) =>
            Try {
              val json     = read(body)
              val vectors  = json("data").arr.map(r => r("embedding").arr.map(_.num).toVector).toSeq
              val metadata = Map("provider" -> "voyage", "model" -> model, "count" -> input.size.toString)
              EmbeddingResponse(embeddings = vectors, metadata = metadata)
            }.toEither.left
              .map { ex =>
                logger.error(s"[VoyageAIEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = Some("502"), message = s"Parsing error: ${ex.getMessage}", provider = "voyage")
              }

          case Left(errorMsg) =>
            logger.error(s"[VoyageAIEmbeddingProvider] HTTP error: $errorMsg")
            Left(
              EmbeddingError(
                code = Some("502"),
                message = errorMsg,
                provider = "voyage"
              )
            )
        }
      }
    }
  }
}

object VoyageAIEmbeddingProvider {
  def apply(config: ConfigReader): VoyageAIEmbeddingProvider = new VoyageAIEmbeddingProvider(config)
}
