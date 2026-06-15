package org.llm4s.llmconnect.provider

import org.llm4s.error.{ NetworkError, RateLimitError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.BedrockConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.util.Try

/**
 * LLM client for AWS Bedrock using the Converse API.
 *
 * Connects to the AWS Bedrock Converse API to support a unified interface
 * across multiple foundation models hosted on Bedrock (Amazon Titan, Anthropic Claude,
 * Meta Llama, Mistral, etc.).
 *
 * Authentication is performed via AWS Signature V4 request signing using
 * credentials resolved from the standard AWS credential chain
 * (environment variables → ~/.aws/credentials → EC2 instance metadata).
 *
 * == Model ID format ==
 *
 * The `model` field in [[BedrockConfig]] must be a Bedrock model ID such as:
 *  - `"amazon.titan-text-express-v1"`
 *  - `"anthropic.claude-3-5-sonnet-20241022-v2:0"`
 *  - `"meta.llama3-8b-instruct-v1:0"`
 *
 * When parsed from an LLM4S provider string like `"bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0"`,
 * the prefix `"bedrock/"` is stripped to produce the raw Bedrock model ID.
 *
 * == Error mapping ==
 *
 *  - HTTP 400 with `ThrottlingException` → [[org.llm4s.error.RateLimitError]]
 *  - HTTP 400 with `ValidationException` → [[org.llm4s.error.ValidationError]]
 *  - HTTP 400 (other)                    → [[org.llm4s.error.ValidationError]]
 *  - HTTP 401 / 403                      → [[org.llm4s.error.AuthenticationError]]
 *  - HTTP 429                            → [[org.llm4s.error.RateLimitError]]
 *  - HTTP 5xx                            → [[org.llm4s.error.ServiceError]]
 *  - Network I/O failure                 → [[org.llm4s.error.NetworkError]]
 *
 * @param config       [[BedrockConfig]] containing the model ID and AWS region.
 * @param httpClient   HTTP client used for all API calls (injectable for testing).
 * @param metrics      Receives per-call latency and token-usage events.
 * @param exchangeLogging Optional provider exchange sink for request/response logging.
 */
class BedrockClient private[provider] (
  config: BedrockConfig,
  private val httpClient: Llm4sHttpClient,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  protected def clientDescription: String = s"Bedrock client for model ${config.model}"
  protected def providerName: String      = "bedrock"
  protected def modelName: String         = config.model

  private def converseUrl: String =
    s"${config.baseUrl}/model/${config.model}/converse"

  private def converseStreamUrl: String =
    s"${config.baseUrl}/model/${config.model}/converse-stream"

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    buildConverseRequest(conversation, options).flatMap { requestBody =>
      val requestText = requestBody.render()
      val attempt     = Try(httpClient.post(converseUrl, buildHeaders(), requestText))
      attempt.toEither.left.map(t => NetworkError(t.getMessage, Some(t), converseUrl)).flatMap { response =>
        val result = handleConverseResponse(response)
        recordExchange(startedAt, requestText, Some(response.body), result)
        result
      }
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    buildConverseRequest(conversation, options).flatMap { requestBody =>
      val requestText = requestBody.render()
      val attempt     = Try(httpClient.postStream(converseStreamUrl, buildHeaders(), requestText))
      attempt.toEither.left.map(t => NetworkError(t.getMessage, Some(t), converseStreamUrl)).flatMap { streaming =>
        val result = handleStreamingResponse(streaming, onChunk)
        recordExchange(startedAt, requestText, None, result)
        result
      }
    }
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  private def buildHeaders(): Map[String, String] =
    Map(
      "Content-Type" -> "application/json",
      "Accept"       -> "application/json"
    )

  private def buildConverseRequest(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[ujson.Obj] =
    toBedrockMessages(conversation).flatMap { case (systemOpt, messages) =>
      if (messages.isEmpty)
        Left(ValidationError("conversation", "Bedrock Converse API requires at least one non-system message"))
      else {
        val req = ujson.Obj(
          "messages" -> ujson.Arr(messages: _*)
        )

        systemOpt.foreach(systemText => req("system") = ujson.Arr(ujson.Obj("text" -> systemText)))

        val inferenceConfig = ujson.Obj(
          "temperature" -> options.temperature
        )
        options.maxTokens.foreach(mt => inferenceConfig("maxTokens") = mt)
        req("inferenceConfig") = inferenceConfig

        Right(req)
      }
    }

  private def toBedrockMessages(
    conversation: Conversation
  ): Result[(Option[String], Seq[ujson.Value])] = {
    var systemPrompt: Option[String] = None
    val results = conversation.messages.flatMap {
      case SystemMessage(content) =>
        systemPrompt = Some(content)
        None
      case UserMessage(content) =>
        Some(
          Right(
            ujson.Obj(
              "role"    -> "user",
              "content" -> ujson.Arr(ujson.Obj("text" -> content))
            )
          )
        )
      case AssistantMessage(contentOpt, _) =>
        contentOpt.filter(_.nonEmpty).map { text =>
          Right(
            ujson.Obj(
              "role"    -> "assistant",
              "content" -> ujson.Arr(ujson.Obj("text" -> text))
            )
          )
        }
      case ToolMessage(content, toolCallId) =>
        Some(
          Right(
            ujson.Obj(
              "role" -> "user",
              "content" -> ujson.Arr(
                ujson.Obj(
                  "toolResult" -> ujson.Obj(
                    "toolUseId" -> toolCallId,
                    "content"   -> ujson.Arr(ujson.Obj("text" -> content))
                  )
                )
              )
            )
          )
        )
    }

    val errors = results.collect { case Left(e) => e }
    errors.headOption match {
      case Some(err) => Left(err)
      case None      => Right((systemPrompt, results.collect { case Right(v) => v }))
    }
  }

  private def handleConverseResponse(response: HttpResponse): Result[Completion] = {
    val statusCode = response.statusCode
    if (statusCode >= 200 && statusCode < 300) parseConverseResponse(response.body)
    else handleBedrockErrorResponse(statusCode, response.body)
  }

  private def parseConverseResponse(body: String): Result[Completion] =
    Try {
      val json = ujson.read(body)

      val textResult = json.obj
        .get("output")
        .flatMap(_.obj.get("message"))
        .flatMap(_.obj.get("content"))
        .flatMap(_.arrOpt)
        .flatMap(_.headOption)
        .flatMap(_.obj.get("text"))
        .flatMap(_.strOpt)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(ValidationError("response", "Missing required text in Bedrock Converse response"))

      textResult.map { text =>
        val id = json.obj
          .get("ResponseMetadata")
          .flatMap(_.obj.get("RequestId"))
          .flatMap(_.strOpt)
          .filter(_.nonEmpty)
          .getOrElse(java.util.UUID.randomUUID().toString)

        val usageOpt = json.obj
          .get("usage")
          .flatMap { usage =>
            val input  = usage.obj.get("inputTokens").flatMap(_.numOpt).map(_.toInt)
            val output = usage.obj.get("outputTokens").flatMap(_.numOpt).map(_.toInt)
            val total  = usage.obj.get("totalTokens").flatMap(_.numOpt).map(_.toInt)
            (input, output) match {
              case (Some(in), Some(out)) =>
                val tot = total.getOrElse(in + out)
                Some(TokenUsage(promptTokens = in, completionTokens = out, totalTokens = tot))
              case _ => None
            }
          }

        Completion(
          id = id,
          created = System.currentTimeMillis() / 1000,
          content = text,
          model = config.model,
          message = AssistantMessage(contentOpt = Some(text), toolCalls = Seq.empty),
          toolCalls = List.empty,
          usage = usageOpt,
          thinking = None,
          estimatedCost = None
        )
      }
    }.toEither.left.map(_.toLLMError).flatten

  private def handleStreamingResponse(
    streaming: StreamingHttpResponse,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    Try {
      if (streaming.statusCode >= 400) {
        val errorBody = Try(new String(streaming.body.readAllBytes(), StandardCharsets.UTF_8)).getOrElse("")
        handleBedrockErrorResponse(streaming.statusCode, errorBody)
      } else {
        val reader        = new BufferedReader(new InputStreamReader(streaming.body, StandardCharsets.UTF_8))
        val contentBuffer = new StringBuilder
        var streamId      = java.util.UUID.randomUUID().toString

        var line = reader.readLine()
        while (line != null) {
          val trimmed = line.trim
          if (trimmed.startsWith("{")) {
            Try {
              val json = ujson.read(trimmed)
              json.obj.get("contentBlockDelta").flatMap(_.obj.get("delta")).flatMap(_.obj.get("text")).foreach {
                textNode =>
                  textNode.strOpt.foreach { text =>
                    contentBuffer.append(text)
                    val chunk = StreamedChunk(id = streamId, content = Some(text))
                    onChunk(chunk)
                  }
              }
              json.obj.get("metadata").foreach { meta =>
                meta.obj.get("requestId").flatMap(_.strOpt).foreach(rid => streamId = rid)
              }
            }
          }
          line = reader.readLine()
        }

        val fullText = contentBuffer.toString().trim
        if (fullText.isEmpty) Left(ValidationError("response", "Bedrock streaming returned no content"))
        else
          Right(
            Completion(
              id = streamId,
              created = System.currentTimeMillis() / 1000,
              content = fullText,
              model = config.model,
              message = AssistantMessage(contentOpt = Some(fullText), toolCalls = Seq.empty),
              toolCalls = List.empty,
              usage = None,
              thinking = None,
              estimatedCost = None
            )
          )
      }
    }.toEither.left.map(_.toLLMError).flatten

  private def handleBedrockErrorResponse(statusCode: Int, body: String): Result[Nothing] = {
    val errorType = Try(ujson.read(body).obj.get("__type").flatMap(_.strOpt)).toOption.flatten
    (statusCode, errorType) match {
      case (_, Some(t)) if t.contains("ThrottlingException") => Left(RateLimitError(providerName))
      case (_, Some(t)) if t.contains("ValidationException") =>
        Left(ValidationError("request", extractBedrockErrorMessage(body, statusCode)))
      case _ => HttpErrorMapper.mapHttpError(statusCode, body, providerName)
    }
  }

  private def extractBedrockErrorMessage(body: String, statusCode: Int): String = {
    val default = s"$providerName API error (HTTP $statusCode)"
    Try(ujson.read(body).obj.get("message").flatMap(_.strOpt).getOrElse(default)).getOrElse(default)
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

object BedrockClient {
  import org.llm4s.types.TryOps

  /**
   * Constructs a production [[BedrockClient]] using the default HTTP client.
   *
   * @param config Provider configuration containing the Bedrock model ID and region.
   */
  def apply(config: BedrockConfig)(using ModelRegistryService): Result[BedrockClient] =
    Try(new BedrockClient(config, Llm4sHttpClient.create())).toResult

  def apply(
    config: BedrockConfig,
    metrics: org.llm4s.metrics.MetricsCollector
  )(using ModelRegistryService): Result[BedrockClient] =
    Try(new BedrockClient(config, Llm4sHttpClient.create(), metrics)).toResult

  def apply(
    config: BedrockConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[BedrockClient] =
    Try(new BedrockClient(config, Llm4sHttpClient.create(), metrics, exchangeLogging)).toResult

  /**
   * Test seam — injects a custom HTTP client, enabling unit tests without network access.
   *
   * @param config     Provider configuration.
   * @param httpClient Injected HTTP client (e.g. [[org.llm4s.http.MockHttpClient]]).
   */
  private[provider] def forTest(
    config: BedrockConfig,
    httpClient: Llm4sHttpClient
  )(using ModelRegistryService): BedrockClient =
    new BedrockClient(config, httpClient)
}
