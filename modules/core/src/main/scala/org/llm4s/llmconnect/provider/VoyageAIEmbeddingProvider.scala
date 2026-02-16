package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model._
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Arr, Obj, read }

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.Try
import scala.util.control.NonFatal

object VoyageAIEmbeddingProvider {

  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider = new EmbeddingProvider {
    private val httpClient = HttpClient.newHttpClient()
    private val logger     = LoggerFactory.getLogger(getClass)

    override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
      val model = request.model.name
      val input = request.input
      val payload = Obj(
        "input" -> Arr.from(input),
        "model" -> model
      )

      val url = s"${cfg.baseUrl}/v1/embeddings"
      logger.debug(s"[VoyageAIEmbeddingProvider] POST $url model=$model inputs=${input.size}")

      val httpRequest = HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .header("Authorization", s"Bearer ${cfg.apiKey}")
        .header("Content-Type", "application/json")
        .timeout(Duration.ofMinutes(2))
        .POST(HttpRequest.BodyPublishers.ofString(payload.render()))
        .build()

      val respEither: Either[EmbeddingError, HttpResponse[String]] =
        try Right(httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
        catch {
          case e: InterruptedException =>
            Thread.currentThread().interrupt()
            Left(
              EmbeddingError(code = None, message = s"HTTP request interrupted: ${e.getMessage}", provider = "voyage")
            )
          case NonFatal(e) =>
            Left(EmbeddingError(code = None, message = s"HTTP request failed: ${e.getMessage}", provider = "voyage"))
        }

      respEither.flatMap { response =>
        response.statusCode() match {
          case 200 =>
            Try {
              val json     = read(response.body())
              val vectors  = json("data").arr.map(r => r("embedding").arr.map(_.num).toVector).toSeq
              val metadata = Map("provider" -> "voyage", "model" -> model, "count" -> input.size.toString)
              EmbeddingResponse(embeddings = vectors, metadata = metadata)
            }.toEither.left
              .map { ex =>
                logger.error(s"[VoyageAIEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "voyage")
              }
          case status =>
            val body = Redaction.truncateForLog(response.body())
            logger.error(s"[VoyageAIEmbeddingProvider] HTTP error: $body")
            Left(EmbeddingError(code = Some(status.toString), message = body, provider = "voyage"))
        }
      }
    }
  }
}
