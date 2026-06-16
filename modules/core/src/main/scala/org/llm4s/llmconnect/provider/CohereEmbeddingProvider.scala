package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse => Llm4sHttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model._
import org.llm4s.error.{ ConfigurationError, RateLimitError }
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Arr, Obj, read }

import scala.util.Try
import scala.util.control.NonFatal

/**
 * Cohere embedding provider implementation (v2 embed endpoint).
 *
 * Supports `embed-english-v3.0` and `embed-multilingual-v3.0` model families.
 * Sends `model`, `texts` and `input_type` fields as required by Cohere.
 */
object CohereEmbeddingProvider {

  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider =
    create(cfg, Llm4sHttpClient.create())

  private[provider] def forTest(cfg: EmbeddingProviderConfig, httpClient: Llm4sHttpClient): EmbeddingProvider =
    create(cfg, httpClient)

  private def create(cfg: EmbeddingProviderConfig, httpClient: Llm4sHttpClient): EmbeddingProvider =
    new EmbeddingProvider {
      private val logger = LoggerFactory.getLogger(getClass)

      override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
        val model = request.model.name
        val input = request.input

        // Determine input_type: explicit token in model name > heuristic (single = query)
        val modelLower = model.toLowerCase
        val inputType: String =
          if (modelLower.contains("search_query")) "search_query"
          else if (modelLower.contains("search_document")) "search_document"
          else if (input.size == 1) "search_query" // likely a query
          else "search_document"                   // likely indexing / documents

        val payload = Obj(
          "model"      -> model,
          "texts"      -> Arr.from(input),
          "input_type" -> inputType
        )

        val url = s"${cfg.baseUrl}/v2/embed"
        logger.debug(s"[CohereEmbeddingProvider] POST $url model=$model inputs=${input.size} input_type=$inputType")

        val headers = Map(
          "Authorization" -> s"Bearer ${cfg.apiKey}",
          "Content-Type"  -> "application/json"
        )

        val respEither: Either[EmbeddingError, Llm4sHttpResponse] =
          try Right(httpClient.post(url, headers, payload.render(), timeout = 120000))
          catch {
            case e: InterruptedException =>
              Thread.currentThread().interrupt()
              Left(
                EmbeddingError(code = None, message = s"HTTP request interrupted: ${e.getMessage}", provider = "cohere")
              )
            case NonFatal(e) =>
              Left(EmbeddingError(code = None, message = s"HTTP request failed: ${e.getMessage}", provider = "cohere"))
          }

        respEither.flatMap { response =>
          response.statusCode match {
            case 200 =>
              Try {
                val json = read(response.body)
                // Cohere returns `embeddings: [{"embedding": [floats]}, ...]` or similar
                val seq = json.obj.get("embeddings").orElse(json.obj.get("data"))
                val vectors: Seq[Vector[Double]] = seq match {
                  case Some(arr) =>
                    arr.arr.map { item =>
                      // prefer object.embedding, fall back to array item
                      item.obj.get("embedding") match {
                        case Some(e) => e.arr.map(_.num).toVector
                        case None =>
                          item.arrOpt match {
                            case Some(a) => a.map(_.num).toVector
                            case None    => Vector.empty[Double]
                          }
                      }
                    }.toSeq
                  case None => Seq.empty
                }

                val metadata = Map("provider" -> "cohere", "model" -> model, "count" -> input.size.toString)
                EmbeddingResponse(embeddings = vectors, metadata = metadata)
              }.toEither.left.map { ex =>
                logger.error(s"[CohereEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "cohere")
              }

            case 401 =>
              Left(
                ConfigurationError(
                  "Unauthorized: check COHERE_API_KEY and llm4s.cohere.apiKey",
                  List("COHERE_API_KEY", "llm4s.cohere.apiKey")
                )
              )

            case 429 =>
              // try to extract Retry-After header (seconds)
              val retryOpt: Option[Long] = response.headers
                .get("retry-after")
                .flatMap(_.headOption)
                .flatMap(s => scala.util.Try(s.toLong).toOption)
              val rateErr = retryOpt.map(r => RateLimitError("cohere", r)).getOrElse(RateLimitError("cohere"))
              Left(rateErr)

            case status =>
              val body = Redaction.truncateForLog(response.body)
              logger.error(s"[CohereEmbeddingProvider] HTTP error: $body")
              Left(EmbeddingError(code = Some(status.toString), message = body, provider = "cohere"))
          }
        }
      }
    }
}
