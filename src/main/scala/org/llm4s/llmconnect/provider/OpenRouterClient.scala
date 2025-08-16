package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.llm4s.error.LLMError

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }

class OpenRouterClient(config: OpenAIConfig) extends LLMClient {
  private val httpClient = HttpClient.newHttpClient()

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] =
    try {
      // Convert conversation to OpenRouter format
      val requestBody = createRequestBody(conversation, options)

      // Make API call
      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"${config.baseUrl}/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer ${config.apiKey}")
        .header("HTTP-Referer", "https://github.com/llm4s/llm4s") // Required by OpenRouter
        .header("X-Title", "LLM4S")                               // Required by OpenRouter
        .POST(HttpRequest.BodyPublishers.ofString(requestBody.render()))
        .build()

      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

      // Handle response status
      response.statusCode() match {
        case 200 =>
          // Parse successful response
          val responseJson = ujson.read(response.body())
          Right(parseCompletion(responseJson))

        case 401    => Left(LLMError.AuthenticationError("Invalid API key", "openrouter"))
        case 429    => Left(LLMError.RateLimitError("Rate limit exceeded", None, "openrouter"))
        case status => Left(LLMError.ServiceError(s"OpenRouter API error: ${response.body()}", status, "openrouter"))
      }
    } catch {
      case e: Exception => Left(LLMError.fromThrowable(e))
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    // Simplified implementation for now
    complete(conversation, options)

  private def createRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj = {
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
                "arguments" -> tc.arguments
              )
            )
          })
        }
        base
      case ToolMessage(toolCallId, content) =>
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

    base
  }

  private def parseCompletion(json: ujson.Value): Completion = {
    val choice  = json("choices")(0)
    val message = choice("message")

    // Extract tool calls if present
    val toolCalls = Option(message.obj.get("tool_calls"))
      .map { tc =>
        tc.arr.map { call =>
          ToolCall(
            id = call("id").str,
            name = call("function")("name").str,
            arguments = call("function")("arguments")
          )
        }.toSeq
      }
      .getOrElse(Seq.empty)

    val usage = Option(json.obj.get("usage")).flatMap { u =>
      val usageObjOpt =
        u.objOpt.orElse(u.arrOpt.flatMap(_.headOption.flatMap(_.objOpt)))
      usageObjOpt.map { usageObj =>
        TokenUsage(
          promptTokens = usageObj.value("prompt_tokens").num.toInt,
          completionTokens = usageObj.value("completion_tokens").num.toInt,
          totalTokens = usageObj.value("total_tokens").num.toInt
        )
      }
    }

    Completion(
      id = json("id").str,
      created = json("created").num.toLong,
      message = AssistantMessage(
        contentOpt = message("content").strOpt,
        toolCalls = toolCalls
      ),
      usage = usage
    )
  }
}
