package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * OpenRouter's dialect on the shared client (#1132): its headers, per-model reasoning
 * parameters, thinking extraction, strict tool-call parsing, and assistant content that is
 * always sent - now as a string, where the old client sent a JSON array by accident.
 */
class OpenRouterDialectSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def config(model: String, baseUrl: String = "https://openrouter.ai/api/v1") =
    OpenAIConfig("or-key", model, None, baseUrl, 200000, 4096)

  private def body(model: String, options: CompletionOptions, messages: Message*) =
    new OpenRouterClient(config(model)).createRequestBody(Conversation(messages.toSeq), options)

  private val hi = UserMessage("hi")

  "OpenRouterClient requests" should "send the referer and title headers with the key" in
    withServer("/chat/completions") { exchange =>
      val headers = exchange.getRequestHeaders
      headers.getFirst("Authorization") shouldBe "Bearer or-key"
      headers.getFirst("HTTP-Referer") shouldBe "https://github.com/llm4s/llm4s"
      headers.getFirst("X-Title") shouldBe "LLM4S"
      sendJsonResponse(exchange, 200, openAICompletion("ok"))
    } { baseUrl =>
      new OpenRouterClient(config("openai/gpt-4o", baseUrl)).complete(Conversation(Seq(hi)), CompletionOptions()).value
    }

  it should "always send assistant content, as a string" in {
    val messages = body(
      "openai/gpt-4o",
      CompletionOptions(),
      AssistantMessage(Some("text")),
      AssistantMessage(Some("")),
      AssistantMessage(None, List(ToolCall("c", "f", ujson.Obj())))
    )("messages")
    messages(0)("content") shouldBe ujson.Str("text")
    messages(1)("content") shouldBe ujson.Str("")
    messages(2)("content") shouldBe ujson.Null
  }

  it should "ask Anthropic models for a clamped thinking budget" in {
    val high = body(
      "anthropic/claude-3.5-sonnet",
      CompletionOptions(maxTokens = Some(4096)).withReasoning(ReasoningEffort.High),
      hi
    )
    high("thinking") shouldBe ujson.Obj("type" -> "enabled", "budget_tokens" -> 4095)

    val low = body("anthropic/claude-3.5-sonnet", CompletionOptions().withBudgetTokens(10), hi)
    low("thinking")("budget_tokens").num shouldBe 1024
  }

  it should "ask o-series models for a reasoning effort" in {
    body("openai/o3-mini", CompletionOptions().withReasoning(ReasoningEffort.Medium), hi)("reasoning_effort") shouldBe
      ujson.Str(ReasoningEffort.Medium.name)
  }

  it should "send no reasoning fields for other models, for effort None, or for a budget alone on a non-Anthropic model" in {
    val keys = Seq(
      body("meta-llama/llama-3-70b", CompletionOptions().withReasoning(ReasoningEffort.High), hi),
      body("anthropic/claude-3.5-sonnet", CompletionOptions().withReasoning(ReasoningEffort.None), hi),
      body("openai/o3-mini", CompletionOptions().withBudgetTokens(4096), hi)
    ).flatMap(_.obj.keySet)
    keys should contain noneOf ("thinking", "reasoning_effort")
  }

  "OpenRouterClient replies" should "read thinking from the message, then the choice, and reasoning tokens" in {
    val client = new OpenRouterClient(config("anthropic/claude-3.5-sonnet"))
    def reply(message: String, choiceExtra: String = "") =
      ujson.read(
        s"""{"id":"x","created":1,"model":"m","choices":[{"message":$message$choiceExtra}],
           |"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3,"reasoning_tokens":7}}""".stripMargin
      )

    client.parseCompletion(reply("""{"content":"a","thinking":"t1"}""")).thinking shouldBe Some("t1")
    client.parseCompletion(reply("""{"content":"a","reasoning":"t2"}""")).thinking shouldBe Some("t2")
    client.parseCompletion(reply("""{"content":"a"}""", ""","thinking":"t3"""")).thinking shouldBe Some("t3")
    client.parseCompletion(reply("""{"content":"a"}""")).usage.flatMap(_.thinkingTokens) shouldBe Some(7)
  }

  it should "stream thinking deltas" in
    withServer("/chat/completions") { exchange =>
      val events = Seq(
        """{"id":"s","choices":[{"delta":{"reasoning":"hmm "}}]}""",
        """{"id":"s","choices":[{"delta":{"thinking":"ok"}}]}""",
        """{"id":"s","choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}"""
      )
      sendSseResponse(exchange, events.map(e => s"data: $e\n\n").mkString + "data: [DONE]\n\n")
    } { baseUrl =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val completion = new OpenRouterClient(config("anthropic/claude-3.5-sonnet", baseUrl))
        .streamComplete(Conversation(Seq(hi)), CompletionOptions(), chunks += _)
        .value
      chunks.flatMap(_.thinkingDelta) shouldBe Seq("hmm ", "ok")
      completion.thinking shouldBe Some("hmm ok")
      completion.content shouldBe "done"
    }

  it should "parse tool calls strictly, failing the completion on malformed arguments" in
    withServer("/chat/completions") { exchange =>
      sendJsonResponse(
        exchange,
        200,
        """{"id":"x","created":1,"model":"m","choices":[{"message":{"content":null,
          |"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{not json"}}]}}]}""".stripMargin
      )
    } { baseUrl =>
      new OpenRouterClient(config("openai/gpt-4o", baseUrl))
        .complete(Conversation(Seq(hi)), CompletionOptions())
        .isLeft shouldBe true
    }

  it should "parse well-formed tool calls" in {
    val completion = new OpenRouterClient(config("openai/gpt-4o")).parseCompletion(
      ujson.read(
        """{"id":"x","created":1,"model":"m","choices":[{"message":{"content":null,
          |"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{\"a\":1}"}}]}}]}""".stripMargin
      )
    )
    completion.toolCalls shouldBe Seq(ToolCall("c1", "f", ujson.Obj("a" -> 1)))
    completion.message.contentOpt shouldBe None
  }
}
