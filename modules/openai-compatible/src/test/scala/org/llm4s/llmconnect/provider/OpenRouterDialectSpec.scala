package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
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
    OpenAIConfig(Secret("or-key"), ModelName(model), None, BaseUrl(baseUrl), 200000, 4096)

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
      AssistantMessage(None, List(ToolCall(ToolCallId("c"), "f", ujson.Obj())))
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

    val low =
      body("anthropic/claude-3.5-sonnet", CompletionOptions().withThinking(Thinking.budget(10).toOption.get), hi)
    low("thinking")("budget_tokens").num shouldBe 1024
  }

  // #2933: with no maxTokens the request carries no max_tokens, so there is no limit of the caller's to fit a
  // budget under. The client used to fit it under 2048 all the same; it now fits it under the model's maximum
  // output, and under 32000, the smallest any thinking Claude model has, when the registry does not state it.
  it should "fit a reasoning level's budget under 32000 when maxTokens is unset and the registry does not know the model" in {
    val unknown = "anthropic/claude-3.5-sonnet"
    summon[ModelRegistryService].lookup(OpenRouterDialect.ProviderId, ModelName(unknown)).isLeft shouldBe true

    def budget(effort: ReasoningEffort) = {
      val sent = body(unknown, CompletionOptions().withReasoning(effort), hi)
      sent.obj.keySet should not contain "max_tokens"
      sent("thinking")("type") shouldBe ujson.Str("enabled")
      sent("thinking")("budget_tokens").num.toInt
    }
    budget(ReasoningEffort.Minimal) shouldBe 1024
    budget(ReasoningEffort.Low) shouldBe 2048
    budget(ReasoningEffort.Medium) shouldBe 8192
    budget(ReasoningEffort.High) shouldBe 31999
    budget(ReasoningEffort.XHigh) shouldBe 31999
    budget(ReasoningEffort.Max) shouldBe 31999
  }

  it should "report a budget fitted under the assumed 32000 as Adjusted, saying how to lift it" in {
    val unknown = "anthropic/claude-3.5-sonnet"
    val reason =
      "The model registry states no maximum output for anthropic/claude-3.5-sonnet, so the thinking budget is kept " +
        "below 32000, the smallest maximum output of a Claude model that thinks: set maxTokens, or give the model a " +
        "max_output_tokens in the model registry, to send a larger one"
    OpenRouterThinkingBudget.UnknownModelMaxOutput shouldBe 32000

    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 200, openAICompletion("ok"))) { baseUrl =>
      val client                                  = new OpenRouterClient(config(unknown, baseUrl))
      def adjustments(options: CompletionOptions) = client.complete(Conversation(Seq(hi)), options).value.adjustments
      def level(budget: Int) =
        List(
          RequestAdjustment.Adjusted(RequestOption.Reasoning, s"budget_tokens $budget", "budget_tokens 31999", reason)
        )

      adjustments(CompletionOptions().withReasoning(ReasoningEffort.Medium)) shouldBe empty
      adjustments(CompletionOptions().withReasoning(ReasoningEffort.High)) shouldBe level(32768)
      adjustments(CompletionOptions().withReasoning(ReasoningEffort.Max)) shouldBe level(65536)

      // an explicit budget: as asked up to 31999, fitted and reported above it
      val within = CompletionOptions().withThinking(Thinking.budget(31999).toOption.get)
      client.createRequestBody(Conversation(Seq(hi)), within)("thinking")("budget_tokens").num shouldBe 31999
      adjustments(within) shouldBe empty

      val above = CompletionOptions().withThinking(Thinking.budget(50000).toOption.get)
      client.createRequestBody(Conversation(Seq(hi)), above)("thinking")("budget_tokens").num shouldBe 31999
      adjustments(above) shouldBe List(
        RequestAdjustment.Adjusted(RequestOption.Thinking, "Budget(50000)", "Budget(31999)", reason)
      )

      // maxTokens lifts it, and a level fitted under the caller's own limit is not reported, as before
      val lifted = CompletionOptions(maxTokens = Some(64000)).withReasoning(ReasoningEffort.Max)
      client.createRequestBody(Conversation(Seq(hi)), lifted)("thinking")("budget_tokens").num shouldBe 63999
      adjustments(lifted) shouldBe empty
    }
  }

  it should "fit a budget under the model's maximum output, from the registry, when maxTokens is unset" in {
    val fable = "anthropic/claude-fable-5.2"
    summon[ModelRegistryService]
      .lookup(OpenRouterDialect.ProviderId, ModelName(fable))
      .value
      .maxOutputTokens shouldBe Some(128000)

    body(fable, CompletionOptions().withReasoning(ReasoningEffort.Max), hi)("thinking")("budget_tokens").num shouldBe
      65536

    val options = CompletionOptions().withThinking(Thinking.budget(200000).toOption.get)
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 200, openAICompletion("ok"))) { baseUrl =>
      val client = new OpenRouterClient(config(fable, baseUrl))
      client.createRequestBody(Conversation(Seq(hi)), options)("thinking")("budget_tokens").num shouldBe 127999
      client.complete(Conversation(Seq(hi)), options).value.adjustments shouldBe List(
        RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Budget(200000)",
          "Budget(127999)",
          "Anthropic takes a budget of at least 1024 and below the model's maximum output (128000)"
        )
      )
    }
  }

  it should "send a Thinking.Budget above 2048 as asked when maxTokens is unset, and fit it under a set maxTokens" in {
    val asked = Thinking.budget(10000).toOption.get
    withServer("/chat/completions")(exchange => sendJsonResponse(exchange, 200, openAICompletion("ok"))) { baseUrl =>
      val client = new OpenRouterClient(config("anthropic/claude-3.5-sonnet", baseUrl))
      val unset  = CompletionOptions().withThinking(asked)
      client.createRequestBody(Conversation(Seq(hi)), unset)("thinking")("budget_tokens").num shouldBe 10000
      client.complete(Conversation(Seq(hi)), unset).value.adjustments shouldBe empty

      val set = CompletionOptions(maxTokens = Some(4096)).withThinking(asked)
      client.createRequestBody(Conversation(Seq(hi)), set)("thinking")("budget_tokens").num shouldBe 4095
      client.complete(Conversation(Seq(hi)), set).value.adjustments shouldBe List(
        RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Budget(10000)",
          "Budget(4095)",
          "Anthropic takes a budget of at least 1024 and below max_tokens (4096)"
        )
      )
    }
  }

  it should "ask o-series models for a reasoning effort" in {
    body("openai/o3-mini", CompletionOptions().withReasoning(ReasoningEffort.Medium), hi)("reasoning_effort") shouldBe
      ujson.Str(ReasoningEffort.Medium.name)
  }

  it should "send no reasoning fields for other models, for thinking off, or for a budget alone on a non-Anthropic model" in {
    val keys = Seq(
      body("meta-llama/llama-3-70b", CompletionOptions().withReasoning(ReasoningEffort.High), hi),
      body("anthropic/claude-3.5-sonnet", CompletionOptions().withThinking(Thinking.Off), hi),
      body("openai/o3-mini", CompletionOptions().withThinking(Thinking.budget(4096).toOption.get), hi)
    ).flatMap(_.obj.keySet)
    keys should contain noneOf ("thinking", "reasoning_effort")
  }

  "OpenRouterClient replies" should "read thinking from the message, then the choice, and reasoning tokens" in {
    val client = new OpenRouterClient(config("anthropic/claude-3.5-sonnet"))
    def reply(message: String, choiceExtra: String = "") =
      ujson.read(
        s"""{"id":"x","created":1,"model":"m","choices":[{"message":$message$choiceExtra}],
           |"usage":{"prompt_tokens":1,"completion_tokens":9,"total_tokens":10,"reasoning_tokens":7}}""".stripMargin
      )

    client.parsedCompletion(reply("""{"content":"a","thinking":"t1"}""")).thinking shouldBe Some("t1")
    client.parsedCompletion(reply("""{"content":"a","reasoning":"t2"}""")).thinking shouldBe Some("t2")
    client.parsedCompletion(reply("""{"content":"a"}""", ""","thinking":"t3"""")).thinking shouldBe Some("t3")
    client.parsedCompletion(reply("""{"content":"a"}""")).usage.flatMap(_.thinkingTokens) shouldBe Some(7)
  }

  it should "take the reasoning tokens out of completion_tokens, read top-level or from completion_tokens_details" in {
    val client = new OpenRouterClient(config("openai/o3"))
    def usage(fields: String) =
      client
        .parsedCompletion(
          ujson.read(
            s"""{"id":"x","created":1,"model":"m","choices":[{"message":{"content":"a"}}],"usage":{$fields}}"""
          )
        )
        .usage
        .map(u => (u.completionTokens, u.thinkingTokens, u.totalTokens))

    usage(""""prompt_tokens":81,"completion_tokens":64,"total_tokens":145,"reasoning_tokens":46""") shouldBe
      Some((18, Some(46), 145))
    usage(
      """"prompt_tokens":81,"completion_tokens":64,"total_tokens":145,"completion_tokens_details":{"reasoning_tokens":46}"""
    ) shouldBe Some((18, Some(46), 145))
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
        .streamComplete(Conversation(Seq(hi)), CompletionOptions())(chunks += _)
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
    val completion = new OpenRouterClient(config("openai/gpt-4o")).parsedCompletion(
      ujson.read(
        """{"id":"x","created":1,"model":"m","choices":[{"message":{"content":null,
          |"tool_calls":[{"id":"c1","type":"function","function":{"name":"f","arguments":"{\"a\":1}"}}]}}]}""".stripMargin
      )
    )
    completion.toolCalls shouldBe Seq(ToolCall(ToolCallId("c1"), "f", ujson.Obj("a" -> 1)))
    completion.message.text shouldBe None
  }
}
