package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * An assistant turn's thinking in the shared client (#1381): read onto the returned message, and
 * sent back only where the dialect says the provider takes it, in the field it documents.
 */
class OpenAICompatibleThinkingReplaySpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def client(dialect: OpenAICompatibleDialect) =
    new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(OpenAICompatibleConfig(model = "m", baseUrl = "http://localhost:1/v1")),
      dialect
    )

  private val call = ToolCall("abcdefghi", "get_weather", ujson.Obj("city" -> "Paris"))

  private val history = Conversation(
    Seq(
      UserMessage("Weather in Paris?"),
      AssistantMessage(None, Seq(call)).withThinking("The user wants Paris weather."),
      ToolMessage("sunny", call.id)
    )
  )

  /** A turn that hit its token limit while still reasoning: thinking, no text, no tool calls. */
  private val thinkingOnly = Conversation(
    Seq(UserMessage("Prove it."), AssistantMessage(None, Seq.empty).withThinking("First, consider"))
  )

  /** The encoded assistant turn of [[history]]. */
  private def assistantTurn(dialect: OpenAICompatibleDialect): ujson.Value =
    client(dialect).createRequestBody(history, CompletionOptions())("messages")(1)

  "the standard dialect" should "drop an assistant turn's thinking: the OpenAI format has no field for it" in {
    val turn = assistantTurn(OpenAICompatibleDialect.Standard)
    turn.obj.keySet shouldBe Set("role", "tool_calls")
  }

  "Cohere" should "drop it too" in {
    assistantTurn(CohereDialect).obj.keySet should contain noneOf ("reasoning_content", "reasoning", "thinking")
  }

  "DeepSeek" should "send it back as `reasoning_content`, which thinking mode requires across tool calls" in {
    assistantTurn(DeepSeekDialect)("reasoning_content").str shouldBe "The user wants Paris weather."
  }

  "Z.ai" should "send it back as `reasoning_content`" in {
    assistantTurn(ZaiDialect)("reasoning_content").str shouldBe "The user wants Paris weather."
  }

  it should "read `reasoning_content` onto the returned message" in {
    val completion = client(ZaiDialect).parseCompletion(
      ujson.read(
        """{"id":"1","created":0,"model":"glm","choices":[{"index":0,"message":{"role":"assistant","content":"Sunny.","reasoning_content":"Check."}}]}"""
      )
    )
    completion.message.thinking shouldBe Seq(ThinkingBlock.Text("Check."))
    completion.thinking shouldBe Some("Check.")
  }

  "OpenRouter" should "send it back as `reasoning`" in {
    assistantTurn(OpenRouterDialect)("reasoning").str shouldBe "The user wants Paris weather."
  }

  "Mistral" should "send it back as a thinking chunk in `content`, before the text" in {
    val withText = Conversation(Seq(UserMessage("hi"), AssistantMessage("Hello.").withThinking("Greet them.")))
    val turn     = client(MistralDialect).createRequestBody(withText, CompletionOptions())("messages")(1)
    turn("content") shouldBe ujson.Arr(
      ujson.Obj("type" -> "thinking", "thinking" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "Greet them."))),
      ujson.Obj("type" -> "text", "text"         -> "Hello.")
    )
    // a tool-call turn has no text chunk
    assistantTurn(MistralDialect)("content").arr.map(_("type").str) shouldBe Seq("thinking")
  }

  it should "read what it sends: the replayed turn decodes to the same content and thinking" in {
    val turn = client(MistralDialect).createRequestBody(
      Conversation(Seq(UserMessage("hi"), AssistantMessage("Hello.").withThinking("Greet them."))),
      CompletionOptions()
    )("messages")(1)
    MistralDialect.decodeContent(turn("content")) shouldBe Some("Hello.")
    MistralDialect.thinking(turn) shouldBe Some("Greet them.")
  }

  it should "keep a thinking-only turn, which it would leave out if it were empty, as a lone thinking chunk" in {
    val messages = client(MistralDialect).createRequestBody(thinkingOnly, CompletionOptions())("messages").arr
    messages.map(_("role").str) shouldBe Seq("user", "assistant")
    messages(1)("content") shouldBe ujson.Arr(
      ujson.Obj("type" -> "thinking", "thinking" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "First, consider")))
    )
    messages(1).obj.keySet shouldBe Set("role", "content")
  }

  "a dialect that drops thinking and sends no empty turns" should "still leave a thinking-only turn out" in {
    val noEmptyTurns = new OpenAICompatibleDialect {
      override val sendEmptyAssistantTurns: Boolean = false
    }
    val messages = client(noEmptyTurns).createRequestBody(thinkingOnly, CompletionOptions())("messages").arr
    messages.map(_("role").str) shouldBe Seq("user")
  }

  "every dialect" should "put the thinking it reads on the returned message, not only on the completion" in {
    val completion = client(DeepSeekDialect).parseCompletion(
      ujson.read(
        """{"id":"1","created":0,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"4","reasoning_content":"2+2"}}]}"""
      )
    )
    completion.message.thinkingText shouldBe Some("2+2")
  }

  it should "send nothing for a turn without thinking" in {
    val plain = Conversation(Seq(UserMessage("hi"), AssistantMessage("Hello.")))
    Seq(DeepSeekDialect, ZaiDialect, OpenRouterDialect).foreach { dialect =>
      val turn = client(dialect).createRequestBody(plain, CompletionOptions())("messages")(1)
      turn.obj.keySet should contain noneOf ("reasoning_content", "reasoning")
    }
    client(MistralDialect).createRequestBody(plain, CompletionOptions())("messages")(1)("content").str shouldBe "Hello."
  }
}
