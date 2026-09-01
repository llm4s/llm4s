// scalafix:off DisableSyntax.NoKeywordCatch
package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse => Llm4sHttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model._
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Arr, Obj }

import scala.util.Try
import scala.util.control.NonFatal

/**
 * Embedding provider implementation for the Jina AI embedding API.
 *
 * Generates text embeddings by posting batched input to the Jina AI
 * `/v1/embeddings` endpoint. Supports task-specific adapters for improved
 * retrieval quality in enterprise RAG pipelines.
 *
 * == Supported Tasks ==
 *  - `retrieval.passage` — encode document passages for indexing
 *  - `retrieval.query`   — encode user queries for search
 *  - `text-matching`     — general-purpose similarity (default)
 *
 * == Supported Models ==
 *  - `jina-embeddings-v3` — 8192-token context, 1024-dimensional, multilingual
 *
 * Requires a valid Jina AI API key (`JINA_API_KEY`) in the provider configuration.
 *
 * @see [[EmbeddingProvider]] for the common embedding interface
 */
object JinaEmbeddingProvider {

  /** Default task type used when no task is supplied in request metadata. */
  val DefaultTask: String = "text-matching"

  /** Metadata key used to pass the task type through [[EmbeddingRequest]]. */
  val TaskMetaKey: String = "jina_task"

  /** Creates an [[EmbeddingProvider]] backed by Jina AI using the given configuration. */
  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider =
    create(cfg, Llm4sHttpClient.create())

  private[provider] def forTest(cfg: EmbeddingProviderConfig, httpClient: Llm4sHttpClient): EmbeddingProvider =
    create(cfg, httpClient)

  private def create(cfg: EmbeddingProviderConfig, httpClient: Llm4sHttpClient): EmbeddingProvider =
    new EmbeddingProvider {
      private val logger = LoggerFactory.getLogger(getClass)

      override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
        val model = request.model.name
        val input = request.input

        // Task comes from model config metadata (stored as extra field) or defaults.
        // EmbeddingRequest only carries input + model; we use a naming convention on
        // the model name ("model:task") so callers can optionally encode the task.
        // Alternatively the task can be appended to the model name as "model::task".
        val task      = extractTask(model)
        val modelName = stripTask(model)

        val payload = buildPayload(input, modelName, task)

        val url = s"${cfg.baseUrl}/v1/embeddings"
        logger.debug(s"[JinaEmbeddingProvider] POST $url model=$modelName task=$task inputs=${input.size}")

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
                EmbeddingError(
                  code = None,
                  message = s"HTTP request interrupted: ${e.getMessage}",
                  provider = "jina"
                )
              )
            case NonFatal(e) =>
              Left(EmbeddingError(code = None, message = s"HTTP request failed: ${e.getMessage}", provider = "jina"))
          }

        respEither.flatMap { response =>
          response.statusCode match {
            case 200 =>
              Try {
                val json    = ujson.read(response.body)
                val vectors = json("data").arr.map(r => r("embedding").arr.map(_.num).toVector).toSeq
                val metadata = Map(
                  "provider" -> "jina",
                  "model"    -> modelName,
                  "task"     -> task,
                  "count"    -> input.size.toString
                )
                EmbeddingResponse(embeddings = vectors, metadata = metadata)
              }.toEither.left
                .map { ex =>
                  logger.error(s"[JinaEmbeddingProvider] Parse error: ${ex.getMessage}")
                  EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "jina")
                }
            case 401 =>
              val body = Redaction.truncateForLog(response.body)
              logger.error(s"[JinaEmbeddingProvider] Auth error (401): $body")
              Left(EmbeddingError(code = Some("401"), message = s"Authentication failed: $body", provider = "jina"))
            case 429 =>
              val body = Redaction.truncateForLog(response.body)
              logger.warn(s"[JinaEmbeddingProvider] Rate limit (429): $body")
              Left(EmbeddingError(code = Some("429"), message = s"Rate limit exceeded: $body", provider = "jina"))
            case status =>
              val body = Redaction.truncateForLog(response.body)
              logger.error(s"[JinaEmbeddingProvider] HTTP error $status: $body")
              Left(EmbeddingError(code = Some(status.toString), message = body, provider = "jina"))
          }
        }
      }
    }

  /**
   * Extract an optional task suffix encoded in the model name.
   *
   * Supports two encoding conventions callers can use:
   *  - `"jina-embeddings-v3::retrieval.passage"` — double-colon separator
   *  - bare `"jina-embeddings-v3"` — uses [[DefaultTask]]
   */
  private[provider] def extractTask(rawModel: String): String =
    rawModel.split("::", 2) match {
      case Array(_, task) if task.nonEmpty => task
      case _                               => DefaultTask
    }

  /** Strip the optional task suffix to obtain the real model name. */
  private[provider] def stripTask(rawModel: String): String =
    rawModel.split("::", 2)(0)

  private def buildPayload(input: Seq[String], model: String, task: String): Obj =
    Obj(
      "input" -> Arr.from(input),
      "model" -> model,
      "task"  -> task
    )
}
