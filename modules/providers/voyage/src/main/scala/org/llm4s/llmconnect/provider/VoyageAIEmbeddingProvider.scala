package org.llm4s.llmconnect.provider

import org.llm4s.config.VoyageConfigKeys
import org.llm4s.error.CancelledError
import org.llm4s.http.{ HttpResponse => Llm4sHttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.llmconnect.model._
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Arr, Obj }

import scala.concurrent.duration.*
import scala.util.Try

/**
 * Embedding provider implementation for the Voyage AI embedding API.
 *
 * Generates text embeddings by posting batched input to the Voyage AI
 * `<baseUrl>/embeddings` endpoint (the default base URL is `https://api.voyageai.com/v1`). Unlike Ollama, Voyage accepts multiple inputs
 * in a single request, so all texts are sent in one HTTP call.
 *
 * Requires a valid Voyage AI API key in the provider configuration.
 *
 * Voyage's entry in the embedding half of the provider SPI.
 *
 * Voyage is the case that shapes the SPI: it supplies embeddings and no chat
 * client at all, which is why [[org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor]]
 * is a separate trait rather than a method on the chat descriptor.
 *
 * It lives in `llm4s-voyage` and is registered by [[Llm4sVoyageModule]]; it was built into
 * `llm4s-core` until [[https://github.com/llm4s/llm4s/issues/1132 #1132]], package unchanged.
 *
 * @see [[EmbeddingProvider]] for the common embedding interface
 */
object VoyageAIEmbeddingProvider extends EmbeddingProviderDescriptor {

  val id: ProviderId = ProviderId("voyage")

  override val aliases: Set[String] = Set("voyageai")

  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://api.voyageai.com/v1"),
    // Bound to the shared llm4s.credentials.voyage.apiKey in this module's reference.conf.
    apiKeyEnv = Seq(VoyageConfigKeys.VOYAGE_API_KEY),
    modelEnv = Some(VoyageConfigKeys.VOYAGE_EMBEDDING_MODEL)
  )

  /**
   * Default output dimensions. Several of these models accept an
   * `output_dimension` request parameter; the client does not send one, so the
   * default is what it gets.
   */
  override val modelDimensions: Map[String, Int] = Map(
    "voyage-2"         -> 1024,
    "voyage-3"         -> 1024,
    "voyage-3-lite"    -> 512,
    "voyage-3-large"   -> 1024,
    "voyage-3.5"       -> 1024,
    "voyage-3.5-lite"  -> 1024,
    "voyage-code-2"    -> 1536,
    "voyage-code-3"    -> 1024,
    "voyage-finance-2" -> 1024,
    "voyage-law-2"     -> 1024,
    "voyage-context-3" -> 1024
  )

  /**
   * The `input_type` Voyage is sent for a request's purpose. Voyage's models are trained to embed a query and a
   * document differently, and prepend a different prompt for each, so the request says which it is.
   */
  private[provider] def inputTypeFor(purpose: InputPurpose): String = purpose match {
    case InputPurpose.Document => "document"
    case InputPurpose.Query    => "query"
  }

  /** Builds the provider for the SPI; see [[fromConfig]] for the direct route. */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(fromConfig(config))

  /** Creates an [[EmbeddingProvider]] backed by Voyage AI using the given configuration. */
  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider =
    create(cfg, Llm4sHttpClient.create())

  private[provider] def forTest(cfg: EmbeddingProviderConfig, httpClient: Llm4sHttpClient): EmbeddingProvider =
    create(cfg, httpClient)

  private def create(cfg: EmbeddingProviderConfig, httpClient: Llm4sHttpClient): EmbeddingProvider =
    new EmbeddingProvider {
      private val logger = LoggerFactory.getLogger(getClass)

      override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
        CancelledError.attempt("voyage.embed") {
          val model = request.model.name
          val input = request.input
          val payload = Obj(
            "input"      -> Arr.from(input),
            "model"      -> model,
            "input_type" -> inputTypeFor(request.purpose)
          )

          val url = s"${cfg.baseUrl.stripSuffix("/")}/embeddings"
          logger.debug(s"[VoyageAIEmbeddingProvider] POST $url model=$model inputs=${input.size}")

          val headers = Map(
            "Authorization" -> s"Bearer ${cfg.apiKey}",
            "Content-Type"  -> "application/json"
          )

          // A cancellation is not a failed request: it passes through, as the contract in
          // docs/design/typed-agent-runtime-design.md section 4.4 requires.
          val respEither: Result[Llm4sHttpResponse] =
            httpClient.post(url, headers, payload.render(), timeout = cfg.timeouts.requestOr(120.seconds)).left.map {
              case cancelled: CancelledError => cancelled
              case err =>
                EmbeddingError(code = None, message = s"HTTP request failed: ${err.message}", provider = "voyage")
            }

          respEither.flatMap { response =>
            response.statusCode match {
              case 200 =>
                Try {
                  val json    = ujson.read(response.body)
                  val vectors = json("data").arr.map(r => r("embedding").arr.map(_.num).toVector).toSeq
                  val metadata =
                    Map("provider" -> "voyage", "model" -> model, "count" -> input.size.toString)
                  EmbeddingResponse(embeddings = vectors, metadata = metadata)
                }.toEither.left
                  .map { ex =>
                    logger.error(s"[VoyageAIEmbeddingProvider] Parse error: ${ex.getMessage}")
                    EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "voyage")
                  }
              case status =>
                val body = Redaction.safeBody(response.body)
                logger.error(s"[VoyageAIEmbeddingProvider] HTTP error: $body")
                Left(EmbeddingError(code = Some(status.toString), message = body, provider = "voyage"))
            }
          }
        }
    }
}
