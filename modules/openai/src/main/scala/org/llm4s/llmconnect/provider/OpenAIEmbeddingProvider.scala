package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.OpenAIConfigKeys
import org.llm4s.error.CancelledError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.llmconnect.model._
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Arr, Obj, read }

import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * OpenAI embedding provider implementation.
 *
 * Provides text embeddings using OpenAI's embedding API (text-embedding-3-small,
 * text-embedding-3-large, text-embedding-ada-002). Supports batch embedding of
 * multiple texts in a single request.
 *
 * == Supported Models ==
 *  - `text-embedding-3-small` - Efficient, lower cost (recommended)
 *  - `text-embedding-3-large` - Higher quality, higher cost
 *  - `text-embedding-ada-002` - Legacy model
 *
 * == Token Usage ==
 * The response includes token usage information when available from the API.
 *
 * OpenAI's entry in the embedding half of the provider SPI.
 *
 * The object that builds the provider is also its [[org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor]],
 * so there is one place per provider rather than a descriptor shadowing a factory.
 *
 * @see [[EmbeddingProvider]] for the provider interface
 * @see [[org.llm4s.llmconnect.config.EmbeddingProviderConfig]] for configuration
 */
@Stable
object OpenAIEmbeddingProvider extends EmbeddingProviderDescriptor {

  val id: ProviderId = ProviderId("openai")

  /**
   * The key is `llm4s.embeddings.openai.apiKey` when that is set, and otherwise OpenAI's
   * shared `llm4s.credentials.openai.apiKey`, which this module's `reference.conf` binds to
   * `OPENAI_API_KEY` - the same key the OpenAI chat sections use, so one variable serves both.
   */
  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://api.openai.com/v1"),
    apiKeyEnv = Seq(OpenAIConfigKeys.OPENAI_API_KEY),
    modelEnv = Some(OpenAIConfigKeys.OPENAI_EMBEDDING_MODEL)
  )

  override val modelDimensions: Map[String, Int] = Map(
    "text-embedding-3-small" -> 1536,
    "text-embedding-3-large" -> 3072,
    "text-embedding-ada-002" -> 1536
  )

  /** Builds the provider for the SPI; see [[fromConfig]] for the direct route. */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(fromConfig(config))

  /**
   * Creates an OpenAI embedding provider from configuration.
   *
   * @param cfg embedding provider configuration with API key and base URL
   * @return configured EmbeddingProvider instance
   */
  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider = new EmbeddingProvider {
    private val httpClient = Llm4sHttpClient.create()
    private val logger     = LoggerFactory.getLogger(getClass)

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = CancelledError.attempt("openai.embed") {
      val model = request.model.name
      val input = request.input
      val payload = Obj(
        "input" -> Arr.from(input),
        "model" -> model
      )

      val url = s"${cfg.baseUrl}/v1/embeddings"
      logger.debug(s"[OpenAIEmbeddingProvider] POST $url model=$model inputs=${input.size}")

      val headers = Map("Authorization" -> s"Bearer ${cfg.apiKey}", "Content-Type" -> "application/json")

      // The client never throws: a timeout, I/O failure or interruption (flag restored) is a Left.
      // A cancellation passes through as it is (design section 4.4); any other failure is an EmbeddingError.
      val respEither: Result[org.llm4s.http.HttpResponse] =
        httpClient
          .post(url, headers, payload.render(), timeout = 2.minutes)
          .left
          .map {
            case cancelled: CancelledError => cancelled
            case e =>
              EmbeddingError(code = None, message = s"HTTP request failed: ${e.message}", provider = "openai")
          }

      respEither.flatMap { response =>
        response.statusCode match {
          case 200 =>
            Try {
              val json     = read(response.body)
              val vectors  = json("data").arr.map(r => r("embedding").arr.map(_.num).toVector).toSeq
              val metadata = Map("provider" -> "openai", "model" -> model, "count" -> input.size.toString)

              // Extract token usage if available
              val usage = json.obj.get("usage").flatMap { usageJson =>
                Try {
                  val promptTokens = usageJson("prompt_tokens").num.toInt
                  val totalTokens  = usageJson("total_tokens").num.toInt
                  EmbeddingUsage(promptTokens = promptTokens, totalTokens = totalTokens)
                }.toOption
              }

              EmbeddingResponse(embeddings = vectors, metadata = metadata, usage = usage)
            }.toEither.left
              .map { ex =>
                logger.error(s"[OpenAIEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "openai")
              }
          case status =>
            val body = Redaction.truncateForLog(response.body)
            logger.error(s"[OpenAIEmbeddingProvider] HTTP error: $body")
            Left(EmbeddingError(code = Some(status.toString), message = body, provider = "openai"))
        }
      }
    }
  }
}
