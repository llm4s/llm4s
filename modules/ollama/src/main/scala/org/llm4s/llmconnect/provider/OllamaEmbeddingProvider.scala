package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.error.CancelledError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Obj, read }

import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * Embedding provider implementation for Ollama, a local model inference server.
 *
 * Generates text embeddings by calling the Ollama `/api/embeddings` HTTP endpoint.
 * Each input text is embedded individually (one HTTP request per text) because the
 * Ollama embedding API accepts a single prompt per call. Results are collected and
 * returned as an [[org.llm4s.llmconnect.model.EmbeddingResponse]].
 *
 * No API key is required when Ollama runs locally, though one can be supplied for
 * remote or authenticated deployments.
 *
 * Ollama's entry in the embedding half of the provider SPI.
 *
 * It shares the `ollama` id with [[OllamaProvider]]'s chat client - the two
 * namespaces are separate - so `EMBEDDING_MODEL=ollama/nomic-embed-text` and a
 * named chat section with `provider = "ollama"` name the same provider.
 */
@Stable
object OllamaEmbeddingProvider extends EmbeddingProviderDescriptor {

  val id: ProviderId = ProviderId("ollama")

  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    defaultBaseUrl = Some("http://localhost:11434"),
    defaultModel = Some("nomic-embed-text"),
    // Ollama ignores the field when run locally, but the client still wants a string.
    defaultApiKey = Some("not-required"),
    modelEnv = Some("OLLAMA_EMBEDDING_MODEL")
  )

  /**
   * The embedding models in Ollama's library that are commonly pulled, under
   * their untagged names. Ollama model names carry an optional `:tag`, and
   * different tags of one model can differ in size - `snowflake-arctic-embed:22m`
   * is 384-dimensional, `:335m` is 1024 - so only `:latest`, which is what the
   * untagged name means, is folded onto these.
   */
  override val modelDimensions: Map[String, Int] = Map(
    "nomic-embed-text"  -> 768,
    "mxbai-embed-large" -> 1024,
    "all-minilm"        -> 384
  )

  override def dimensionsOf(model: String): Option[Int] =
    modelDimensions.get(model.stripSuffix(":latest"))

  /** Builds the provider for the SPI; see [[fromConfig]] for the direct route. */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(fromConfig(config))

  /** Creates an [[EmbeddingProvider]] backed by Ollama using the given configuration. */
  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider = new EmbeddingProvider {
    private val httpClient = Llm4sHttpClient.create()
    private val logger     = LoggerFactory.getLogger(getClass)

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      CancelledError.attempt("ollama.embed") {
        val model = request.model.name
        val input = request.input

        // One request per text, stopping at the first failure: a cancelled call must not go on to
        // send the rest of the batch.
        input
          .foldLeft[Result[Vector[Vector[Double]]]](Right(Vector.empty)) { (done, text) =>
            done.flatMap(vectors => embedSingle(cfg, model, text).map(vectors :+ _))
          }
          .map { embeddings =>
            val metadata = Map("provider" -> "ollama", "model" -> model, "count" -> input.size.toString)
            EmbeddingResponse(embeddings = embeddings, metadata = metadata)
          }
      }

    private def embedSingle(
      cfg: EmbeddingProviderConfig,
      model: String,
      text: String
    ): Result[Vector[Double]] = {
      val payload = Obj(
        "model"  -> model,
        "prompt" -> text
      )

      val url = s"${cfg.baseUrl}/api/embeddings"

      logger.debug(s"[OllamaEmbeddingProvider] POST $url model=$model text_length=${text.length}")

      val auth =
        if (cfg.apiKey.nonEmpty && cfg.apiKey != "not-required") Map("Authorization" -> s"Bearer ${cfg.apiKey}")
        else Map.empty
      val headers = Map("Content-Type" -> "application/json") ++ auth

      // The client never throws: a timeout, I/O failure or interruption (flag restored) is a Left.
      // A cancellation passes through as it is (design section 4.4); any other failure is an EmbeddingError.
      val respEither: Result[org.llm4s.http.HttpResponse] =
        httpClient
          .post(url, headers, payload.render(), timeout = cfg.timeouts.requestOr(2.minutes))
          .left
          .map {
            case cancelled: CancelledError => cancelled
            case e =>
              EmbeddingError(code = None, message = s"HTTP request failed: ${e.message}", provider = "ollama")
          }

      respEither.flatMap { response =>
        response.statusCode match {
          case 200 =>
            Try {
              val json   = read(response.body)
              val vector = json("embedding").arr.map(_.num).toVector
              vector
            }.toEither.left
              .map { ex =>
                logger.error(s"[OllamaEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "ollama")
              }
          case status =>
            val body = Redaction.safeBody(response.body)
            logger.error(s"[OllamaEmbeddingProvider] HTTP error: $body")
            Left(
              EmbeddingError(
                code = Some(status.toString),
                message = body,
                provider = "ollama"
              )
            )
        }
      }
    }
  }
}
