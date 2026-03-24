package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.serialization.OpenRouterToolCallDeserializer
import org.llm4s.llmconnect.streaming.{ SSEParser, StreamingAccumulator, StreamingToolArgumentParser }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.llm4s.error.ThrowableOps._

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.time.Duration
import java.time.Instant
import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * [[LLMClient]] implementation for the OpenRouter unified model gateway.
 *
 * Sends requests to the OpenRouter REST API using the OpenAI-compatible
 * `/chat/completions` endpoint. Accepts `OpenAIConfig` — there is no
 * separate `OpenRouterConfig`; `LLMConnect` detects OpenRouter by checking
 * whether `baseUrl` contains `"openrouter.ai"` and routes accordingly.
 *
 * == Required headers ==
 *
 * OpenRouter's usage policy requires two additional headers on every
 * request. This client sends them automatically:
 *  - `HTTP-Referer: https://github.com/llm4s/llm4s`
 *  - `X-Title: LLM4S`
 *
 * == Reasoning / extended thinking ==
 *
 * Model type is detected by substring matching on the lower-cased model name:
 *  - Names containing `"claude"` or `"anthropic"` → Anthropic-style
 *    `thinking` object (`type: "enabled"`, `budget_tokens`).
 *  - Names containing `"o1"`, `"o3"`, or `"o4"` → OpenAI-style
 *    `reasoning_effort` string parameter.
 *  - All other models → reasoning configuration is silently omitted.
 *
 * The thinking budget is clamped to `[1024, maxTokens - 1]` for Anthropic
 * models, matching the Anthropic API constraint.
 *
 * == Thinking content ==
 *
 * Extended thinking text is extracted from whichever field the model
 * populates: `message.thinking`, `message.reasoning`, or
 * `choice.thinking` (checked in that order).
 *
 * @param config  `OpenAIConfig` whose `baseUrl` must contain `"openrouter.ai"`;
 *                carries the API key and model name.
 * @param metrics Receives per-call latency and token-usage events.
 *                Defaults to `MetricsCollector.noop`.
 */
class OpenRouterClient(
  config: OpenAIConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
) extends BaseLifecycleLLMClient {
  private val httpClient = HttpClient.newHttpClient()

  protected def clientDescription: String = s"OpenRouter client for model ${config.model}"
  protected def providerName: String      = "openrouter"
  protected def modelName: String         = config.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    // Wrap entire body in Try so createRequestBody returns Left(error)
    // instead of throwing RuntimeException.
    val result = Try {
      createRequestBody(conversation, options)
    }.toEither.left.map(_.toLLMError).flatMap { requestBody =>
      val requestText = requestBody.render()
      val attempt =
        Try {
          val request = HttpRequest
            .newBuilder()
            .uri(URI.create(s"${config.baseUrl}/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", s"Bearer ${config.apiKey}")
            .header("HTTP-Referer", "https://github.com/llm4s/llm4s") // Required by OpenRouter
            .header("X-Title", "LLM4S")                               // Required by OpenRouter
            .POST(HttpRequest.BodyPublishers.ofString(requestText))
            .build()

          httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }.toEither.left
          .map(_.toLLMError)

      attempt.flatMap { response =>
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          Try(ujson.read(response.body())).toEither.left.map(_.toLLMError).flatMap { responseJson =>
            val completionResult = Try(parseCompletion(responseJson)).toEither.left.map(_.toLLMError)
            recordExchange(startedAt, requestText, Some(response.body()), completionResult)
            completionResult
          }
        } else {
          val errorResult = HttpErrorMapper.mapHttpError(response.statusCode(), response.body(), providerName)
          recordExchange(startedAt, requestText, Some(response.body()), errorResult)
          errorResult
        }
      }
    }
    result.left.map { error =>
      recordExchange(startedAt, "", None, Left(error))
      error
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt = Instant.now()
    // Wrap entire body in Try so createRequestBody and other unprotected
    // code paths return Left(error) instead of throwing RuntimeException.
    Try {
      val requestBody = createRequestBody(conversation, options)
      requestBody("stream") = true
      (
        requestBody.render(),
        StreamingAccumulator.create(),
        StringBuilder()
      )
    }.toEither.left.map(_.toLLMError).flatMap { case (requestText, streamAccumulator, rawStream) =>
      // Send the HTTP request, converting transport exceptions to Left
      val responseOrError = Try {
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"${config.baseUrl}/chat/completions"))
          .header("Content-Type", "application/json")
          .header("Authorization", s"Bearer ${config.apiKey}")
          .header("HTTP-Referer", "https://github.com/llm4s/llm4s")
          .header("X-Title", "LLM4S")
          .timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofString(requestText))
          .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
      }.toEither.left.map { error =>
        val llmError = error.toLLMError
        recordExchange(startedAt, requestText, Option.when(rawStream.nonEmpty)(rawStream.result()), Left(llmError))
        llmError
      }

      // Check HTTP status, returning typed errors for known failure codes
      val streamOrError = responseOrError.flatMap { response =>
        if (response.statusCode() == 200) {
          Right(response)
        } else {
          val errorBody = Try(new String(response.body().readAllBytes(), StandardCharsets.UTF_8))
            .getOrElse("<error body unreadable>")
          val errorResult = HttpErrorMapper.mapHttpError(response.statusCode(), errorBody, providerName)
          recordExchange(startedAt, requestText, Some(errorBody), errorResult)
          errorResult
        }
      }

      // Process the SSE stream, converting any I/O exceptions to Left
      val attempt = streamOrError.flatMap { response =>
        Try {
          val sseParser = SSEParser.createStreamingParser()
          val reader    = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
          try {
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
                        streamAccumulator.addChunk(c)
                        onChunk(c)
                      }
                    }
                  }
                }
            }
          } finally {
            Try(reader.close())
            Try(response.body().close())
          }
        }.toEither.left.map(_.toLLMError)
      }

      attempt
        .flatMap(_ =>
          streamAccumulator.toCompletion.map { c =>
            val cost       = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
            val completion = c.copy(model = config.model, estimatedCost = cost)
            recordExchange(startedAt, requestText, Some(rawStream.result()), Right(completion))
            completion
          }
        )
        .left
        .map { error =>
          recordExchange(startedAt, requestText, Option.when(rawStream.nonEmpty)(rawStream.result()), Left(error))
          error
        }
    }
  }

  private def parseStreamingChunks(json: ujson.Value): Seq[StreamedChunk] = {
    val choices = json("choices").arr
    if (choices.nonEmpty) {
      val choice = choices(0)
      val delta  = choice("delta")

      val content      = delta.obj.get("content").flatMap(_.strOpt)
      val finishReason = choice.obj.get("finish_reason").flatMap(_.strOpt).filter(_ != "null")

      // Handle thinking content delta if present
      val thinkingDelta = delta.obj
        .get("thinking")
        .flatMap(_.strOpt)
        .orElse(delta.obj.get("reasoning").flatMap(_.strOpt))

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
            thinkingDelta = thinkingDelta
          )
        )
      } else {
        val first = StreamedChunk(
          id = chunkId,
          content = content,
          toolCall = Some(toolCalls.head),
          finishReason = finishReason,
          thinkingDelta = thinkingDelta
        )
        val rest = toolCalls.drop(1).map { tc =>
          StreamedChunk(
            id = chunkId,
            content = None,
            toolCall = Some(tc),
            finishReason = None,
            thinkingDelta = None
          )
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
    result: Result[Completion]
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

  /**
   * Test-visible seam for request serialization; intentionally scoped to provider package to avoid broader API surface.
   */
  protected[provider] def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val messages = conversation.messages.map {
      case UserMessage(content) =>
        ujson.Obj("role" -> "user", "content" -> content)
      case SystemMessage(content) =>
        ujson.Obj("role" -> "system", "content" -> content)
      case AssistantMessage(content, toolCalls) =>
        val base = ujson.Obj("role" -> "assistant", "content" -> content)
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
      "temperature" -> options.temperature,
      "top_p"       -> options.topP
    )

    options.maxTokens.foreach(mt => base("max_tokens") = mt)
    if (options.presencePenalty != 0) base("presence_penalty") = options.presencePenalty
    if (options.frequencyPenalty != 0) base("frequency_penalty") = options.frequencyPenalty

    if (options.tools.nonEmpty) {
      val toolRegistry = new ToolRegistry(options.tools)
      base("tools") = toolRegistry.getOpenAITools()
    }

    options.responseFormat.foreach { fmt =>
      ResponseFormatMapper.toOpenAIResponseFormat(fmt).foreach(rf => base("response_format") = rf)
    }

    // Add reasoning configuration based on model type
    addReasoningConfig(base, options)

    base
  }

  /**
   * Appends provider-specific reasoning fields to `base` when reasoning is enabled.
   *
   * Model type is detected by substring matching on the lower-cased model name.
   * Anthropic models receive a `thinking` object; OpenAI o1/o3/o4 models receive
   * `reasoning_effort`; all others are left unchanged (reasoning silently ignored).
   */
  private def addReasoningConfig(base: ujson.Obj, options: CompletionOptions): Unit = {
    val modelLower = config.model.toLowerCase

    // Check if reasoning is requested
    options.reasoning.foreach { effort =>
      if (effort != ReasoningEffort.None) {
        // Determine if this is an Anthropic or OpenAI model
        val isAnthropicModel = modelLower.contains("claude") || modelLower.contains("anthropic")
        val isOpenAIReasoningModel =
          modelLower.contains("o1") || modelLower.contains("o3") || modelLower.contains("o4")

        if (isAnthropicModel) {
          // Anthropic extended thinking via `thinking` parameter
          val maxTokens       = options.maxTokens.getOrElse(2048)
          val budgetTokens    = options.effectiveBudgetTokens.getOrElse(ReasoningEffort.defaultBudgetTokens(effort))
          val effectiveBudget = math.max(1024, math.min(budgetTokens, maxTokens - 1))
          base("thinking") = ujson.Obj(
            "type"          -> "enabled",
            "budget_tokens" -> effectiveBudget
          )
        } else if (isOpenAIReasoningModel) {
          // OpenAI reasoning_effort parameter for o1/o3 models
          base("reasoning_effort") = effort.name
        }
        // For other models, reasoning is silently ignored
      }
    }

    // Also support explicit budget tokens without reasoning effort set
    if (options.reasoning.isEmpty) {
      options.budgetTokens.foreach { budgetTokens =>
        val modelLower       = config.model.toLowerCase
        val isAnthropicModel = modelLower.contains("claude") || modelLower.contains("anthropic")
        if (isAnthropicModel && budgetTokens > 0) {
          val maxTokens       = options.maxTokens.getOrElse(2048)
          val effectiveBudget = math.max(1024, math.min(budgetTokens, maxTokens - 1))
          base("thinking") = ujson.Obj(
            "type"          -> "enabled",
            "budget_tokens" -> effectiveBudget
          )
        }
      }
    }
  }

  private def parseCompletion(json: ujson.Value): Completion = {
    val choice  = json("choices")(0)
    val message = choice("message")

    // Extract tool calls if present
    val toolCalls = Option(message.obj.get("tool_calls"))
      .map(tc => OpenRouterToolCallDeserializer.deserializeToolCalls(tc))
      .getOrElse(Seq.empty)

    // Extract thinking content if present (for models that support extended thinking)
    // OpenRouter may return thinking in the message or in a separate field
    val thinking = message.obj
      .get("thinking")
      .flatMap(_.strOpt)
      .orElse(message.obj.get("reasoning").flatMap(_.strOpt))
      .orElse(choice.obj.get("thinking").flatMap(_.strOpt))

    val usage = Option(json.obj.get("usage")).flatMap { u =>
      val usageObjOpt =
        u.objOpt.orElse(u.arrOpt.flatMap(_.headOption.flatMap(_.objOpt)))
      usageObjOpt.map { usageObj =>
        // Extract base token usage
        val baseUsage = TokenUsage(
          promptTokens = usageObj.value("prompt_tokens").num.toInt,
          completionTokens = usageObj.value("completion_tokens").num.toInt,
          totalTokens = usageObj.value("total_tokens").num.toInt
        )
        // Check for thinking tokens (OpenRouter may include reasoning_tokens)
        val thinkingTokens = usageObj.value.get("reasoning_tokens").flatMap(_.numOpt).map(_.toInt)
        if (thinkingTokens.isDefined) {
          baseUsage.copy(thinkingTokens = thinkingTokens)
        } else {
          baseUsage
        }
      }
    }

    // Estimate cost using CostEstimator
    val modelId = json("model").str
    val cost    = usage.flatMap(u => CostEstimator.estimate(config.model, u))

    Completion(
      id = json("id").str,
      created = json("created").num.toLong,
      content = message.obj.get("content").flatMap(_.strOpt).getOrElse(""),
      model = modelId,
      message = AssistantMessage(
        contentOpt = message.obj.get("content").flatMap(_.strOpt),
        toolCalls = toolCalls.toList
      ),
      toolCalls = toolCalls.toList,
      usage = usage,
      thinking = thinking,
      estimatedCost = cost
    )
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  override protected def releaseResources(): Unit =
    (httpClient: Any) match {
      case c: AutoCloseable => c.close()
      case _                => ()
    }
}

object OpenRouterClient {
  import org.llm4s.types.TryOps

  /**
   * Constructs an [[OpenRouterClient]], wrapping any construction-time
   * exception in a `Left`.
   *
   * @param config  `OpenAIConfig` with the OpenRouter API key, model, and
   *                a `baseUrl` that contains `"openrouter.ai"`.
   * @param metrics Receives per-call latency and token-usage events.
   *                Defaults to `MetricsCollector.noop`.
   * @return `Right(client)` on success; `Left(LLMError)` if construction fails.
   */
  def apply(
    config: OpenAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
  ): Result[OpenRouterClient] =
    Try(new OpenRouterClient(config, metrics)).toResult

  def apply(
    config: OpenAIConfig,
    metrics: org.llm4s.metrics.MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  ): Result[OpenRouterClient] =
    Try(new OpenRouterClient(config, metrics, exchangeLogging)).toResult
}
