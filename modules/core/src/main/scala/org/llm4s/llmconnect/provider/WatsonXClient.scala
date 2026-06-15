// scalafix:off DisableSyntax.NoKeywordTry
package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/**
 * [[LLMClient]] implementation for IBM WatsonX AI.
 *
 * Authentication uses IBM IAM: the `apiKey` in [[WatsonXConfig]] is exchanged
 * for a short-lived bearer token at the IAM endpoint. The token is cached and
 * automatically refreshed when it expires (after ~3600 seconds). All calls to
 * the WatsonX `/ml/v1/text/generation` endpoint include both the bearer token
 * and the `project_id`.
 *
 * == Supported operations ==
 *  - `complete()` via the `/ml/v1/text/generation` REST endpoint.
 *  - `streamComplete()` via the `/ml/v1/text/generation_stream` SSE endpoint.
 *
 * == Message format ==
 *
 * WatsonX uses a prompt-based API (not a chat-completions API). The client
 * converts the conversation into a single prompt string using role prefixes:
 *  - `SystemMessage` content is prepended as-is.
 *  - `UserMessage` content is prefixed with `"Human: "`.
 *  - `AssistantMessage` content is prefixed with `"Assistant: "`.
 *
 * The prompt is then passed as the `input` field in the request body.
 *
 * @param config      [[WatsonXConfig]] carrying the API key, project ID, model, and URLs.
 * @param metrics     Receives per-call latency and token-usage events.
 * @param httpClient  HTTP client used for all outbound calls; injectable for testing.
 */
class WatsonXClient(
  config: WatsonXConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  private[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  protected def clientDescription: String = s"WatsonX client for model ${config.model}"
  protected def providerName: String      = "watsonx"
  protected def modelName: String         = config.model

  // Cached IAM token: (token, expiresAtEpochSeconds)
  private[provider] val cachedToken: AtomicReference[Option[(String, Long)]] =
    new AtomicReference(None)

  // Obtain a valid bearer token, refreshing if expired or absent
  private[provider] def getBearerToken(): Result[String] = {
    val now = System.currentTimeMillis() / 1000L
    cachedToken.get() match {
      case Some((token, expiresAt)) if expiresAt > now + 60 =>
        Right(token)
      case _ =>
        exchangeIamToken()
    }
  }

  private def exchangeIamToken(): Result[String] = {
    val body =
      s"grant_type=urn%3Aibm%3Aparams%3Aoauth%3Agrant-type%3Aapikey&apikey=${config.apiKey}"
    val headers = Map(
      "Content-Type" -> "application/x-www-form-urlencoded",
      "Accept"       -> "application/json"
    )
    Try {
      httpClient.post(config.iamUrl, headers, body, timeout = 30000)
    }.toEither.left.map(_.toLLMError).flatMap { response =>
      if (response.statusCode >= 200 && response.statusCode < 300) {
        parseIamResponse(response.body)
      } else {
        Left(ConfigurationError(s"IAM token exchange failed (HTTP ${response.statusCode}): ${response.body.take(200)}"))
      }
    }
  }

  private def parseIamResponse(body: String): Result[String] =
    Try {
      val json = ujson.read(body)
      val tokenOpt = json.obj.get("access_token").flatMap(_.strOpt).filter(_.nonEmpty)
      val expiresInOpt =
        json.obj.get("expires_in").flatMap(_.numOpt).map(_.toLong)

      tokenOpt match {
        case None =>
          Left(ConfigurationError(s"IAM response missing access_token: ${body.take(200)}"))
        case Some(token) =>
          val expiresAt = System.currentTimeMillis() / 1000L + expiresInOpt.getOrElse(3600L)
          cachedToken.set(Some((token, expiresAt)))
          Right(token)
      }
    }.toEither.left.map(e => ConfigurationError(s"Failed to parse IAM response: ${e.getMessage}")).flatten

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    for {
      token       <- getBearerToken()
      prompt      <- buildPrompt(conversation)
      requestBody  = buildRequestBody(prompt, options)
      requestText  = requestBody.render()
      url          = s"${config.baseUrl}/ml/v1/text/generation?version=2023-05-29"
      headers      = Map(
        "Content-Type" -> "application/json",
        "Authorization" -> s"Bearer $token"
      )
      response    <- Try(httpClient.post(url, headers, requestText, timeout = 120000)).toEither.left.map(_.toLLMError)
      result      <- if (response.statusCode >= 200 && response.statusCode < 300) {
        val r = parseCompletionResponse(response.body)
        recordExchange(startedAt, requestText, Some(response.body), r)
        r
      } else {
        val err = handleErrorResponse(response.statusCode, response.body, token)
        recordExchange(startedAt, requestText, Some(response.body), err)
        err
      }
    } yield result
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    for {
      token       <- getBearerToken()
      prompt      <- buildPrompt(conversation)
      requestBody  = buildRequestBody(prompt, options)
      requestText  = requestBody.render()
      url          = s"${config.baseUrl}/ml/v1/text/generation_stream?version=2023-05-29"
      headers      = Map(
        "Content-Type"  -> "application/json",
        "Authorization" -> s"Bearer $token",
        "Accept"        -> "text/event-stream"
      )
      streamResp   = httpClient.postStream(url, headers, requestText, timeout = 600000)
      result       <- if (streamResp.statusCode < 200 || streamResp.statusCode >= 300) {
        val errBody = new String(streamResp.body.readAllBytes(), StandardCharsets.UTF_8)
        streamResp.body.close()
        val err = handleErrorResponse(streamResp.statusCode, errBody, token)
        recordExchange(startedAt, requestText, Some(errBody), err)
        err
      } else {
        processStreamResponse(startedAt, requestText, streamResp.body, onChunk)
      }
    } yield result
  }

  private def processStreamResponse(
    startedAt: Instant,
    requestText: String,
    body: java.io.InputStream,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    val reader      = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))
    val rawStream   = new StringBuilder()
    val accumulated = new StringBuilder()
    var promptTokens: Option[Int]     = None
    var completionTokens: Option[Int] = None
    val messageId = java.util.UUID.randomUUID().toString

    val result = Try {
      try {
        var line: String = null
        while ({ line = reader.readLine(); line != null }) {
          rawStream.append(line).append('\n')
          val trimmed = line.trim
          if (trimmed.startsWith("data: ")) {
            val jsonStr = trimmed.stripPrefix("data: ").trim
            if (jsonStr.nonEmpty && jsonStr != "[DONE]") {
              Try(ujson.read(jsonStr)).foreach { json =>
                // Extract generated text from the chunk
                val textDelta = json.obj
                  .get("results")
                  .flatMap(_.arrOpt)
                  .flatMap(_.headOption)
                  .flatMap(_.obj.get("generated_text"))
                  .flatMap(_.strOpt)
                  .getOrElse("")

                if (textDelta.nonEmpty) {
                  accumulated.append(textDelta)
                  val chunk = StreamedChunk(
                    id = messageId,
                    content = Some(textDelta)
                  )
                  onChunk(chunk)
                }

                // Extract token usage if present
                for {
                  results   <- json.obj.get("results").flatMap(_.arrOpt)
                  firstResult <- results.headOption
                  inputTokens <- firstResult.obj.get("input_token_count").flatMap(_.numOpt)
                } promptTokens = Some(inputTokens.toInt)

                for {
                  results      <- json.obj.get("results").flatMap(_.arrOpt)
                  firstResult  <- results.headOption
                  outputTokens <- firstResult.obj.get("generated_token_count").flatMap(_.numOpt)
                } completionTokens = Some(outputTokens.toInt)
              }
            }
          }
        }
      } finally {
        Try(reader.close())
        Try(body.close())
      }
    }.toEither.left.map(_.toLLMError)

    result.flatMap { _ =>
      val text = accumulated.toString()
      if (text.isEmpty) {
        Left(ValidationError("response", "Empty streaming response from WatsonX"))
      } else {
        val usageOpt = for {
          pt <- promptTokens
          ct <- completionTokens
        } yield TokenUsage(promptTokens = pt, completionTokens = ct, totalTokens = pt + ct)

        val costOpt = usageOpt.flatMap(u => CostEstimator.estimate(config.model, u))
        val completion = Completion(
          id = messageId,
          created = System.currentTimeMillis() / 1000L,
          content = text,
          model = config.model,
          message = AssistantMessage(contentOpt = Some(text), toolCalls = Seq.empty),
          toolCalls = List.empty,
          usage = usageOpt,
          thinking = None,
          estimatedCost = costOpt
        )
        recordExchange(startedAt, requestText, Some(rawStream.result()), Right(completion))
        Right(completion)
      }
    }.tapLeft(err =>
      recordExchange(
        startedAt,
        requestText,
        Option.when(rawStream.nonEmpty)(rawStream.result()),
        Left(err)
      )
    )
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  private def buildPrompt(conversation: Conversation): Result[String] = {
    if (conversation.messages.isEmpty) {
      Left(ValidationError("conversation", "WatsonX requires at least one message"))
    } else {
      val sb = new StringBuilder()
      conversation.messages.foreach {
        case SystemMessage(content) =>
          sb.append(content).append("\n\n")
        case UserMessage(content) =>
          sb.append("Human: ").append(content).append("\n")
        case AssistantMessage(contentOpt, _) =>
          contentOpt.filter(_.nonEmpty).foreach(c => sb.append("Assistant: ").append(c).append("\n"))
        case _ =>
          () // Skip unsupported message types
      }
      sb.append("Assistant:")
      Right(sb.toString())
    }
  }

  private def buildRequestBody(prompt: String, options: CompletionOptions): ujson.Obj = {
    val params = ujson.Obj(
      "decoding_method" -> "greedy",
      "temperature"     -> options.temperature
    )
    options.maxTokens.foreach(mt => params("max_new_tokens") = mt)

    ujson.Obj(
      "model_id"   -> config.model,
      "input"      -> prompt,
      "project_id" -> config.projectId,
      "parameters" -> params
    )
  }

  private def parseCompletionResponse(body: String): Result[Completion] =
    Try {
      val json = ujson.read(body)

      val textOpt = json.obj
        .get("results")
        .flatMap(_.arrOpt)
        .flatMap(_.headOption)
        .flatMap(_.obj.get("generated_text"))
        .flatMap(_.strOpt)
        .map(_.trim)

      textOpt match {
        case None | Some("") =>
          Left(ValidationError("response", "Missing or empty generated_text in WatsonX response"))
        case Some(text) =>
          val id      = json.obj.get("id").flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString)
          val created = System.currentTimeMillis() / 1000L

          val usageOpt = for {
            results     <- json.obj.get("results").flatMap(_.arrOpt)
            firstResult <- results.headOption
            inputToks   <- firstResult.obj.get("input_token_count").flatMap(_.numOpt)
            outputToks  <- firstResult.obj.get("generated_token_count").flatMap(_.numOpt)
          } yield TokenUsage(
            promptTokens = inputToks.toInt,
            completionTokens = outputToks.toInt,
            totalTokens = inputToks.toInt + outputToks.toInt
          )

          val costOpt = usageOpt.flatMap(u => CostEstimator.estimate(config.model, u))

          Right(
            Completion(
              id = id,
              created = created,
              content = text,
              model = config.model,
              message = AssistantMessage(contentOpt = Some(text), toolCalls = Seq.empty),
              toolCalls = List.empty,
              usage = usageOpt,
              thinking = None,
              estimatedCost = costOpt
            )
          )
      }
    }.toEither.left.map(_.toLLMError).flatten

  private def handleErrorResponse(statusCode: Int, body: String, token: String): Result[Nothing] = {
    // Detect invalid project_id: WatsonX returns a specific error message
    if (body.contains("project_id") || body.contains("BXZAI0001E")) {
      Left(ValidationError("project_id", s"Invalid project_id: ${body.take(200)}"))
    } else {
      HttpErrorMapper.mapHttpError(statusCode, body, providerName)
    }
  }

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
}

object WatsonXClient {
  import org.llm4s.types.TryOps

  def apply(config: WatsonXConfig)(using ModelRegistryService): Result[WatsonXClient] =
    Try(new WatsonXClient(config)).toResult

  def apply(
    config: WatsonXConfig,
    metrics: org.llm4s.metrics.MetricsCollector
  )(using ModelRegistryService): Result[WatsonXClient] =
    Try(new WatsonXClient(config, metrics)).toResult

  def apply(
    config: WatsonXConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[WatsonXClient] =
    Try(new WatsonXClient(config, metrics, exchangeLogging)).toResult

  /** Test seam: inject a mock HTTP client for unit tests. */
  private[provider] def forTest(
    config: WatsonXConfig,
    httpClient: Llm4sHttpClient
  )(using ModelRegistryService): WatsonXClient =
    new WatsonXClient(config, httpClient = httpClient)
}
