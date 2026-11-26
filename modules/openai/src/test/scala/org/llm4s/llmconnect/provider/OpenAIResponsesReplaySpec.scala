package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIApi, OpenAIConfig }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  FinishReason,
  Message,
  ThinkingBlock,
  ToolCall,
  ToolCallId,
  ToolMessage,
  UserMessage
}
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.collection.mutable.ListBuffer

/** A thread on the Responses API: the reasoning items of its earlier turns go back with each request. */
final class OpenAIResponsesReplaySpec extends AnyFlatSpec with Matchers with EitherValues:
  import OpenAIResponsesFixtures.{ functionCall, reasoningItem, reply, message => outputMessage }

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val Terra = "gpt-6.1-terra"
  private val Luna  = "gpt-5.6-luna"

  private lazy val call1 = ToolCall(ToolCallId("call_1"), "get_weather", ujson.Obj("city" -> "Paris"))
  private lazy val call2 = ToolCall(ToolCallId("call_2"), "get_time", ujson.Obj("zone" -> "CET"))

  private def clientFor(baseUrl: String, model: String, keepReasoning: Boolean = true): OpenAIClient =
    val config = OpenAIConfig
      .fromValues(ModelName(model), Secret("sk-test"), BaseUrl(s"$baseUrl/v1"))
      .value
      .withApi(OpenAIApi.Responses)
      .withKeepReasoning(keepReasoning)
    OpenAIClient(config).value

  private def kind(item: ujson.Value): String        = item.obj.get("type").map(_.str).getOrElse(item("role").str)
  private def types(input: ujson.Value): Seq[String] = input.arr.map(kind).toSeq

  /** What a two-request thread saw: the first completion, and the second request's `input`. */
  final private case class Thread(first: Completion, input: ujson.Value)

  /**
   * Serves `firstOutput` to the first request and a plain answer to every later one; the second request is sent by
   * `secondModel`'s client with the conversation `next` builds from the first completion.
   */
  private def thread(
    firstOutput: String,
    firstModel: String = Terra,
    secondModel: String = Terra,
    status: String = "completed",
    extra: String = "",
    secondKeepsReasoning: Boolean = true
  )(
    next: Completion => Seq[Message]
  ): Thread =
    val count  = new AtomicInteger(0)
    val bodies = ListBuffer.empty[ujson.Value]
    val out    = new AtomicReference[Thread]()
    LocalProviderTestServer.withServer("/") { exchange =>
      bodies.synchronized(
        bodies += ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      )
      val answer =
        if count.getAndIncrement() == 0 then reply(firstOutput, status, extra) else reply(outputMessage("done"))
      LocalProviderTestServer.sendJsonResponse(exchange, 200, answer)
    } { baseUrl =>
      val first = clientFor(baseUrl, firstModel)
      val completion =
        first.complete(Conversation(Seq(UserMessage("go"))), CompletionOptions(maxTokens = Some(64))).value
      first.close()
      val second = clientFor(baseUrl, secondModel, secondKeepsReasoning)
      val sent   = second.complete(Conversation(next(completion)), CompletionOptions(maxTokens = Some(64)))
      second.close()
      sent.value.content shouldBe "done"
      out.set(Thread(completion, bodies.synchronized(bodies.last)("input")))
    }
    out.get()

  private def toolTurn(completion: Completion): Seq[Message] =
    Seq(
      UserMessage("go"),
      completion.message,
      ToolMessage("18C", ToolCallId("call_1")),
      ToolMessage("12:00", ToolCallId("call_2"))
    )

  private val interleaved =
    Seq(
      reasoningItem("rs_1"),
      functionCall("call_1", "get_weather", """{"city":"Paris"}"""),
      reasoningItem("rs_2"),
      functionCall("call_2", "get_time", """{"zone":"CET"}""")
    ).mkString(",")

  private def bound(model: String, message: AssistantMessage): AssistantMessage =
    ThinkingReplay.bindOrigin(ReplayOrigin("openai", model), message)

  private def opaque(id: String, before: Option[String]): ThinkingBlock =
    val item = ujson.Obj("type" -> "reasoning", "id" -> id, "summary" -> ujson.Arr(), "encrypted_content" -> s"enc-$id")
    val fields = Seq("item" -> (item: ujson.Value)) ++ before.map(b => "before" -> (ujson.Str(b): ujson.Value))
    ThinkingBlock.Opaque(ProviderId("openai"), ujson.Obj.from(fields).render())

  "A thread on the Responses API" should "send a turn's reasoning items back, each before the call it came with" in {
    val seen  = thread(interleaved)(toolTurn)
    val input = seen.input
    types(input) shouldBe
      Seq(
        "user",
        "reasoning",
        "function_call",
        "reasoning",
        "function_call",
        "function_call_output",
        "function_call_output"
      )
    input(1)("id").str shouldBe "rs_1"
    input(1)("encrypted_content").str shouldBe "enc-rs_1"
    input(2)("call_id").str shouldBe "call_1"
    input(3)("id").str shouldBe "rs_2"
    input(4)("call_id").str shouldBe "call_2"
    input(2).obj.keySet should not contain "id"
    info("input: " + input.arr.map(i => kind(i) + i.obj.get("id").fold("")(id => ":" + id.str)).mkString(", "))
  }

  it should "keep two reasoning items that came together before the call, after the text" in {
    val seen = thread(
      Seq(
        outputMessage("Checking."),
        reasoningItem("rs_1"),
        reasoningItem("rs_2"),
        functionCall("call_1", "get_weather", """{"city":"Paris"}""")
      ).mkString(",")
    )(c => Seq(UserMessage("go"), c.message, ToolMessage("18C", ToolCallId("call_1"))))
    types(seen.input) shouldBe
      Seq("user", "assistant", "reasoning", "reasoning", "function_call", "function_call_output")
    seen.input(2)("id").str shouldBe "rs_1"
    seen.input(3)("id").str shouldBe "rs_2"
  }

  it should "send a reasoning item before the text and the one anchored to the call before the call" in {
    val seen = thread(
      Seq(
        reasoningItem("rs_1"),
        outputMessage("Checking."),
        reasoningItem("rs_2"),
        functionCall("call_1", "get_weather", """{"city":"Paris"}""")
      ).mkString(",")
    )(c => Seq(UserMessage("go"), c.message, ToolMessage("18C", ToolCallId("call_1"))))
    types(seen.input) shouldBe
      Seq("user", "reasoning", "assistant", "reasoning", "function_call", "function_call_output")
    seen.input(1)("id").str shouldBe "rs_1"
    seen.input(3)("id").str shouldBe "rs_2"
  }

  it should "not replay reasoning bound to the prefix of a conversation rather than to the message's origin" in {
    val origin  = ReplayOrigin("openai", Terra)
    val request = Seq[Message](UserMessage("go"))
    val message = ThinkingReplay.bind(
      origin,
      AssistantMessage(Some("hi"), Nil, Seq(opaque("rs_1", None))),
      request,
      CompletionOptions()
    )
    val seen = thread(outputMessage("hi"))(_ => Seq(UserMessage("go"), message, UserMessage("again")))
    types(seen.input) should not contain "reasoning"
  }

  it should "send a reasoning item that came before the answer's text ahead of that text" in {
    val seen = thread(reasoningItem("rs_1") + "," + outputMessage("It is 18C."))(c =>
      Seq(UserMessage("go"), c.message, UserMessage("thanks"))
    )
    types(seen.input) shouldBe Seq("user", "reasoning", "assistant", "user")
    seen.input(1)("id").str shouldBe "rs_1"
  }

  it should "keep a reasoning item cut off at max_output_tokens out of the stored message and the next request" in {
    val seen = thread(
      reasoningItem("rs_1"),
      status = "incomplete",
      extra = """"incomplete_details":{"reason":"max_output_tokens"}"""
    )(c => Seq(UserMessage("go"), c.message, UserMessage("again")))
    seen.first.finishReason shouldBe FinishReason.Length
    seen.first.message.hasSealedThinking shouldBe false
    types(seen.input) should not contain "reasoning"
  }

  it should "not send a reasoning item that came after the turn's text" in {
    val seen = thread(outputMessage("Done.") + "," + reasoningItem("rs_1"))(c =>
      Seq(UserMessage("go"), c.message, UserMessage("again"))
    )
    types(seen.input) shouldBe Seq("user", "assistant", "user")
  }

  // This is the stored format of 1.1.0. If this test fails after a change, stored conversations will lose their
  // reasoning: do not regenerate the literal.
  private lazy val StoredFor110 =
    """{"type":"assistant","toolCalls":[{"id":"call_1","name":"get_weather","arguments":{"city":"Paris"}}],"thinking":[{"type":"opaque","provider":"openai","data":"{\"item\":{\"id\":\"rs_1\",\"summary\":[],\"type\":\"reasoning\",\"encrypted_content\":\"enc-rs_1\"},\"before\":\"call_1\"}"}],"thinkingBinding":"424214ff564c90a4342787649e81c100d21f86796dee44f713ac23b368d7c410"}"""

  it should "read a message stored by 1.1.0 and send its reasoning back before its call" in {
    val seen = thread(outputMessage("unused")) { _ =>
      val stored = upickle.default.read[Message](StoredFor110)
      Seq(UserMessage("go"), stored, ToolMessage("18C", ToolCallId("call_1")))
    }
    types(seen.input) shouldBe Seq("user", "reasoning", "function_call", "function_call_output")
    seen.input(1)("id").str shouldBe "rs_1"
    seen.input(1)("encrypted_content").str shouldBe "enc-rs_1"
    seen.input(2)("call_id").str shouldBe "call_1"
  }

  it should "send the same request PR 1 sent for a thread with no reasoning" in {
    val seen = thread(outputMessage("hi"))(_ =>
      Seq(
        UserMessage("go"),
        AssistantMessage(Some("checking"), Seq(call1)),
        ToolMessage("18C", ToolCallId("call_1"))
      )
    )
    types(seen.input) shouldBe Seq("user", "assistant", "function_call", "function_call_output")
  }

  it should "not send reasoning for a turn whose content or tool calls were changed" in {
    val edited  = thread(interleaved)(c => toolTurn(c).updated(1, c.message.withContent("edited")))
    val trimmed = thread(interleaved)(c => toolTurn(c).updated(1, c.message.withToolCalls(Seq.empty)))
    types(edited.input) should not contain "reasoning"
    types(trimmed.input) should not contain "reasoning"
  }

  it should "not send reasoning to another model" in {
    val seen = thread(interleaved, firstModel = Terra, secondModel = Luna)(toolTurn)
    types(seen.input) should not contain "reasoning"
    types(seen.input) should contain("function_call")
  }

  it should "not send a reasoning item whose call is gone, and still send the request" in {
    val message = bound(
      Terra,
      AssistantMessage(None, Seq(call2), Seq(opaque("rs_1", Some("call_1")), opaque("rs_2", Some("call_2"))))
    )
    val seen =
      thread(outputMessage("hi"))(_ => Seq(UserMessage("go"), message, ToolMessage("12:00", ToolCallId("call_2"))))
    types(seen.input).count(_ == "reasoning") shouldBe 1
    val firstReasoning = types(seen.input).indexOf("reasoning")
    seen.input(firstReasoning)("id").str shouldBe "rs_2"
    types(seen.input) shouldBe Seq("user", "reasoning", "function_call", "function_call_output")
  }

  it should "never send another provider's sealed thinking as a reasoning item" in {
    val message = bound(
      Terra,
      AssistantMessage(
        Some("hi"),
        Nil,
        Seq(
          ThinkingBlock.Opaque(ProviderId("gemini"), """{"item":{"type":"reasoning","id":"rs_x"}}"""),
          ThinkingBlock.Text("t", Some("sig"))
        )
      )
    )
    val seen = thread(outputMessage("hi"))(_ => Seq(UserMessage("go"), message, UserMessage("again")))
    types(seen.input) should not contain "reasoning"
  }

  it should "skip an openai opaque block it cannot read" in {
    val message = bound(
      Terra,
      AssistantMessage(Some("hi"), Nil, Seq(ThinkingBlock.Opaque(ProviderId("openai"), "not json")))
    )
    val seen = thread(outputMessage("hi"))(_ => Seq(UserMessage("go"), message, UserMessage("again")))
    types(seen.input) shouldBe Seq("user", "assistant", "user")
  }

  it should "send back reasoning from a stored message" in {
    val live = thread(interleaved)(toolTurn)
    val stored = thread(interleaved) { c =>
      val back = upickle.default.read[Message](upickle.default.write[Message](c.message))
      toolTurn(c).updated(1, back)
    }
    types(stored.input) shouldBe types(live.input)
    stored.input.arr.filter(kind(_) == "reasoning").toSeq shouldBe live.input.arr.filter(kind(_) == "reasoning").toSeq
  }

  it should "keep a stored summary out of the request" in {
    val seen = thread(reasoningItem("rs_1", summary = Seq("Thought.")) + "," + outputMessage("hi"))(c =>
      Seq(UserMessage("go"), c.message, UserMessage("again"))
    )
    types(seen.input).count(_ == "reasoning") shouldBe 1
    val item = seen.input.arr.find(kind(_) == "reasoning").get
    item("summary")(0)("text").str shouldBe "Thought."
    seen.input.arr
      .filterNot(kind(_) == "reasoning")
      .flatMap(_.obj.get("content"))
      .map(_.render()) should not contain "\"Thought.\""
  }

  it should "send an item with a status this SDK does not know, exactly as returned" in {
    val future =
      """{"type":"reasoning","id":"rs_f","summary":[],"encrypted_content":"enc-f","status":"some_future_status"}"""
    val seen = thread(future + "," + outputMessage("hi"))(c => Seq(UserMessage("go"), c.message, UserMessage("again")))
    types(seen.input) shouldBe Seq("user", "reasoning", "assistant", "user")
    seen.input(1)("id").str shouldBe "rs_f"
    seen.input(1)("status").str shouldBe "some_future_status"
  }

  it should "send an encrypted_content with awkward characters back exactly" in {
    val awkward = ("A/b+c=" * 200) + "éü中\"\\\n\t\u0001"
    val seen = thread(reasoningItem("rs_1", encrypted = Some(awkward)) + "," + outputMessage("hi"))(c =>
      Seq(UserMessage("go"), c.message, UserMessage("again"))
    )
    seen.input(1)("encrypted_content").str shouldBe awkward
  }

  it should "not send stored reasoning back to a client with keepReasoning off, and still succeed" in {
    val kept = thread(interleaved)(toolTurn)
    types(kept.input) should contain("reasoning")
    val off = thread(interleaved, secondKeepsReasoning = false)(toolTurn)
    types(off.input) should not contain "reasoning"
    types(off.input) shouldBe Seq(
      "user",
      "function_call",
      "function_call",
      "function_call_output",
      "function_call_output"
    )
  }

  "A reply with keepReasoning off" should "not keep its reasoning items, but keep the summary text" in {
    val count = new AtomicInteger(0)
    var out   = Option.empty[Completion]
    LocalProviderTestServer.withServer("/") { exchange =>
      count.incrementAndGet()
      exchange.getRequestBody.readAllBytes()
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        200,
        reply(
          reasoningItem("rs_1", summary = Seq("Thought.")) + "," + functionCall(
            "call_1",
            "get_weather",
            """{"city":"Paris"}"""
          )
        )
      )
    } { baseUrl =>
      val client = clientFor(baseUrl, Terra, keepReasoning = false)
      out = client.complete(Conversation(Seq(UserMessage("go"))), CompletionOptions(maxTokens = Some(64))).toOption
      client.close()
    }
    val completion = out.get
    completion.message.hasSealedThinking shouldBe false
    completion.message.thinkingBinding shouldBe None
    completion.thinking shouldBe Some("Thought.")
    completion.toolCalls.map(_.name) shouldBe Seq("get_weather")
  }

  // OpenAI's refusal of an old encrypted reasoning item, and what the client makes of it
  private lazy val rejection =
    """{"error":{"message":"The encrypted content gAAA could not be verified.","type":"invalid_request_error","param":null,"code":"invalid_encrypted_content"}}"""

  private def carrying: Conversation =
    Conversation(
      Seq(
        UserMessage("go"),
        bound(Terra, AssistantMessage(Some("hi"), Nil, Seq(opaque("rs_1", None)))),
        UserMessage("again")
      )
    )

  private def plain: Conversation = Conversation(Seq(UserMessage("go")))

  private def refused(conversation: Conversation, body: String, stream: Boolean = false) =
    var out = Option.empty[Either[org.llm4s.error.LLMError, Completion]]
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes()
      LocalProviderTestServer.sendJsonResponse(exchange, 400, body)
    } { baseUrl =>
      val client  = clientFor(baseUrl, Terra)
      val options = CompletionOptions(maxTokens = Some(64))
      out = Some(
        if stream then client.streamComplete(conversation, options)(_ => ()) else client.complete(conversation, options)
      )
      client.close()
    }
    out.get.left.value

  "A rejected reasoning item" should "say what to do, keeping OpenAI's own message and the error type of a 400" in {
    val before = refused(plain, rejection) // the same body, to a request with no reasoning item: today's error
    val error  = refused(carrying, rejection)
    error shouldBe a[org.llm4s.error.ValidationError]
    error.getClass shouldBe before.getClass
    error.httpStatus shouldBe Some(400)
    error.message should include("could not be verified")
    error.message should include("keepReasoning")
    error.message should include("withKeepReasoning(false)")
    error.message should include("API key or organisation")
    info(error.message)
  }

  it should "say it on a streamed request too" in {
    val error = refused(carrying, rejection, stream = true)
    error shouldBe a[org.llm4s.error.ValidationError]
    error.message should include("could not be verified")
    error.message should include("keepReasoning")
  }

  it should "recognise the message when the code is another" in {
    val body =
      """{"error":{"message":"Invalid Encrypted Content in item rs_1","type":"invalid_request_error","code":"invalid_value"}}"""
    refused(carrying, body).message should include("keepReasoning")
  }

  it should "leave a 400 with another code exactly as it is" in {
    val body =
      """{"error":{"message":"Unsupported parameter: foo","type":"invalid_request_error","code":"unsupported_parameter"}}"""
    val error = refused(carrying, body)
    (error.message should not).include("keepReasoning")
    error shouldBe refused(plain, body)
  }

  it should "leave the code on a request that carried no reasoning item exactly as it is" in {
    val error = refused(plain, rejection)
    (error.message should not).include("keepReasoning")
    error.message should include("could not be verified")
  }
