package org.llm4s.llmconnect.provider

import org.llm4s.config.CohereEmbeddingConfigKeys
import org.llm4s.error.{ CancelledError, RateLimitError }
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
 * The Cohere `input_type`: which side of retrieval a text is on.
 *
 * Cohere's v3 and v4 models embed a query and a document differently, so a mismatch between how
 * texts were indexed and how a query is embedded quietly degrades retrieval. A request says which
 * it is with its [[org.llm4s.llmconnect.model.InputPurpose]], and the provider maps it onto [[SearchQuery]] or
 * [[SearchDocument]] by itself, not guessed from the number of inputs or encoded in the model
 * name. An input type the purpose cannot express ([[Classification]], [[Clustering]]) is a typed
 * setting of the provider: pass it to [[CohereEmbeddingProvider.fromConfig]], where it takes
 * precedence over the purpose of every request.
 *
 * @param wireName the value sent as `input_type` in the request body
 */
enum CohereInputType(val wireName: String):
  /** A document to index; pair with [[SearchQuery]] for the queries run against it. */
  case SearchDocument extends CohereInputType("search_document")

  /** A search query against documents embedded as [[SearchDocument]]. */
  case SearchQuery extends CohereInputType("search_query")

  /** Input for a text classifier. */
  case Classification extends CohereInputType("classification")

  /** Input for clustering. */
  case Clustering extends CohereInputType("clustering")

object CohereInputType:
  /** The input type for a document, [[org.llm4s.llmconnect.model.InputPurpose.Document]]: what a provider built without an explicit one sends. */
  val default: CohereInputType = SearchDocument

  /** The input type a request's [[org.llm4s.llmconnect.model.InputPurpose]] stands for: [[SearchDocument]] for a document, [[SearchQuery]] for a query. */
  def forPurpose(purpose: InputPurpose): CohereInputType = purpose match
    case InputPurpose.Document => SearchDocument
    case InputPurpose.Query    => SearchQuery

/**
 * Embedding provider implementation for Cohere's native embed API (`POST <baseUrl>/v2/embed`).
 *
 * This is not an OpenAI-compatible endpoint: it takes `texts`, an `input_type` and the
 * `embedding_types` to return, and answers with the vectors keyed by type
 * (`{"embeddings": {"float": [[...]]}}`). That is why it is a module of its own and not a dialect
 * in `llm4s-openai-compatible`, where Cohere's chat client lives.
 *
 * Texts are sent in requests of at most [[CohereEmbeddingProvider.MaxTextsPerRequest]] (Cohere's
 * limit); the vectors come back in input order and the billed tokens of every request are summed
 * into the response's usage. The first failing request fails the whole call.
 *
 * Requires a Cohere API key (`COHERE_API_KEY`) in the provider configuration. The vectors have
 * the model's default size; no `output_dimension` is sent.
 *
 * Cohere supplies chat through `llm4s-openai-compatible`, so this is an
 * [[org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor]] only, registered by [[Llm4sCohereModule]].
 *
 * @see [[EmbeddingProvider]] for the common embedding interface
 */
object CohereEmbeddingProvider extends EmbeddingProviderDescriptor {

  val id: ProviderId = ProviderId("cohere")

  /** The most texts Cohere accepts in one `/v2/embed` request. */
  val MaxTextsPerRequest: Int = 96

  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://api.cohere.com"),
    // Bound to the shared llm4s.credentials.cohere.apiKey in this module's reference.conf.
    apiKeyEnv = Seq(CohereEmbeddingConfigKeys.COHERE_API_KEY),
    modelEnv = Some(CohereEmbeddingConfigKeys.COHERE_EMBEDDING_MODEL)
  )

  /** Default output dimensions; the client does not send an `output_dimension` parameter. */
  override val modelDimensions: Map[String, Int] = Map(
    "embed-v4.0"                    -> 1536,
    "embed-english-v3.0"            -> 1024,
    "embed-multilingual-v3.0"       -> 1024,
    "embed-english-light-v3.0"      -> 384,
    "embed-multilingual-light-v3.0" -> 384
  )

  /** Builds the provider for the SPI: the input type follows each request's purpose; see [[fromConfig]] to fix it. */
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = Right(fromConfig(config))

  /** Creates an [[EmbeddingProvider]] backed by Cohere whose `input_type` follows each request's [[org.llm4s.llmconnect.model.InputPurpose]]. */
  def fromConfig(cfg: EmbeddingProviderConfig): EmbeddingProvider =
    create(cfg, None, Llm4sHttpClient.create())

  /**
   * Creates an [[EmbeddingProvider]] backed by Cohere that sends `inputType` with every request, whatever the
   * request's [[org.llm4s.llmconnect.model.InputPurpose]]. An explicit input type wins over the purpose: it is a deliberate choice, and it can
   * be one the purpose cannot express, such as [[CohereInputType.Classification]].
   */
  def fromConfig(cfg: EmbeddingProviderConfig, inputType: CohereInputType): EmbeddingProvider =
    create(cfg, Some(inputType), Llm4sHttpClient.create())

  private[provider] def forTest(cfg: EmbeddingProviderConfig, httpClient: Llm4sHttpClient): EmbeddingProvider =
    create(cfg, None, httpClient)

  private[provider] def forTest(
    cfg: EmbeddingProviderConfig,
    httpClient: Llm4sHttpClient,
    inputType: CohereInputType
  ): EmbeddingProvider =
    create(cfg, Some(inputType), httpClient)

  /**
   * The embed endpoint for a configured base URL. The API root is what is configured
   * (`https://api.cohere.com`), but a base that already names the version (`.../v2`) or the whole
   * endpoint (`.../v2/embed`) is accepted rather than doubled up into a 404.
   */
  private[provider] def endpoint(baseUrl: String): String = {
    val base = baseUrl.stripSuffix("/")
    if (base.endsWith("/v2/embed")) base
    else if (base.endsWith("/v2")) s"$base/embed"
    else s"$base/v2/embed"
  }

  /** An error body for logs and messages: truncated, with anything key-shaped (a reflected `Bearer ...`) redacted. */
  private def safeBody(body: String): String = Redaction.truncateForLog(Redaction.redact(body))

  /** What one request returned: its vectors, in the order of the texts it carried, and the tokens it billed, if reported. */
  final private case class Batch(vectors: Seq[Vector[Double]], billedTokens: Option[Int])

  /**
   * The `float` vectors of a v2 response. They are keyed by embedding type, and the answer must
   * hold exactly one vector per text sent: a short result would otherwise pair vectors with the
   * wrong texts, so a count that does not match is an error.
   */
  private def parseBatch(body: String, expected: Int): Batch = {
    val json = ujson.read(body)
    val floats = json("embeddings").obj
      .get("float")
      .getOrElse(throw new IllegalStateException("response has no float embeddings"))
      .arr
    if (floats.size != expected)
      throw new IllegalStateException(s"expected $expected embeddings for $expected inputs but received ${floats.size}")
    val billed = json.obj
      .get("meta")
      .flatMap(_.obj.get("billed_units"))
      .flatMap(_.obj.get("input_tokens"))
      .flatMap(n => Try(n.num.toInt).toOption)
    Batch(floats.map(_.arr.map(_.num).toVector).toSeq, billed)
  }

  /** The `Retry-After` of a 429 when it is a positive number of seconds (an HTTP date is ignored). */
  private def retryAfter(response: Llm4sHttpResponse): Option[FiniteDuration] =
    response
      .header("retry-after")
      .flatMap(s => Try(s.trim.toLong).toOption)
      .filter(_ > 0)
      .map(_.seconds)

  private def create(
    cfg: EmbeddingProviderConfig,
    explicitInputType: Option[CohereInputType],
    httpClient: Llm4sHttpClient
  ): EmbeddingProvider =
    new EmbeddingProvider {
      private val logger = LoggerFactory.getLogger(getClass)
      private val url    = endpoint(cfg.baseUrl)

      override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
        CancelledError.attempt("cohere.embed")(embedBatches(request))

      private def embedBatches(request: EmbeddingRequest): Result[EmbeddingResponse] = {
        val model     = request.model.name
        val input     = request.input
        val inputType = explicitInputType.getOrElse(CohereInputType.forPurpose(request.purpose))
        val metadata = Map(
          "provider"   -> "cohere",
          "model"      -> model,
          "count"      -> input.size.toString,
          "input_type" -> inputType.wireName
        )

        // Cohere rejects an empty `texts`; there is nothing to embed, so nothing is sent.
        if (input.isEmpty) Right(EmbeddingResponse(embeddings = Seq.empty, metadata = metadata))
        else {
          val batches = input.grouped(MaxTextsPerRequest).toVector
          batches
            .foldLeft[Result[Vector[Batch]]](Right(Vector.empty))((done, texts) =>
              done.flatMap(d => send(model, texts, inputType).map(d :+ _))
            )
            .map { done =>
              val billed = done.map(_.billedTokens)
              val usage =
                if (billed.forall(_.isDefined)) {
                  val tokens = billed.flatten.sum
                  Some(EmbeddingUsage(promptTokens = tokens, totalTokens = tokens))
                } else None
              EmbeddingResponse(embeddings = done.flatMap(_.vectors), metadata = metadata, usage = usage)
            }
        }
      }

      private def send(model: String, texts: Seq[String], inputType: CohereInputType): Result[Batch] = {
        val payload = Obj(
          "model"           -> model,
          "texts"           -> Arr.from(texts),
          "input_type"      -> inputType.wireName,
          "embedding_types" -> Arr.from(Seq("float"))
        )
        logger.debug(
          s"[CohereEmbeddingProvider] POST $url model=$model input_type=${inputType.wireName} inputs=${texts.size}"
        )

        val headers = Map(
          "Authorization" -> s"Bearer ${cfg.apiKey}",
          "Content-Type"  -> "application/json"
        )

        // A cancellation is not a failed request: it passes through (design section 4.4).
        val respEither: Result[Llm4sHttpResponse] =
          httpClient.post(url, headers, payload.render(), timeout = cfg.timeouts.requestOr(120.seconds)).left.map {
            case cancelled: CancelledError => cancelled
            case err =>
              EmbeddingError(code = None, message = s"HTTP request failed: ${err.message}", provider = "cohere")
          }

        respEither.flatMap { response =>
          response.statusCode match {
            case 200 =>
              Try(parseBatch(response.body, texts.size)).toEither.left.map { ex =>
                logger.error(s"[CohereEmbeddingProvider] Parse error: ${ex.getMessage}")
                EmbeddingError(code = None, message = s"Parsing error: ${ex.getMessage}", provider = "cohere")
              }
            case 401 =>
              val body = safeBody(response.body)
              logger.error(s"[CohereEmbeddingProvider] Auth error (401): $body")
              Left(EmbeddingError(code = Some("401"), message = s"Authentication failed: $body", provider = "cohere"))
            case 429 =>
              logger.warn(s"[CohereEmbeddingProvider] Rate limit (429): ${safeBody(response.body)}")
              Left(retryAfter(response).map(RateLimitError("cohere", _)).getOrElse(RateLimitError("cohere")))
            case status =>
              val body = safeBody(response.body)
              logger.error(s"[CohereEmbeddingProvider] HTTP error $status: $body")
              Left(EmbeddingError(code = Some(status.toString), message = body, provider = "cohere"))
          }
        }
      }
    }
}
