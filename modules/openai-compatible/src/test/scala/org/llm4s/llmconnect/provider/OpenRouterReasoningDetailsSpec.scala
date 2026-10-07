package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/**
 * OpenRouter's `reasoning_details` (#1384): read whole or streamed into opaque blocks that keep
 * every field, sent back unchanged on the turn they came with while the conversation before it is
 * unchanged, and dropped - leaving the `reasoning` text - once it is not.
 */
class OpenRouterReasoningDetailsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def client(baseUrl: String, dialect: OpenAICompatibleDialect = OpenRouterDialect) =
    new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(OpenAICompatibleConfig(model = "anthropic/claude-sonnet", baseUrl = baseUrl)),
      dialect
    )

  // the origin `client` binds to and replays for: its provider id and configured model
  private val origin = ReplayOrigin(OpenAICompatibleConfig.ProviderIdName, "anthropic/claude-sonnet")

  private val details = ujson.read(
    """[
      |{"type":"reasoning.summary","summary":"Weighed the request","id":"rs-1","format":"anthropic-claude-v1","index":0},
      |{"type":"reasoning.text","text":"The user wants Paris weather.","signature":"sig-abc","id":null,"format":"anthropic-claude-v1","index":1},
      |{"type":"reasoning.encrypted","data":"ZW5jcnlwdGVk","id":"enc-1","format":"anthropic-claude-v1","index":2}
      |]""".stripMargin
  )

  private val toolCallReply = ujson
    .Obj(
      "id"      -> "gen-1",
      "created" -> 0,
      "model"   -> "anthropic/claude-sonnet",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "index" -> 0,
          "message" -> ujson.Obj(
            "role"              -> "assistant",
            "content"           -> ujson.Null,
            "reasoning"         -> "The user wants Paris weather.",
            "reasoning_details" -> details,
            "tool_calls" -> ujson.Arr(
              ujson.Obj(
                "id"       -> "call-1",
                "type"     -> "function",
                "function" -> ujson.Obj("name" -> "get_weather", "arguments" -> """{"city":"Paris"}""")
              )
            )
          ),
          "finish_reason" -> "tool_calls"
        )
      )
    )
    .render()

  private val finalReply = openAICompletion("Sunny.", "anthropic/claude-sonnet")

  private val history = Seq(UserMessage("Hello"), AssistantMessage("Hi."), UserMessage("Weather in Paris?"))
  private val answer  = ToolMessage("sunny", "call-1")

  private val withDetails = AssistantMessage(None, Seq(ToolCall("call-1", "get_weather", ujson.Obj("city" -> "Paris"))))
    .withThinking(
      ThinkingBlock.Text("Thinking.") +: details.arr.toSeq.map(item =>
        ThinkingBlock.Opaque("openrouter", item.render())
      )
    )

  /** Serves `replies` in order, recording each request body. */
  private def withReplies(replies: String*)(test: (String, () => Seq[ujson.Value]) => Any): Unit = {
    val seen      = ListBuffer.empty[ujson.Value]
    val remaining = scala.collection.mutable.Queue(replies*)
    withServer("/chat/completions") { exchange =>
      seen.synchronized(seen += ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      val reply = remaining.synchronized(remaining.dequeue())
      if (reply.startsWith("data:")) sendSseResponse(exchange, reply) else sendJsonResponse(exchange, 200, reply)
    }(baseUrl => test(baseUrl, () => seen.synchronized(seen.toList)))
  }

  private def assistantTurn(request: ujson.Value): ujson.Value =
    request("messages").arr.find(m => m("role").str == "assistant" && m.obj.contains("tool_calls")).get

  "a response" should "keep every reasoning_details item, with all its fields, as an opaque block after the text" in
    withReplies(toolCallReply) { (baseUrl, _) =>
      val message = client(baseUrl).complete(Conversation(history), CompletionOptions()).value.message
      message.thinking shouldBe ThinkingBlock.Text("The user wants Paris weather.") +:
        details.arr.toSeq.map(item => ThinkingBlock.Opaque("openrouter", item.render()))
      message.thinkingText shouldBe Some("The user wants Paris weather.")
      message.hasSealedThinking shouldBe true
      message.thinkingBinding shouldBe defined
    }

  "the follow-up request" should "send reasoning_details back unchanged - same items, order and fields - with the reasoning text" in
    withReplies(toolCallReply, finalReply) { (baseUrl, seen) =>
      val c     = client(baseUrl)
      val first = c.complete(Conversation(history), CompletionOptions()).value
      c.complete(Conversation(history :+ first.message :+ answer), CompletionOptions()).isRight shouldBe true

      val turn = assistantTurn(seen()(1))
      turn("reasoning_details") shouldBe details
      turn("reasoning_details").render() shouldBe details.render()
      turn("reasoning").str shouldBe "The user wants Paris weather."
    }

  it should "drop reasoning_details, keeping the reasoning text, once the history before the turn was pruned" in
    withReplies(toolCallReply, finalReply, finalReply) { (baseUrl, seen) =>
      val c     = client(baseUrl)
      val first = c.complete(Conversation(history), CompletionOptions()).value
      c.complete(Conversation(history.drop(2) :+ first.message :+ answer), CompletionOptions()).isRight shouldBe true

      val turn = assistantTurn(seen()(1))
      turn.obj.keySet should not contain "reasoning_details"
      turn("reasoning").str shouldBe "The user wants Paris weather."
    }

  it should "drop them once the turn itself was edited" in
    withReplies(toolCallReply, finalReply) { (baseUrl, seen) =>
      val c      = client(baseUrl)
      val first  = c.complete(Conversation(history), CompletionOptions()).value
      val edited = first.message.withContent("Let me check.")
      c.complete(Conversation(history :+ edited :+ answer), CompletionOptions()).isRight shouldBe true
      assistantTurn(seen()(1)).obj.keySet should not contain "reasoning_details"
    }

  it should "drop them when the turn was served by another model than the one now called, the history unchanged" in {
    // a router (openrouter/auto, model fallbacks) answered with a model other than the configured one
    val routed = toolCallReply.replace("\"anthropic/claude-sonnet\"", "\"openai/o3\"")
    withReplies(routed, finalReply) { (baseUrl, seen) =>
      val c     = client(baseUrl)
      val first = c.complete(Conversation(history), CompletionOptions()).value
      first.model shouldBe "openai/o3"
      first.message.hasSealedThinking shouldBe true
      c.complete(Conversation(history :+ first.message :+ answer), CompletionOptions()).isRight shouldBe true

      val turn = assistantTurn(seen()(1))
      turn.obj.keySet should not contain "reasoning_details"
      turn("reasoning").str shouldBe "The user wants Paris weather."
    }
  }

  it should "drop them when the conversation moves to another model" in
    withReplies(toolCallReply) { (baseUrl, _) =>
      val first = client(baseUrl).complete(Conversation(history), CompletionOptions()).value
      val otherModel = new OpenAICompatibleClient(
        OpenAICompatibleClient.settings(OpenAICompatibleConfig(model = "google/gemini-2.5-pro", baseUrl = baseUrl)),
        OpenRouterDialect
      )
      val turn = assistantTurn(
        otherModel.createRequestBody(Conversation(history :+ first.message :+ answer), CompletionOptions())
      )
      turn.obj.keySet should not contain "reasoning_details"
      turn("reasoning").str shouldBe "The user wants Paris weather."
    }

  "a streamed response" should "join each item's fragments by index and send the joined items back" in {
    def event(delta: ujson.Value, finish: ujson.Value = ujson.Null): String =
      "data: " + ujson
        .Obj(
          "id"      -> "gen-2",
          "model"   -> "anthropic/claude-sonnet",
          "choices" -> ujson.Arr(ujson.Obj("index" -> 0, "delta" -> delta, "finish_reason" -> finish))
        )
        .render() + "\n\n"
    def fragment(fields: (String, ujson.Value)*): ujson.Value =
      ujson.Obj("reasoning_details" -> ujson.Arr(ujson.Obj.from(fields)))
    val format = "format" -> ujson.Str("anthropic-claude-v1")
    val sse = Seq(
      event(
        ujson.Obj(
          "reasoning" -> "Paris ",
          "reasoning_details" -> ujson.Arr(
            ujson.Obj("type" -> "reasoning.text", "text" -> "Paris ", "signature" -> ujson.Null, format, "index" -> 0)
          )
        )
      ),
      event(
        ujson.Obj(
          "reasoning" -> "weather.",
          "reasoning_details" -> ujson.Arr(
            ujson.Obj("type" -> "reasoning.text", "text" -> "weather.", "signature" -> ujson.Null, format, "index" -> 0)
          )
        )
      ),
      event(fragment("type" -> "reasoning.text", "signature" -> "sig-streamed", format, "index" -> 0)),
      event(fragment("type" -> "reasoning.encrypted", "data" -> "c2VhbGVk", "id" -> "enc-9", format, "index" -> 1)),
      event(
        ujson.Obj(
          "tool_calls" -> ujson.Arr(
            ujson.Obj(
              "index"    -> 0,
              "id"       -> "call-1",
              "type"     -> "function",
              "function" -> ujson.Obj("name" -> "get_weather", "arguments" -> """{"city":"Paris"}""")
            )
          )
        ),
        "tool_calls"
      ),
      "data: [DONE]\n\n"
    ).mkString

    withReplies(sse, finalReply) { (baseUrl, seen) =>
      val c        = client(baseUrl)
      val streamed = c.streamComplete(Conversation(history), CompletionOptions(), _ => ()).value
      val joinedText = ujson.Obj(
        "type"      -> "reasoning.text",
        "text"      -> "Paris weather.",
        "signature" -> "sig-streamed",
        format,
        "index" -> 0
      )
      val encrypted =
        ujson.Obj("type" -> "reasoning.encrypted", "data" -> "c2VhbGVk", "id" -> "enc-9", format, "index" -> 1)

      streamed.message.thinking shouldBe Seq(
        ThinkingBlock.Text("Paris weather."),
        ThinkingBlock.Opaque("openrouter", joinedText.render()),
        ThinkingBlock.Opaque("openrouter", encrypted.render())
      )
      streamed.message.thinkingBinding shouldBe defined

      c.complete(Conversation(history :+ streamed.message :+ answer), CompletionOptions()).isRight shouldBe true
      val turn = assistantTurn(seen()(1))
      turn("reasoning_details") shouldBe ujson.Arr(joinedText, encrypted)
      turn("reasoning").str shouldBe "Paris weather."
    }

    // the same stream served by a routed model: its items are not replayed to the configured one
    withReplies(sse.replace("\"anthropic/claude-sonnet\"", "\"openai/o3\"")) { (baseUrl, _) =>
      val c        = client(baseUrl)
      val streamed = c.streamComplete(Conversation(history), CompletionOptions(), _ => ()).value
      streamed.model shouldBe "openai/o3"
      val turn =
        assistantTurn(c.createRequestBody(Conversation(history :+ streamed.message :+ answer), CompletionOptions()))
      turn.obj.keySet should not contain "reasoning_details"
    }
  }

  "decodeThinkingDetails" should "start a new item for a fragment with no index or of a different type" in {
    val blocks = OpenRouterDialect.decodeThinkingDetails(
      Seq(
        ujson.Obj("type" -> "reasoning.text", "text"       -> "a", "index" -> 0),
        ujson.Obj("type" -> "reasoning.summary", "summary" -> "s", "index" -> 0),
        ujson.Obj("type" -> "reasoning.summary", "summary" -> "t", "index" -> 0),
        ujson.Obj("type" -> "reasoning.encrypted", "data"  -> "x")
      )
    )
    blocks shouldBe Seq(
      ThinkingBlock.Opaque("openrouter", """{"type":"reasoning.text","text":"a","index":0}"""),
      ThinkingBlock.Opaque("openrouter", """{"type":"reasoning.summary","summary":"st","index":0}"""),
      ThinkingBlock.Opaque("openrouter", """{"type":"reasoning.encrypted","data":"x"}""")
    )
  }

  /** The assistant turn as `dialect` encodes `message` after `history`, bound as its client would. */
  private def encoded(dialect: OpenAICompatibleDialect, message: AssistantMessage): ujson.Value = {
    val bound = ThinkingReplay.bind(origin, message, history, CompletionOptions())
    assistantTurn(
      client("http://localhost:1/v1", dialect)
        .createRequestBody(Conversation(history :+ bound :+ answer), CompletionOptions())
    )
  }

  "other dialects" should "never send OpenRouter's reasoning_details" in {
    encoded(DeepSeekDialect, withDetails).obj.keySet shouldBe Set("role", "tool_calls", "reasoning_content")
    encoded(DeepSeekDialect, withDetails)("reasoning_content").str shouldBe "Thinking."
    encoded(ZaiDialect, withDetails).obj.keySet should not contain "reasoning_details"
    encoded(OpenAICompatibleDialect.Standard, withDetails).obj.keySet shouldBe Set("role", "tool_calls")
    encoded(MistralDialect, withDetails)("content").arr.map(_("type").str) shouldBe Seq("thinking")
    (encoded(MistralDialect, withDetails).render() should not).include("sig-abc")
  }

  "OpenRouter" should "ignore opaque blocks another provider produced" in {
    val foreign = withDetails.withThinking(Seq(ThinkingBlock.Text("Thinking."), ThinkingBlock.Opaque("other", "{}")))
    val turn    = encoded(OpenRouterDialect, foreign)
    turn.obj.keySet should not contain "reasoning_details"
    turn("reasoning").str shouldBe "Thinking."
  }

  it should "drop them once a system message moved, since it sends system messages inline where they sit" in {
    val system = SystemMessage("Answer in French.")
    val asked  = Seq(system, UserMessage("Weather in Paris?"))
    val bound  = ThinkingReplay.bind(origin, withDetails, asked, CompletionOptions())
    def turnAfter(prefix: Seq[Message]): ujson.Value =
      assistantTurn(
        client("http://localhost:1/v1")
          .createRequestBody(Conversation(prefix :+ bound :+ answer), CompletionOptions())
      )
    turnAfter(asked)("reasoning_details") shouldBe details
    val moved = turnAfter(Seq(UserMessage("Weather in Paris?"), system))
    moved.obj.keySet should not contain "reasoning_details"
    moved("reasoning").str shouldBe "Thinking."
  }

  it should "not send reasoning_details that no client bound" in {
    val unbound = assistantTurn(
      client("http://localhost:1/v1")
        .createRequestBody(Conversation(history :+ withDetails :+ answer), CompletionOptions())
    )
    unbound.obj.keySet should not contain "reasoning_details"
    unbound("reasoning").str shouldBe "Thinking."
  }
}
