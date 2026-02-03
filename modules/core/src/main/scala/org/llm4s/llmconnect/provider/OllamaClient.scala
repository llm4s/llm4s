package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.llm4s.types.Result

import java.io.{ BufferedReader, InputStreamReader }
import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.time.Duration
import scala.util.Try

class OllamaClient(
  config: OllamaConfig,
  protected val metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
) extends LLMClient
    with MetricsRecording {
  private val httpClient = HttpClient.newHttpClient()

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = withMetrics("ollama", config.model) {
    connect(conversation, options)
  }(
    extractUsage = _.usage,
    estimateCost = usage =>
      org.llm4s.model.ModelRegistry.lookup(config.model).toOption.flatMap { meta =>
        meta.pricing.estimateCost(usage.promptTokens, usage.completionTokens)
      }
  )

  private def connect(conversation: Conversation, options: CompletionOptions) = {
    val requestBody = createRequestBody(conversation, options, stream = false)
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl}/api/chat"))
      .header("Content-Type", "application/json")
      .timeout(Duration.ofMinutes(2))
      .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

    response.statusCode() match {
      case 200 =>
        val json = ujson.read(response.body())
        Right(parseCompletion(json))
      case status =>
        Left(ServiceError(status, "ollama", s"Ollama error: ${response.body()}"))
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = withMetrics("ollama", config.model) {
    val requestBody = createRequestBody(conversation, options, stream = true)
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"${config.baseUrl}/api/chat"))
      .header("Content-Type", "application/json")
      .timeout(Duration.ofMinutes(10))
      .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() != 200) {
      val err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8)
      response.body().close()
      response.statusCode() match {
        case 401 => Left(AuthenticationError("ollama", "Unauthorized"))
        case 429 => Left(RateLimitError("ollama"))
        case s   => Left(ServiceError(s, "ollama", s"Ollama error: $err"))
      }
    } else {
      val accumulator = StreamingAccumulator.create()
      val reader      = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))
      val processEither = Try {
        try {
          var line: String = null
          while ({ line = reader.readLine(); line != null }) {
            val trimmed = line.trim
            if (trimmed.nonEmpty) {
              val json = ujson.read(trimmed)
              // Ollama streams incremental content in json lines
              val done = json.obj.get("done").exists(_.bool)
              val contentOpt = json.obj
                .get("message")
                .flatMap(_.obj.get("content"))
                .flatMap(_.strOpt)
                .filter(_.nonEmpty)

              val chunk = StreamedChunk(
                id = json.obj.get("id").flatMap(_.strOpt).getOrElse(""),
                content = contentOpt,
                toolCall = None,
                finishReason = if (done) Some("stop") else None
              )

              accumulator.addChunk(chunk)
              onChunk(chunk)

              // token counts (if present) only appear at the end
              if (done) {
                val prompt = json.obj.get("prompt_eval_count").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
                val comp   = json.obj.get("eval_count").flatMap(_.numOpt).map(_.toInt).getOrElse(0)
                if (prompt > 0 || comp > 0) accumulator.updateTokens(prompt, comp)
              }
            }
          }
        } finally {
          Try(reader.close())
          Try(response.body().close())
        }
      }.toEither
      processEither.left.foreach(_ => ())

      accumulator.toCompletion
    }
  }(
    extractUsage = _.usage,
    estimateCost = usage =>
      org.llm4s.model.ModelRegistry.lookup(config.model).toOption.flatMap { meta =>
        meta.pricing.estimateCost(usage.promptTokens.toInt, usage.completionTokens.toInt)
      }
  )

  private def createRequestBody(
    conversation: Conversation,
    options: CompletionOptions,
    stream: Boolean
  ): ujson.Obj = {
    val msgs = ujson.Arr.from(conversation.messages.collect {
      case SystemMessage(content) => ujson.Obj("role" -> "system", "content" -> content)
      case UserMessage(content)   => ujson.Obj("role" -> "user", "content" -> content)
      case am: AssistantMessage   => ujson.Obj("role" -> "assistant", "content" -> am.content)
      // Tool messages are not supported by Ollama chat API; drop them
    })

    val opts = ujson.Obj(
      "temperature" -> options.temperature,
      "top_p"       -> options.topP
    )
    options.maxTokens.foreach(t => opts("num_predict") = t)

    ujson.Obj(
      "model"    -> config.model,
      "messages" -> msgs,
      "stream"   -> stream,
      "options"  -> opts
    )
  }

  private def parseCompletion(json: ujson.Value): Completion = {
    val id      = json.obj.get("id").flatMap(_.strOpt).getOrElse(java.util.UUID.randomUUID().toString)
    val created = System.currentTimeMillis() / 1000
    val content = json.obj
      .get("message")
      .flatMap(_.obj.get("content"))
      .flatMap(_.strOpt)
      .getOrElse("")

    val usage = (for {
      prompt <- json.obj.get("prompt_eval_count").flatMap(_.numOpt).map(_.toInt)
      comp   <- json.obj.get("eval_count").flatMap(_.numOpt).map(_.toInt)
    } yield TokenUsage(prompt, comp, prompt + comp)).orElse(None)

    Completion(
      id = id,
      created = created,
      content = content,
      toolCalls = List.empty,
      usage = usage,
      model = config.model,
      message = AssistantMessage(content)
    )
  }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion
}

object OllamaClient {
  import org.llm4s.types.TryOps

  def apply(
    config: OllamaConfig,
    metrics: org.llm4s.metrics.MetricsCollector = org.llm4s.metrics.MetricsCollector.noop
  ): Result[OllamaClient] =
    Try(new OllamaClient(config, metrics)).toResult
}
