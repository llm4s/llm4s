package org.llm4s.llmconnect.provider

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.MessageCreateParams
import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * Extended thinking with tool use (#1381): Anthropic returns thinking blocks, each signed, and
 * requires them back unchanged, in order, in the assistant turn that made the tool calls. The
 * client reads them - streamed or not - onto the returned message, and sends them back with the
 * turn's `tool_use` blocks and the `tool_result` blocks that answer them.
 */
class AnthropicThinkingReplaySpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val testConfig = AnthropicConfig(
    apiKey = "test-key",
    model = "claude-sonnet-4-5",
    baseUrl = "https://api.anthropic.com",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  private val call = ToolCall("toolu_1", "get_weather", ujson.Obj("city" -> "Paris"))

  private val sealedThinking =
    Seq(ThinkingBlock.Text("Weather and time.", Some("sig-pair")), ThinkingBlock.Redacted("opaque-pair"))
  private val timeCall = ToolCall("toolu_2", "get_time", ujson.Obj("zone" -> "CET"))

  private def requestBody(messages: Message*): ujson.Value = {
    val builder = MessageCreateParams.builder().model(testConfig.model).maxTokens(4096)
    new AnthropicClient(testConfig).addMessagesToParams(Conversation(messages), builder, CompletionOptions())
    ujson.read(ObjectMappers.jsonMapper().writeValueAsString(builder.build()._body()))
  }

  /** A fake `/v1/messages`, answering each request with the next response and recording its body. */
  private def withServer(responses: (String, String)*)(test: (String, () => List[ujson.Value]) => Any): Unit = {
    val seen   = new ConcurrentLinkedQueue[ujson.Value]()
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/messages",
      (exchange: HttpExchange) => {
        seen.add(ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
        val (contentType, body) = responses(math.min(seen.size - 1, responses.size - 1))
        val bytes               = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try test(s"http://localhost:${server.getAddress.getPort}", () => seen.asScala.toList)
    finally server.stop(0)
  }

  private val thinkingToolUseReply =
    "application/json" ->
      """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
        |"content":[
        |  {"type":"thinking","thinking":"The user wants Paris weather.","signature":"sig-1"},
        |  {"type":"redacted_thinking","data":"opaque-1"},
        |  {"type":"text","text":"Let me check."},
        |  {"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Paris"}}
        |],
        |"stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":20}}""".stripMargin

  private val finalReply =
    "application/json" ->
      """{"id":"msg_2","type":"message","role":"assistant","model":"claude-sonnet-4-5",
        |"content":[{"type":"text","text":"Sunny."}],
        |"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":30,"output_tokens":2}}""".stripMargin

  private val streamedThinkingReply = {
    val events = Seq(
      "message_start" ->
        """{"type":"message_start","message":{"id":"msg_s","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-5","stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":8,"output_tokens":0}}}""",
      "content_block_start" ->
        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
      "content_block_delta" ->
        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"The user wants "}}""",
      "content_block_delta" ->
        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Paris weather."}}""",
      "content_block_delta" ->
        """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-streamed"}}""",
      "content_block_stop" -> """{"type":"content_block_stop","index":0}""",
      "content_block_start" ->
        """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque-s"}}""",
      "content_block_stop" -> """{"type":"content_block_stop","index":1}""",
      "content_block_start" ->
        """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}""",
      "content_block_delta" ->
        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Paris\"}"}}""",
      "content_block_stop" -> """{"type":"content_block_stop","index":2}""",
      "message_delta" ->
        """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":20}}""",
      "message_stop" -> """{"type":"message_stop"}"""
    )
    "text/event-stream" -> events.map((event, data) => s"event: $event\ndata: $data\n\n").mkString
  }

  "a response" should "put its thinking blocks on the message, signatures and redacted data intact" in {
    withServer(thinkingToolUseReply) { (baseUrl, _) =>
      val completion = new AnthropicClient(testConfig.copy(baseUrl = baseUrl))
        .complete(Conversation(Seq(UserMessage("Weather in Paris?"))), CompletionOptions())
        .toOption
        .get
      completion.message.thinking shouldBe Seq(
        ThinkingBlock.Text("The user wants Paris weather.", Some("sig-1")),
        ThinkingBlock.Redacted("opaque-1")
      )
      completion.thinking shouldBe Some("The user wants Paris weather.")
      completion.message.toolCalls.map(_.id) shouldBe Seq("toolu_1")
    }
  }

  "a streamed response" should "assemble each thinking block with its signature, and redacted thinking, on the message" in {
    withServer(streamedThinkingReply) { (baseUrl, _) =>
      val completion = new AnthropicClient(testConfig.copy(baseUrl = baseUrl))
        .streamComplete(Conversation(Seq(UserMessage("Weather in Paris?"))), CompletionOptions(), _ => ())
        .toOption
        .get
      completion.message.thinking shouldBe Seq(
        ThinkingBlock.Text("The user wants Paris weather.", Some("sig-streamed")),
        ThinkingBlock.Redacted("opaque-s")
      )
      completion.thinking shouldBe Some("The user wants Paris weather.")
      completion.message.toolCalls.map(_.arguments) shouldBe Seq(ujson.Obj("city" -> "Paris"))
    }
  }

  "the follow-up request" should "send the tool-call turn back with its thinking blocks first, then tool_result" in {
    withServer(thinkingToolUseReply, finalReply) { (baseUrl, seen) =>
      val client = new AnthropicClient(testConfig.copy(baseUrl = baseUrl))
      val ask    = UserMessage("Weather in Paris?")
      val first  = client.complete(Conversation(Seq(ask)), CompletionOptions()).toOption.get
      val answer = ToolMessage("sunny", "toolu_1")
      client.complete(Conversation(Seq(ask, first.message, answer)), CompletionOptions()).isRight shouldBe true

      val messages = seen()(1)("messages").arr
      messages.map(_("role").str) shouldBe Seq("user", "assistant", "user")
      val turn = messages(1)("content").arr
      turn.map(_("type").str) shouldBe Seq("thinking", "redacted_thinking", "text", "tool_use")
      turn(0)("thinking").str shouldBe "The user wants Paris weather."
      turn(0)("signature").str shouldBe "sig-1"
      turn(1)("data").str shouldBe "opaque-1"
      turn(3)("id").str shouldBe "toolu_1"
      messages(2)("content")(0)("type").str shouldBe "tool_result"
      messages(2)("content")(0)("tool_use_id").str shouldBe "toolu_1"
    }
  }

  "addMessagesToParams" should "leave out unsigned thinking, which Anthropic would reject" in {
    val fromElsewhere = AssistantMessage("Hello.").withThinking("Reasoning from another provider.")
    val body          = requestBody(UserMessage("hi"), fromElsewhere)
    (body.render() should not).include("Reasoning from another provider.")
    body("messages")(1)("content") match {
      case ujson.Str(text) => text shouldBe "Hello."
      case blocks          => blocks.arr.map(_("type").str) shouldBe Seq("text")
    }
  }

  it should "send a signed block whose text was withheld" in {
    val omitted = AssistantMessage(None, Seq(call)).withThinking(Seq(ThinkingBlock.Text("", Some("sig-omitted"))))
    val turn    = requestBody(UserMessage("hi"), omitted, ToolMessage("sunny", call.id))("messages")(1)("content")
    turn(0)("type").str shouldBe "thinking"
    turn(0)("signature").str shouldBe "sig-omitted"
  }

  it should "put the results of parallel tool calls in one user turn, in order" in {
    val second = ToolCall("toolu_2", "get_time", ujson.Obj("zone" -> "CET"))
    val body = requestBody(
      UserMessage("Weather and time in Paris?"),
      AssistantMessage(None, Seq(call, second)),
      ToolMessage("sunny", call.id),
      ToolMessage("noon", second.id),
      UserMessage("Thanks")
    )
    val messages = body("messages").arr
    messages.map(_("role").str) shouldBe Seq("user", "assistant", "user", "user")
    messages(2)("content").arr.map(_("tool_use_id").str) shouldBe Seq("toolu_1", "toolu_2")
  }

  // the conversation validator accepts a user message between a call and its result; Anthropic
  // does not accept a tool_result that is not straight after its tool_use, nor one after text
  it should "omit a tool_use whose result a user message separates from it, and send that result as text" in {
    val body = requestBody(
      UserMessage("Weather in Paris?"),
      AssistantMessage(Some("Checking."), Seq(call)),
      UserMessage("Still there?"),
      ToolMessage("sunny", call.id)
    )
    (body.render() should not).include("tool_use")
    (body.render() should not).include("tool_result")
    val messages = body("messages").arr
    messages.map(_("role").str) shouldBe Seq("user", "assistant", "user", "user")
    messages(3)("content")(0)("text").str shouldBe "[Tool result for toolu_1]: sunny"
  }

  it should "pair a result only with the turn straight before it" in {
    val second = ToolCall("toolu_2", "get_time", ujson.Obj("zone" -> "CET"))
    val body = requestBody(
      UserMessage("Weather, then time?"),
      AssistantMessage(None, Seq(call)),
      ToolMessage("sunny", call.id),
      AssistantMessage(None, Seq(second)),
      ToolMessage("late duplicate", call.id),
      ToolMessage("noon", second.id)
    )
    val messages = body("messages").arr
    messages.map(_("role").str) shouldBe Seq("user", "assistant", "user", "assistant", "user")
    messages(3)("content").arr.map(_("id").str) shouldBe Seq("toolu_2")
    // the paired result first, as Anthropic requires, then the unpaired one as text
    val lastTurn = messages(4)("content").arr
    lastTurn.map(_("type").str) shouldBe Seq("tool_result", "text")
    lastTurn(0)("tool_use_id").str shouldBe "toolu_2"
    lastTurn(1)("text").str shouldBe "[Tool result for toolu_1]: late duplicate"
  }

  it should "not send a turn of thinking alone when its every tool call went unanswered" in {
    val unanswered =
      AssistantMessage(None, Seq(call)).withThinking(Seq(ThinkingBlock.Text("t", Some("sig-unanswered"))))
    val body = requestBody(UserMessage("hi"), unanswered, UserMessage("again"))
    body("messages").arr.map(_("role").str) shouldBe Seq("user", "user")
    (body.render() should not).include("sig-unanswered")
  }

  it should "unseal a signed turn whose calls are not all paired, sending only the paired call" in {
    val turnMsg = AssistantMessage(Some("Checking."), Seq(call, timeCall)).withThinking(sealedThinking)
    val body    = requestBody(UserMessage("Weather and time?"), turnMsg, ToolMessage("sunny", call.id))
    val turn    = body("messages")(1)("content").arr
    turn.map(_("type").str) shouldBe Seq("text", "tool_use")
    turn(1)("id").str shouldBe "toolu_1"
    (body.render() should not).include("sig-pair")
    (body.render() should not).include("opaque-pair")
  }

  it should "keep a signed turn's thinking when every call is paired" in {
    val turnMsg = AssistantMessage(None, Seq(call, timeCall)).withThinking(sealedThinking)
    val body = requestBody(
      UserMessage("Weather and time?"),
      turnMsg,
      ToolMessage("sunny", call.id),
      ToolMessage("noon", timeCall.id)
    )
    val turn = body("messages")(1)("content").arr
    turn.map(_("type").str) shouldBe Seq("thinking", "redacted_thinking", "tool_use", "tool_use")
    turn(0)("signature").str shouldBe "sig-pair"
    turn(1)("data").str shouldBe "opaque-pair"
  }
}
