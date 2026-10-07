package org.llm4s.llmconnect.provider

import org.llm4s.config.JinaConfigKeys
import org.llm4s.error.CancelledError
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
 * is embedding. A request says so with its [[InputPurpose]], and the provider maps it onto
 * [[RetrievalQuery]] or [[RetrievalPassage]] by itself. A task the purpose cannot express
 * ([[TextMatching]], [[Classification]], [[Separation]]) is a typed setting of the provider, not
 * part of the model name: pass it to [[JinaEmbeddingProvider.fromConfig]], where it takes
 * precedence over the purpose of every request.
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
  /** The task for a document, [[InputPurpose.Document]]: what a provider built without an explicit task sends. */
  val default: JinaTask = RetrievalPassage

  /** The task a request's [[InputPurpose]] stands for: [[RetrievalPassage]] for a document, [[RetrievalQuery]] for a query. */
  def forPurpose(purpose: InputPurpose): JinaTask = purpose match
    case InputPurpose.Document => RetrievalPassage
    case InputPurpose.Query    => RetrievalQuery

/**
 * Embedding provider implementation for the Jina AI embedding API.
 *
 * Generates text embeddings by posting batched input to Jina's `<baseUrl>/embeddings`
 * endpoint (the default base URL is `https://api.jina.ai/v1`), with a [[JinaTask]] as the `task`
 * field: the one the provider was built with if there is one, otherwise the one the request's
 * [[InputPurpose]] stands for ([[JinaTask.forPurpose]]). All texts go in one HTTP call.
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

  /** Builds the provider for the SPI: the task follows each request's purpose; see [[fromConfig]] to fix the task. */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(fromConfig(config))

  /** Creates an [[EmbeddingProvider]] backed by Jina AI whose `task` follows each request's [[InputPurpose]]. */
  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider =
    create(cfg, None, Llm4sHttpClient.create())

  /**
   * Creates an [[EmbeddingProvider]] backed by Jina AI that sends `task` with every request, whatever the
   * request's [[InputPurpose]]. An explicit task wins over the purpose: it is a deliberate choice, and it can be
   * one the purpose cannot express, such as [[JinaTask.TextMatching]].
   */
  def fromConfig(cfg: EmbeddingProviderConfig, task: JinaTask): EmbeddingProvider =
    create(cfg, Some(task), Llm4sHttpClient.create())

  private[provider] def forTest(cfg: EmbeddingProviderConfig, httpClient: Llm4sHttpClient): EmbeddingProvider =
    create(cfg, None, httpClient)

  private[provider] def forTest(
    cfg: EmbeddingProviderConfig,
    httpClient: Llm4sHttpClient,
    task: JinaTask
  ): EmbeddingProvider =
    create(cfg, Some(task), httpClient)

  /** An error body for logs and messages: truncated, with anything key-shaped (a reflected `Bearer ...`) redacted. */
  private def safeBody(body: String): String = Redaction.truncateForLog(Redaction.redact(body))

  /**
   * The response vectors in input order. Jina tags each item with the `index` of the input it
   * answers and does not promise the array is sorted, so position in the array is not trusted
   * when indices are present. A count or index set that does not match the inputs is an error:
   * a short or duplicated result would otherwise pair vectors with the wrong texts.
   */
  private def alignedVectors(items: Seq[ujson.Value], expected: Int): Seq[Vector[Double]] = {
    if (items.size != expected)
      throw new IllegalStateException(s"expected $expected embeddings for $expected inputs but received ${items.size}")
    val indices = items.map(_.obj.get("index").map(_.num.toInt))
    val ordered =
      if (indices.forall(_.isEmpty)) items
      else if (indices.forall(_.isDefined) && indices.flatten.sorted == (0 until expected))
        items.sortBy(_("index").num)
      else
        throw new IllegalStateException(s"response indices are not 0 until $expected: ${indices.flatten.mkString(",")}")
    ordered.map(r => r("embedding").arr.map(_.num).toVector)
  }

  /**
   * The `task` to send for `model`, per Jina's published request schemas (api.jina.ai/openapi.json):
   * `jina-embeddings-v2-*` has no `task` field, so it is omitted; `jina-clip-v2` accepts only
   * `retrieval.query` ("Leave unset for documents"); `jina-embeddings-v4` accepts retrieval,
   * text-matching and code tasks but not `classification` or `separation`, which is a local error
   * rather than a request the API would reject; `jina-embeddings-v3` and unknown models take any.
   */
  private[provider] def taskFor(model: String, task: JinaTask): Either[EmbeddingError, Option[JinaTask]] =
    if (model.startsWith("jina-embeddings-v2")) Right(None)
    else if (model == "jina-clip-v2") Right(Some(task).filter(_ == JinaTask.RetrievalQuery))
    else if (model == "jina-embeddings-v4" && (task == JinaTask.Classification || task == JinaTask.Separation))
      Left(
        EmbeddingError(
          code = None,
          message = s"Jina task '${task.wireName}' is not supported by $model " +
            "(supported: retrieval.query, retrieval.passage, text-matching)",
          provider = "jina"
        )
      )
    else Right(Some(task))

  private def create(
    cfg: EmbeddingProviderConfig,
    explicitTask: Option[JinaTask],
    httpClient: Llm4sHttpClient
  ): EmbeddingProvider =
    new EmbeddingProvider {
      private val logger = LoggerFactory.getLogger(getClass)

      override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
        CancelledError.attempt("jina.embed")(
          taskFor(request.model.name, explicitTask.getOrElse(JinaTask.forPurpose(request.purpose))).flatMap(sent =>
            send(request, sent)
          )
        )

      private def send(
        request: EmbeddingRequest,
        sentTask: Option[JinaTask]
      ): Result[EmbeddingResponse] = {
        val model = request.model.name
        val input = request.input
        val payload = Obj(
          "input" -> Arr.from(input),
          "model" -> model
        )
        sentTask.foreach(t => payload("task") = t.wireName)

        val url = s"${cfg.baseUrl.stripSuffix("/")}/embeddings"
        logger.debug(
          s"[JinaEmbeddingProvider] POST $url model=$model task=${sentTask.map(_.wireName).getOrElse("-")} inputs=${input.size}"
        )

        val headers = Map(
          "Authorization" -> s"Bearer ${cfg.apiKey}",
          "Content-Type"  -> "application/json"
        )

        // A cancellation is not a failed request: it passes through (design section 4.4).
        val respEither: Result[Llm4sHttpResponse] =
          httpClient.post(url, headers, payload.render(), timeout = 120.seconds).left.map {
            case cancelled: CancelledError => cancelled
            case err =>
              EmbeddingError(code = None, message = s"HTTP request failed: ${err.message}", provider = "jina")
          }

        respEither.flatMap { response =>
          response.statusCode match {
            case 200 =>
              Try {
                val json    = ujson.read(response.body)
                val vectors = alignedVectors(json("data").arr.toSeq, input.size)
                val metadata = Map(
                  "provider" -> "jina",
                  "model"    -> model,
                  "count"    -> input.size.toString
                ) ++ sentTask.map(t => "task" -> t.wireName)
                EmbeddingResponse(embeddings = vectors, metadata = metadata)
              }.toEither.left
                .map { ex =>
                  logger.error(s"[JinaEmbeddingProvider] Parse error: ${ex.getMessage}")
                  EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "jina")
                }
            case 401 =>
              val body = safeBody(response.body)
              logger.error(s"[JinaEmbeddingProvider] Auth error (401): $body")
              Left(EmbeddingError(code = Some("401"), message = s"Authentication failed: $body", provider = "jina"))
            case 429 =>
              val body = safeBody(response.body)
              logger.warn(s"[JinaEmbeddingProvider] Rate limit (429): $body")
              Left(EmbeddingError(code = Some("429"), message = s"Rate limit exceeded: $body", provider = "jina"))
            case status =>
              val body = safeBody(response.body)
              logger.error(s"[JinaEmbeddingProvider] HTTP error $status: $body")
              Left(EmbeddingError(code = Some(status.toString), message = body, provider = "jina"))
          }
        }
      }
    }
}
