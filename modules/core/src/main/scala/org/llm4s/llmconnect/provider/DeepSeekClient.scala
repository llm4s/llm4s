package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.DeepSeekConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ProviderResultOps.*
import org.llm4s.llmconnect.streaming.{ SSEParser, StreamingAccumulator, StreamingToolArgumentParser }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.error.ThrowableOps._

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.time.Duration
import java.time.Instant
import java.io.{ BufferedReader, InputStreamReader }
import java.nio.charset.StandardCharsets
import scala.util.Try

/**
 * DeepSeek LLM client implementation using the OpenAI-compatible API.
 *
 * Provides access to DeepSeek models including DeepSeek-Chat (V3) with 64K context
 * and DeepSeek-Reasoner (R1) with 128K context for advanced reasoning tasks.
 *
 * Uses the same request/response format as OpenAI, making it compatible with standard
 * OpenAI tooling and client code patterns.
 *
 * @param config DeepSeek configuration containing API key, model, base URL, and context settings
 * @param metrics MetricsCollector for recording request metrics
 */
class DeepSeekClient(
  config: DeepSeekConfig,
  protected val metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient {

  private val httpClient = HttpClient.newHttpClient()
  private val logger     = org.slf4j.LoggerFactory.getLogger(getClass)

  protected def clientDescription: String = s"DeepSeek client for model ${config.model}"
  protected def providerName: String      = "deepseek"
  protected def modelName: String         = config.model

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = completeWithMetrics {
    val requestBody = createRequestBody(conversation, options)
    val requestText = requestBody.render()
    val startedAt   = Instant.now()

    logger.debug(s"Sending request to DeepSeek API at ${config.baseUrl}/chat/completions")

    val attempt =
      Try {
        val request = HttpRequest
          .newBuilder()
          .uri(URI.create(s"${config.baseUrl}/chat/completions"))
          .header("Content-Type", "application/json")
          .header("Authorization", s"Bearer ${config.apiKey}")
          .header("User-Agent", "llm4s/1.0")
          .POST(HttpRequest.BodyPublishers.ofString(requestText))
          .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        logger.debug(s"Response status: ${response.statusCode()}")

        response
      }.toEither.left
        .map(_.toLLMError)

    attempt.flatMap { response =>
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        val result = Try {
          val responseJson = ujson.read(response.body())
          parseCompletion(responseJson)
        }.toEither.left.map(_.toLLMError)
        recordExchange(startedAt, requestText, Some(response.body()), result)
        result
      } else {
        val errorResult = HttpErrorMapper.mapHttpError(response.statusCode(), response.body(), providerName)
        recordExchange(startedAt, requestText, Some(response.body()), errorResult)
        errorResult
      }
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = completeWithMetrics {
    val startedAt   = Instant.now()
    val requestBody = createRequestBody(conversation, options)
    requestBody("stream") = true
    val requestText = requestBody.render()

    val accumulator = StreamingAccumulator.create()
    val rawStream   = StringBuilder()

    val requestResult = Try {
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"${config.baseUrl}/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer ${config.apiKey}")
        .header("User-Agent", "llm4s/1.0")
        .timeout(Duration.ofMinutes(5))
        .POST(HttpRequest.BodyPublishers.ofString(requestText))
        .build()
      httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }.toResult
      .tapLeft(error =>
        recordExchange(startedAt, requestText, Option.when(rawStream.nonEmpty)(rawStream.result()), Left(error))
      )

    requestResult.flatMap { response =>
      if (response.statusCode() != 200) {
        val errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8)
        Try(response.body().close()) // Ensure stream is closed on error path
        val errorResult = HttpErrorMapper.mapHttpError(response.statusCode(), errorBody, providerName)
        recordExchange(startedAt, requestText, Some(errorBody), errorResult)
        errorResult
      } else {
        val sseParser = SSEParser.createStreamingParser()
        val reader    = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
        val loopTry = Try {
          Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach { line =>
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
        }
        Try(reader.close()); Try(response.body().close())
        loopTry.toResult
          .flatMap(_ =>
            accumulator.toCompletion.map { c =>
              val cost       = c.usage.flatMap(u => CostEstimator.estimate(config.model, u))
              val completion = c.copy(model = config.model, estimatedCost = cost)
              recordExchange(startedAt, requestText, Some(rawStream.result()), Right(completion))
              completion
            }
          )
          .tapLeft(error =>
            recordExchange(startedAt, requestText, Option.when(rawStream.nonEmpty)(rawStream.result()), Left(error))
          )
      }
    }
  }

  private def parseStreamingChunks(json: ujson.Value): Seq[StreamedChunk] = {
    val choices = json("choices").arr
    if (choices.nonEmpty) {
      val choice = choices(0)
      val delta  = choice("delta")

      val content = delta.obj.get("content").flatMap(_.strOpt)

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

  private[provider] def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
    val messages = conversation.messages.map {
      case UserMessage(content) =>
        ujson.Obj("role" -> "user", "content" -> content)
      case SystemMessage(content) =>
        ujson.Obj("role" -> "system", "content" -> content)
      case AssistantMessage(content, toolCalls) =>
        val base = ujson.Obj("role" -> "assistant")
        content.filter(_.nonEmpty).foreach(c => base("content") = c)
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

    base
  }

  private def parseCompletion(json: ujson.Value): Completion = {
    val choice  = json("choices")(0)
    val message = choice("message")

    val toolCalls = message.obj
      .get("tool_calls")
      .map(parseToolCalls)
      .getOrElse(Seq.empty)

    val contentStr = message.obj.get("content").flatMap(_.strOpt).getOrElse("")

    val usage = json.obj.get("usage").flatMap { u =>
      u.objOpt.map { usageObj =>
        TokenUsage(
          promptTokens = usageObj("prompt_tokens").num.toInt,
          completionTokens = usageObj("completion_tokens").num.toInt,
          totalTokens = usageObj("total_tokens").num.toInt
        )
      }
    }

    // Estimate cost using CostEstimator
    val modelId = json("model").str
    val cost    = usage.flatMap(u => CostEstimator.estimate(config.model, u))

    Completion(
      id = json("id").str,
      created = json("created").num.toLong,
      content = contentStr,
      model = modelId,
      message = AssistantMessage(
        contentOpt = Some(contentStr),
        toolCalls = toolCalls.toList
      ),
      toolCalls = toolCalls.toList,
      usage = usage,
      thinking = None,
      estimatedCost = cost
    )
  }

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

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  override protected def releaseResources(): Unit =
    (httpClient: Any) match {
      case c: AutoCloseable => c.close()
      case _                => ()
    }
}

object DeepSeekClient {
  import org.llm4s.types.TryOps

  def apply(
    config: DeepSeekConfig,
    metrics: MetricsCollector,
    exchangeLogging: ProviderExchangeLogging
  )(using ModelRegistryService): Result[DeepSeekClient] =
    Try(new DeepSeekClient(config, metrics, exchangeLogging)).toResult

  def apply(config: DeepSeekConfig, metrics: MetricsCollector)(using ModelRegistryService): Result[DeepSeekClient] =
    Try(new DeepSeekClient(config, metrics)).toResult

  def apply(config: DeepSeekConfig)(using ModelRegistryService): Result[DeepSeekClient] =
    Try(new DeepSeekClient(config, MetricsCollector.noop)).toResult
}
