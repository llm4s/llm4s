// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordFinally
package org.llm4s.llmconnect.provider

import org.llm4s.error.ThrowableOps._
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.TogetherAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps._
import org.llm4s.llmconnect.streaming.{ SSEParser, StreamingAccumulator, StreamingToolArgumentParser }
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ Result, TryOps }
import org.slf4j.LoggerFactory

import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.util.Try

/**
 * [[LLMClient]] implementation for the Together AI platform.
 *
 * Together AI hosts open-source models (Llama, Mistral, Qwen, DBRX, StableLM) at scale and
 * exposes a fully OpenAI-compatible REST API at `https://api.together.xyz/v1`.
 *
 * Both non-streaming (`complete`) and streaming (`streamComplete`) are supported using
 * the standard OpenAI `/v1/chat/completions` endpoint format.
 *
 * == Authentication ==
 * API key is sent as a Bearer token in the `Authorization` header, just like OpenAI.
 *
 * == Supported models ==
 * - `meta-llama/Llama-3.3-70B-Instruct-Turbo`
 * - `mistralai/Mixtral-8x7B-Instruct-v0.1`
 * - `Qwen/Qwen2.5-72B-Instruct-Turbo`
 *
 * @param config        Together AI configuration with API key, model, and base URL.
 * @param metrics       Receives per-call latency and token-usage events.
 * @param exchangeLogging Controls whether raw provider exchanges are logged.
 * @param httpClient    HTTP client; injectable for testing.
 */
class TogetherAIClient(
  config: TogetherAIConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  private[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val logger = LoggerFactory.getLogger(getClass)

  protected def clientDescription: String = s"TogetherAI client for model ${config.model}"
  protected def providerName: String      = "together"
  protected def modelName: String         = config.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt   = Instant.now()
    val requestBody = buildRequestBody(conversation, options)
    val requestText = requestBody.render()
    val url         = s"${config.baseUrl}/chat/completions"

    logger.debug(s"[TogetherAI] Sending request to $url for model ${config.model}")

    val headers = Map(
      "Content-Type"  -> "application/json",
      "Authorization" -> s"Bearer ${config.apiKey}"
    )

    Try {
      val response = httpClient.post(url, headers, requestText, timeout = 120000)

      if (response.statusCode >= 200 && response.statusCode < 300) {
        val completionResult = parseChatResponse(response.body)
        recordExchange(startedAt, requestText, Some(response.body), completionResult)
        completionResult
      } else {
        val errorResult = HttpErrorMapper.mapHttpError(response.statusCode, response.body, providerName)
        recordExchange(startedAt, requestText, Some(response.body), errorResult)
        errorResult
      }
    }.toEither.left.map(_.toLLMError).flatten
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt   = Instant.now()
    val requestBody = buildRequestBody(conversation, options)
    requestBody("stream") = true
    val requestText = requestBody.render()
    val url         = s"${config.baseUrl}/chat/completions"

    logger.debug(s"[TogetherAI] Starting stream to $url for model ${config.model}")

    val headers = Map(
      "Content-Type"  -> "application/json",
      "Authorization" -> s"Bearer ${config.apiKey}"
    )

    val accumulator = StreamingAccumulator.create()
    val rawStream   = StringBuilder()

    val response = httpClient.postStream(url, headers, requestText, timeout = 600000)

    if (response.statusCode < 200 || response.statusCode >= 300) {
      val errBody     = new String(response.body.readAllBytes(), StandardCharsets.UTF_8)
      val errorResult = HttpErrorMapper.mapHttpError(response.statusCode, errBody, providerName)
      recordExchange(startedAt, requestText, Some(errBody), errorResult)
      response.body.close()
      errorResult
    } else {
      val reader = new BufferedReader(new InputStreamReader(response.body, StandardCharsets.UTF_8))

      Try {
        try {
          val sseParser    = SSEParser.createStreamingParser()
          var line: String = null
          while ({ line = reader.readLine(); line != null }) {
            rawStream.append(line).append('\n')
            sseParser.addChunk(line + "\n")
            while (sseParser.hasEvents)
              sseParser.nextEvent().foreach { event =>
                event.data.foreach { data =>
                  if (data != "[DONE]") {
                    val json   = ujson.read(data)
                    val chunks = parseStreamingChunks(json)
                    chunks.foreach { c =>
                      accumulator.addChunk(c)
                      onChunk(c)
                    }
                  }
                }
              }
          }
        } finally {
          Try(reader.close())
          Try(response.body.close())
        }
      }.toEither.left
        .map(_.toLLMError)
        .flatMap(_ =>
          accumulator.toCompletion.map { c =>
            val cost       = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
            val completion = c.copy(model = config.model, estimatedCost = cost)
            recordExchange(startedAt, requestText, Some(rawStream.result()), Right(completion))
            completion
          }
        )
        .tapLeft(error =>
          recordExchange(
            startedAt,
            requestText,
            Option.when(rawStream.nonEmpty)(rawStream.result()),
            Left(error)
          )
        )
    }
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  override protected def releaseResources(): Unit = ()

  private def buildRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val messages = conversation.messages.map {
      case SystemMessage(content) =>
        ujson.Obj("role" -> "system", "content" -> content)
      case UserMessage(content) =>
        ujson.Obj("role" -> "user", "content" -> content)
      case AssistantMessage(contentOpt, toolCalls) =>
        val base = ujson.Obj("role" -> "assistant")
        contentOpt.filter(_.nonEmpty).foreach(c => base("content") = c)
        if (toolCalls.nonEmpty) {
          base("tool_calls") = ujson.Arr.from(toolCalls.map { tc =>
            ujson.Obj(
              "id"   -> tc.id,
              "type" -> "function",
              "function" -> ujson.Obj(
                "name"      -> tc.name,
                "arguments" -> tc.arguments.render()
              )
            )
          })
        }
        base
      case ToolMessage(content, toolCallId) =>
        ujson.Obj(
          "role"         -> "tool",
          "tool_call_id" -> toolCallId,
          "content"      -> content
        )
    }

    val base = ujson.Obj(
      "model"       -> config.model,
      "messages"    -> ujson.Arr.from(messages),
      "temperature" -> options.temperature
    )

    options.maxTokens.foreach(mt => base("max_tokens") = mt)
    if (options.presencePenalty != 0) base("presence_penalty") = options.presencePenalty
    if (options.frequencyPenalty != 0) base("frequency_penalty") = options.frequencyPenalty

    if (options.tools.nonEmpty) {
      val toolRegistry = new ToolRegistry(options.tools)
      base("tools") = toolRegistry.getOpenAITools()
    }

    base
  }

  private def parseChatResponse(body: String): Result[Completion] =
    Try {
      val json   = ujson.read(body)
      val choice = json("choices")(0)
      val msg    = choice("message")

      val contentStr = msg.obj.get("content").flatMap(_.strOpt).getOrElse("")

      val toolCalls = msg.obj
        .get("tool_calls")
        .map(parseToolCalls)
        .getOrElse(Seq.empty)

      val usageOpt = json.obj.get("usage").flatMap { u =>
        for {
          pt  <- u.obj.get("prompt_tokens").flatMap(_.numOpt).map(_.toInt)
          ct  <- u.obj.get("completion_tokens").flatMap(_.numOpt).map(_.toInt)
          tot <- u.obj.get("total_tokens").flatMap(_.numOpt).map(_.toInt)
        } yield TokenUsage(promptTokens = pt, completionTokens = ct, totalTokens = tot)
      }

      val costOpt = usageOpt.flatMap(u => CostEstimator.estimate(config.model, u))

      val id      = json.obj.get("id").flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString)
      val created = json.obj.get("created").flatMap(_.numOpt).map(_.toLong).getOrElse(System.currentTimeMillis() / 1000)

      Completion(
        id = id,
        created = created,
        content = contentStr,
        model = config.model,
        message = AssistantMessage(contentOpt = Some(contentStr), toolCalls = toolCalls.toList),
        toolCalls = toolCalls.toList,
        usage = usageOpt,
        thinking = None,
        estimatedCost = costOpt
      )
    }.toEither.left.map(_.toLLMError)

  private def parseToolCalls(toolCallsJson: ujson.Value): Seq[ToolCall] =
    toolCallsJson.arr.map { call =>
      val function = call("function")
      val argsStr  = function.obj.get("arguments").flatMap(_.strOpt).getOrElse("{}")
      ToolCall(
        id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
        name = function.obj.get("name").flatMap(_.strOpt).getOrElse(""),
        arguments = Try(ujson.read(argsStr)).getOrElse(ujson.Obj())
      )
    }.toSeq

  private def parseStreamingChunks(json: ujson.Value): Seq[StreamedChunk] = {
    val choices = json("choices").arr
    if (choices.nonEmpty) {
      val choice = choices(0)
      val delta  = choice("delta")

      val content      = delta.obj.get("content").flatMap(_.strOpt)
      val finishReason = choice.obj.get("finish_reason").flatMap(_.strOpt).filter(_ != "null")

      val toolCalls = delta.obj.get("tool_calls").map(_.arr).getOrElse(Seq.empty).collect {
        case call if call.obj.contains("function") =>
          val function = call("function")
          val rawArgs  = function.obj.get("arguments").flatMap(_.strOpt).getOrElse("")
          ToolCall(
            id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
            name = function.obj.get("name").flatMap(_.strOpt).getOrElse(""),
            arguments = StreamingToolArgumentParser.parse(rawArgs)
          )
      }

      val chunkId = json.obj.get("id").flatMap(_.strOpt).getOrElse("")
      if (toolCalls.isEmpty) {
        Seq(
          StreamedChunk(
            id = chunkId,
            content = content,
            toolCall = None,
            finishReason = finishReason,
            thinkingDelta = None
          )
        )
      } else {
        val first = StreamedChunk(
          id = chunkId,
          content = content,
          toolCall = Some(toolCalls.head),
          finishReason = finishReason,
          thinkingDelta = None
        )
        val rest = toolCalls.drop(1).map { tc =>
          StreamedChunk(id = chunkId, content = None, toolCall = Some(tc), finishReason = None, thinkingDelta = None)
        }
        Seq(first) ++ rest
      }
    } else {
      Seq.empty
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

object TogetherAIClient {
  import org.llm4s.types.TryOps

  def apply(config: TogetherAIConfig)(using ModelRegistryService): Result[TogetherAIClient] =
    Try(new TogetherAIClient(config)).toResult

  def apply(
    config: TogetherAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector
  )(using ModelRegistryService): Result[TogetherAIClient] =
    Try(new TogetherAIClient(config, metrics)).toResult

  def apply(
    config: TogetherAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[TogetherAIClient] =
    Try(new TogetherAIClient(config, metrics, exchangeLogging)).toResult

  /** Test seam: injects a mock HTTP client without going through the `apply` factory. */
  private[provider] def forTest(
    config: TogetherAIConfig,
    httpClient: Llm4sHttpClient
  )(using ModelRegistryService): TogetherAIClient =
    new TogetherAIClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, httpClient)
}
