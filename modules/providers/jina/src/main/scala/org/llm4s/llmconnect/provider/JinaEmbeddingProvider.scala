package org.llm4s.llmconnect.provider

import org.llm4s.config.JinaConfigKeys
import org.llm4s.http.{ HttpResponse => Llm4sHttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import org.slf4j.LoggerFactory
import ujson.{ Arr, Obj }

import scala.concurrent.duration.*
import scala.util.Try

/**
 * The Jina AI embedding task: which LoRA adapter `jina-embeddings-v3` applies to the input.
 *
 * Jina embeds queries and documents differently, so the right task depends on what the caller
 * is embedding. It is a typed setting of the provider, not part of the model name: pass it to
 * [[JinaEmbeddingProvider.fromConfig]].
 *
 * @param wireName the value sent as `task` in the request body
 */
enum JinaTask(val wireName: String):
  /** Embeds a search query; pair with [[RetrievalPassage]] for the indexed documents. */
  case RetrievalQuery extends JinaTask("retrieval.query")

  /** Embeds a document passage for indexing. */
  case RetrievalPassage extends JinaTask("retrieval.passage")

  /** Symmetric similarity between texts of the same kind. */
  case TextMatching extends JinaTask("text-matching")

  /** Input for a classifier. */
  case Classification extends JinaTask("classification")

  /** Input for clustering or reranking. */
  case Separation extends JinaTask("separation")

object JinaTask:
  /**
   * What the registry-built provider uses: a document, because indexing is the common
   * batch case. A caller embedding queries builds the provider with [[RetrievalQuery]].
   */
  val default: JinaTask = RetrievalPassage

/**
 * Embedding provider implementation for the Jina AI embedding API.
 *
 * Generates text embeddings by posting batched input to Jina's `<baseUrl>/embeddings`
 * endpoint (the default base URL is `https://api.jina.ai/v1`), with the configured
 * [[JinaTask]] as the `task` field. All texts go in one HTTP call.
 *
 * Requires a valid Jina AI API key (`JINA_API_KEY`) in the provider configuration.
 *
 * Jina supplies embeddings and no chat client, so it is an
 * [[org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor]] only, registered by [[Llm4sJinaModule]].
 *
 * @see [[EmbeddingProvider]] for the common embedding interface
 */
object JinaEmbeddingProvider extends EmbeddingProviderDescriptor {

  val id: ProviderId = ProviderId("jina")

  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://api.jina.ai/v1"),
    // Bound to the shared llm4s.credentials.jina.apiKey in this module's reference.conf.
    apiKeyEnv = Seq(JinaConfigKeys.JINA_API_KEY),
    modelEnv = Some(JinaConfigKeys.JINA_EMBEDDING_MODEL)
  )

  /** Default output dimensions; the client does not send a `dimensions` parameter. */
  override val modelDimensions: Map[String, Int] = Map(
    "jina-embeddings-v3"           -> 1024,
    "jina-embeddings-v4"           -> 2048,
    "jina-clip-v2"                 -> 1024,
    "jina-embeddings-v2-base-en"   -> 768,
    "jina-embeddings-v2-base-code" -> 768
  )

  /** Builds the provider for the SPI, with [[JinaTask.default]]; see [[fromConfig]] to choose the task. */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(fromConfig(config))

  /** Creates an [[EmbeddingProvider]] backed by Jina AI, sending `task` with every request. */
  def fromConfig(cfg: EmbeddingProviderConfig, task: JinaTask = JinaTask.default): EmbeddingProvider =
    create(cfg, task, Llm4sHttpClient.create())

  private[provider] def forTest(
    cfg: EmbeddingProviderConfig,
    httpClient: Llm4sHttpClient,
    task: JinaTask = JinaTask.default
  ): EmbeddingProvider =
    create(cfg, task, httpClient)

  private def create(cfg: EmbeddingProviderConfig, task: JinaTask, httpClient: Llm4sHttpClient): EmbeddingProvider =
    new EmbeddingProvider {
      private val logger = LoggerFactory.getLogger(getClass)

      override def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
        val model = request.model.name
        val input = request.input
        val payload = Obj(
          "input" -> Arr.from(input),
          "model" -> model,
          "task"  -> task.wireName
        )

        val url = s"${cfg.baseUrl.stripSuffix("/")}/embeddings"
        logger.debug(s"[JinaEmbeddingProvider] POST $url model=$model task=${task.wireName} inputs=${input.size}")

        val headers = Map(
          "Authorization" -> s"Bearer ${cfg.apiKey}",
          "Content-Type"  -> "application/json"
        )

        val respEither: Either[EmbeddingError, Llm4sHttpResponse] =
          httpClient.post(url, headers, payload.render(), timeout = 120.seconds).left.map { err =>
            EmbeddingError(code = None, message = s"HTTP request failed: ${err.message}", provider = "jina")
          }

        respEither.flatMap { response =>
          response.statusCode match {
            case 200 =>
              Try {
                val json    = ujson.read(response.body)
                val vectors = json("data").arr.map(r => r("embedding").arr.map(_.num).toVector).toSeq
                val metadata = Map(
                  "provider" -> "jina",
                  "model"    -> model,
                  "task"     -> task.wireName,
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
}
