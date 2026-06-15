// scalafix:off DisableSyntax.NoKeywordTry
package org.llm4s.llmconnect.provider

import org.llm4s.error.ThrowableOps._
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/**
 * IBM WatsonX AI provider client.
 *
 * Implements non-streaming and streaming text generation via the WatsonX ML API
 * (`POST /ml/v1/text/generation` and `POST /ml/v1/text/generation_stream`).
 *
 * == Authentication ==
 * WatsonX uses IBM Cloud IAM token exchange. The `apiKey` is exchanged for a
 * short-lived bearer token at `https://iam.cloud.ibm.com/identity/token`.
 * Tokens expire after 1 hour; this client refreshes the token lazily when it
 * detects expiry (with a 5-minute safety buffer).
 *
 * == Request format ==
 * WatsonX uses a proprietary format (`model_id`, `project_id`, `input`,
 * `parameters`) rather than the OpenAI-compatible chat completions API.
 * Conversation messages are concatenated into a single `input` string with
 * role prefixes.
 *
 * == Model prefix convention ==
 * Use `LLM_MODEL=watsonx/<model-id>`, e.g.:
 *   - `watsonx/ibm/granite-13b-instruct-v2`
 *   - `watsonx/meta-llama/llama-3-8b-instruct`
 *   - `watsonx/mistralai/mistral-large`
 *
 * @param config         WatsonX configuration (API key, project ID, base URL, etc.)
 * @param httpClient     HTTP client; injectable for testing via [[WatsonXClient.forTest]].
 * @param iamHttpClient  Separate HTTP client for IAM token exchange (can be the same instance).
 * @param metrics        Receives per-call latency and token-usage events.
 * @param exchangeLogging Optional provider exchange logging.
 */
class WatsonXClient(
  config: WatsonXConfig,
  httpClient: Llm4sHttpClient,
  iamHttpClient: Llm4sHttpClient,
  protected val metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  import WatsonXClient._

  protected def clientDescription: String = s"WatsonX client for model ${config.model}"
  protected def providerName: String      = "watsonx"
  protected def modelName: String         = config.model

  // IAM token cache: stores (token, expiry epoch seconds)
  private val tokenCache: AtomicReference[Option[(String, Long)]] = new AtomicReference(None)

  private def iamTokenUrl: String = IAM_TOKEN_URL

  /**
   * Obtains a valid IAM bearer token, refreshing if missing or within the expiry buffer.
   */
  private[provider] def getOrRefreshToken(): Result[String] = {
    val now          = System.currentTimeMillis() / 1000L
    val bufferSecs   = 300L // refresh 5 minutes before expiry
    val cachedResult = tokenCache.get()
    cachedResult match {
      case Some((token, expiry)) if now < expiry - bufferSecs =>
        Right(token)
      case _ =>
        fetchIamToken()
    }
  }

  private def fetchIamToken(): Result[String] = {
    val body =
      s"grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey&apikey=${urlEncode(config.apiKey)}"
    val headers = Map(
      "Content-Type" -> "application/x-www-form-urlencoded",
      "Accept"       -> "application/json"
    )
    Try {
      iamHttpClient.post(
        url = iamTokenUrl,
        headers = headers,
        body = body,
        timeout = IAM_TIMEOUT_MS
      )
    }.toEither.left.map(_.toLLMError).flatMap { response =>
      if (response.statusCode >= 200 && response.statusCode < 300) {
        parseIamTokenResponse(response.body)
      } else {
        Left(
          org.llm4s.error.AuthenticationError(
            "watsonx",
            s"IAM token exchange failed (HTTP ${response.statusCode}): ${response.body.take(256)}"
          )
        )
      }
    }
  }

  private def parseIamTokenResponse(body: String): Result[String] =
    Try {
      val json        = ujson.read(body)
      val accessToken = json.obj.get("access_token").flatMap(_.strOpt)
      val expiresIn   = json.obj.get("expires_in").flatMap(_.numOpt).map(_.toLong)
      accessToken match {
        case Some(token) =>
          val expiry = System.currentTimeMillis() / 1000L + expiresIn.getOrElse(3600L)
          tokenCache.set(Some((token, expiry)))
          Right(token)
        case None =>
          Left(
            org.llm4s.error.AuthenticationError(
              "watsonx",
              s"IAM response missing access_token: ${body.take(256)}"
            )
          )
      }
    }.toEither.left.map(_.toLLMError).flatten

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    getOrRefreshToken().flatMap { token =>
      val requestBody = buildGenerationRequest(conversation, options)
      val requestText = requestBody.render()
      val url         = s"${config.baseUrl}/ml/v1/text/generation?version=${config.apiVersion}"
      val headers = Map(
        "Content-Type"  -> "application/json",
        "Authorization" -> s"Bearer $token",
        "Accept"        -> "application/json"
      )
      Try {
        httpClient.post(url = url, headers = headers, body = requestText, timeout = DEFAULT_TIMEOUT_MS)
      }.toEither.left.map(_.toLLMError).flatMap { response =>
        if (response.statusCode >= 200 && response.statusCode < 300) {
          val result = parseGenerationResponse(response.body)
          recordExchange(startedAt, requestText, Some(response.body), result)
          result
        } else {
          val errorResult = HttpErrorMapper.mapHttpError(response.statusCode, response.body, providerName)
          recordExchange(startedAt, requestText, Some(response.body), errorResult)
          errorResult
        }
      }
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    getOrRefreshToken().flatMap { token =>
      val requestBody = buildGenerationRequest(conversation, options)
      requestBody("stream") = true
      val requestText = requestBody.render()
      val url         = s"${config.baseUrl}/ml/v1/text/generation_stream?version=${config.apiVersion}"
      val headers = Map(
        "Content-Type"  -> "application/json",
        "Authorization" -> s"Bearer $token",
        "Accept"        -> "text/event-stream"
      )
      val rawStream = new StringBuilder()
      val attempt = Try {
        val streamResponse = httpClient.postStream(url = url, headers = headers, body = requestText)
        if (streamResponse.statusCode >= 200 && streamResponse.statusCode < 300) {
          val accumulated           = new StringBuilder()
          var lastId                = java.util.UUID.randomUUID().toString
          var totalPromptTokens     = 0
          var totalCompletionTokens = 0
          val reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(streamResponse.body, java.nio.charset.StandardCharsets.UTF_8)
          )
          Try {
            var line = reader.readLine()
            while (line != null) {
              rawStream.append(line).append('\n')
              val trimmed = line.trim
              if (trimmed.startsWith("data:")) {
                val data = trimmed.drop(5).trim
                if (data.nonEmpty && data != "[DONE]") {
                  Try {
                    val json         = ujson.read(data)
                    val generatedOpt = json.obj.get("results").flatMap(_.arrOpt).flatMap(_.headOption)
                    generatedOpt.foreach { result =>
                      val textOpt = result.obj.get("generated_text").flatMap(_.strOpt)
                      textOpt.foreach { text =>
                        if (text.nonEmpty) {
                          accumulated.append(text)
                          val chunk = StreamedChunk(
                            id = lastId,
                            content = Some(text),
                            toolCall = None,
                            finishReason = None
                          )
                          onChunk(chunk)
                        }
                      }
                      // Extract usage from streaming response if present
                      result.obj.get("input_token_count").flatMap(_.numOpt).foreach(n => totalPromptTokens = n.toInt)
                      result.obj.get("generated_token_count").flatMap(_.numOpt).foreach { n =>
                        totalCompletionTokens = n.toInt
                      }
                    }
                    // Also try top-level id
                    json.obj.get("id").flatMap(_.strOpt).foreach(id => lastId = id)
                  }.recover { case _ => () }
                }
              }
              line = reader.readLine()
            }
          }.recover { case _ => () }
          Try(reader.close())
          Try(streamResponse.body.close())
          val content = accumulated.toString()
          val usage = if (totalPromptTokens > 0 || totalCompletionTokens > 0) {
            Some(
              TokenUsage(
                promptTokens = totalPromptTokens,
                completionTokens = totalCompletionTokens,
                totalTokens = totalPromptTokens + totalCompletionTokens
              )
            )
          } else None
          val cost = usage.flatMap(u => CostEstimator.estimate(config.model, u))
          val completion = Completion(
            id = lastId,
            created = System.currentTimeMillis() / 1000L,
            content = content,
            model = config.model,
            message = AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty),
            toolCalls = List.empty,
            usage = usage,
            thinking = None,
            estimatedCost = cost
          )
          Right(completion)
        } else {
          val errorBody = Try(new String(streamResponse.body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
            .getOrElse("")
          Try(streamResponse.body.close())
          HttpErrorMapper.mapHttpError(streamResponse.statusCode, errorBody, providerName)
        }
      }.toEither.left.map(_.toLLMError).flatten

      attempt match {
        case Right(completion) =>
          recordExchange(startedAt, requestText, Some(rawStream.result()), Right(completion))
          Right(completion)
        case Left(err) =>
          recordExchange(startedAt, requestText, Option.when(rawStream.nonEmpty)(rawStream.result()), Left(err))
          Left(err)
      }
    }
  }

  override def getContextWindow(): Int     = config.contextWindow
  override def getReserveCompletion(): Int = config.reserveCompletion

  override protected def releaseResources(): Unit = ()

  private[provider] def buildGenerationRequest(
    conversation: Conversation,
    options: CompletionOptions
  ): ujson.Obj = {
    val input = formatConversationInput(conversation)
    val parameters = ujson.Obj(
      "temperature" -> options.temperature
    )
    options.maxTokens.foreach(mt => parameters("max_new_tokens") = mt)
    if (options.topP != 1.0) parameters("top_p") = options.topP

    val req = ujson.Obj(
      "model_id"   -> config.model,
      "input"      -> input,
      "parameters" -> parameters
    )
    config.spaceId match {
      case Some(sid) => req("space_id") = sid
      case None      => req("project_id") = config.projectId
    }
    req
  }

  private def formatConversationInput(conversation: Conversation): String = {
    val sb = new StringBuilder()
    conversation.messages.foreach {
      case SystemMessage(content) =>
        sb.append(s"[SYSTEM]: $content\n")
      case UserMessage(content) =>
        sb.append(s"[USER]: $content\n")
      case AssistantMessage(contentOpt, _) =>
        contentOpt.filter(_.nonEmpty).foreach(c => sb.append(s"[ASSISTANT]: $c\n"))
      case ToolMessage(content, toolCallId) =>
        sb.append(s"[TOOL_RESULT:$toolCallId]: $content\n")
    }
    sb.append("[ASSISTANT]: ")
    sb.result()
  }

  private def parseGenerationResponse(body: String): Result[Completion] =
    Try {
      val json = ujson.read(body)
      val resultsArr = json.obj
        .get("results")
        .flatMap(_.arrOpt)
        .getOrElse(
          throw new IllegalArgumentException("WatsonX response missing 'results' array")
        )
      val firstResult = resultsArr.headOption.getOrElse(
        throw new IllegalArgumentException("WatsonX response has empty 'results' array")
      )
      val generatedText = firstResult.obj
        .get("generated_text")
        .flatMap(_.strOpt)
        .getOrElse("")

      val promptTokenCount =
        firstResult.obj
          .get("input_token_count")
          .flatMap(_.numOpt)
          .map(_.toInt)
          .orElse(
            json.obj.get("input_token_count").flatMap(_.numOpt).map(_.toInt)
          )
      val generatedTokenCount =
        firstResult.obj.get("generated_token_count").flatMap(_.numOpt).map(_.toInt)

      val usage = (promptTokenCount, generatedTokenCount) match {
        case (Some(p), Some(g)) =>
          Some(TokenUsage(promptTokens = p, completionTokens = g, totalTokens = p + g))
        case _ => None
      }

      val cost = usage.flatMap(u => CostEstimator.estimate(config.model, u))

      val id = json.obj.get("id").flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString)

      Right(
        Completion(
          id = id,
          created = System.currentTimeMillis() / 1000L,
          content = generatedText,
          model = config.model,
          message = AssistantMessage(contentOpt = Some(generatedText), toolCalls = Seq.empty),
          toolCalls = List.empty,
          usage = usage,
          thinking = None,
          estimatedCost = cost
        )
      )
    }.toEither.left.map(_.toLLMError).flatten

  private def recordExchange(
    startedAt: Instant,
    requestBody: String,
    responseBody: Option[String],
    result: Result[?]
  ): Unit =
    ProviderExchangeRecorder.record(
      exchangeLogging = exchangeLogging,
      provider = providerName,
      model = Some(config.model),
      startedAt = startedAt,
      requestBody = requestBody,
      responseBody = responseBody,
      result = result
    )

  private def urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8")
}

object WatsonXClient {
  import org.llm4s.types.TryOps

  val IAM_TOKEN_URL: String   = "https://iam.cloud.ibm.com/identity/token"
  val IAM_TIMEOUT_MS: Int     = 30000
  val DEFAULT_TIMEOUT_MS: Int = 120000

  def apply(
    config: WatsonXConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[WatsonXClient] = {
    val httpClient = Llm4sHttpClient.create()
    Try(new WatsonXClient(config, httpClient, httpClient, metrics, exchangeLogging)).toResult
  }

  def apply(
    config: WatsonXConfig,
    metrics: MetricsCollector
  )(using ModelRegistryService): Result[WatsonXClient] = {
    val httpClient = Llm4sHttpClient.create()
    Try(new WatsonXClient(config, httpClient, httpClient, metrics)).toResult
  }

  def apply(config: WatsonXConfig)(using ModelRegistryService): Result[WatsonXClient] = {
    val httpClient = Llm4sHttpClient.create()
    Try(new WatsonXClient(config, httpClient, httpClient)).toResult
  }

  /**
   * Test seam — allows injecting mock HTTP clients for unit tests.
   * The `httpClient` is used for WatsonX API calls; `iamHttpClient` for IAM token exchange.
   */
  private[provider] def forTest(
    config: WatsonXConfig,
    httpClient: Llm4sHttpClient,
    iamHttpClient: Llm4sHttpClient
  )(using ModelRegistryService): WatsonXClient =
    new WatsonXClient(config, httpClient, iamHttpClient)

  /**
   * Test seam with a single HTTP client used for both API calls and IAM exchange.
   */
  private[provider] def forTest(
    config: WatsonXConfig,
    httpClient: Llm4sHttpClient
  )(using ModelRegistryService): WatsonXClient =
    new WatsonXClient(config, httpClient, httpClient)
}
