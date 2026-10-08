package org.llm4s.llmconnect.provider

import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.{ LLMClient, ProviderExchangeLogging }
import org.llm4s.llmconnect.config.{ GeminiConfig, VertexAIConfig }
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

/**
 * Thought signatures through the Gemini API and Vertex AI clients (see [[GeminiThoughtSignatures]]): what a
 * response's signatures become on the [[AssistantMessage]], what the next request sends back and where, and
 * when a signature is not sent. Every behaviour is checked against both clients, which are separate signing
 * authorities, over a transport that records the exact JSON each request carries.
 *
 * The response shapes follow Google's published rules ("Thought signatures", Vertex AI documentation): the
 * signature sits on the `functionCall` part, on the first call only when calls are parallel, and on the
 * last (possibly empty) text part otherwise. No live call is made.
 */
class GeminiThoughtSignatureSpec extends AnyFlatSpec with Matchers with MockFactory {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  // ---- a transport that records requests and answers from a script

  final private class Wire {
    var body: String             = ""
    var sse: String              = ""
    val requests                 = ListBuffer.empty[ujson.Value]
    def lastRequest: ujson.Value = requests.last
  }

  private val tokenBody = """{"access_token":"ya29.test-token","expires_in":3600}"""

  private def geminiClient(model: String = "gemini-2.0-flash"): (LLMClient, Wire) = {
    val wire = new Wire
    val http = stub[Llm4sHttpClient]
    (http.post _).when(*, *, *, *).onCall { (_: String, _: Map[String, String], body: String, _: FiniteDuration) =>
      wire.requests += ujson.read(body)
      Right(HttpResponse(200, wire.body, Map.empty))
    }
    (http.postStream _).when(*, *, *, *).onCall {
      (_: String, _: Map[String, String], body: String, _: FiniteDuration) =>
        wire.requests += ujson.read(body)
        Right(StreamingHttpResponse(200, new ByteArrayInputStream(wire.sse.getBytes(StandardCharsets.UTF_8))))
    }
    val config = GeminiConfig(
      apiKey = "test-key",
      model = model,
      baseUrl = "https://generativelanguage.googleapis.com/v1beta",
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    (new GeminiClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, http), wire)
  }

  private def vertexClient(model: String = "gemini-2.0-flash"): (LLMClient, Wire) = {
    val wire = new Wire
    val http = stub[Llm4sHttpClient]
    (http.post _).when(*, *, *, *).onCall { (url: String, _: Map[String, String], body: String, _: FiniteDuration) =>
      if (url.contains("oauth2.googleapis.com")) Right(HttpResponse(200, tokenBody, Map.empty))
      else {
        wire.requests += ujson.read(body)
        Right(HttpResponse(200, wire.body, Map.empty))
      }
    }
    (http.postStream _).when(*, *, *, *).onCall {
      (_: String, _: Map[String, String], body: String, _: FiniteDuration) =>
        wire.requests += ujson.read(body)
        Right(StreamingHttpResponse(200, new ByteArrayInputStream(wire.sse.getBytes(StandardCharsets.UTF_8))))
    }
    (http.get _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    val config = VertexAIConfig(
      projectId = "my-gcp-project",
      location = "us-central1",
      model = model,
      credentialFilePath = None,
      contextWindow = 1048576,
      reserveCompletion = 8192
    )
    (new VertexAIClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, http), wire)
  }

  // ---- response and request shapes

  private def part(fields: (String, ujson.Value)*): String = ujson.Obj.from(fields).render()
  private def text(t: String, signature: Option[String] = None, thought: Boolean = false): String =
    part(
      Seq[(String, ujson.Value)]("text" -> t) ++
        Option.when(thought)("thought" -> ujson.Bool(true)) ++
        signature.map("thoughtSignature" -> ujson.Str(_))*
    )
  private def call(name: String, args: ujson.Value, signature: Option[String] = None): String =
    part(
      Seq[(String, ujson.Value)]("functionCall" -> ujson.Obj("name" -> name, "args" -> args)) ++
        signature.map("thoughtSignature" -> ujson.Str(_))*
    )
  private def candidate(parts: String*): String =
    s"""{"content":{"role":"model","parts":[${parts.mkString(",")}]},"finishReason":"STOP"}"""
  private def response(parts: String*): String =
    s"""{"candidates":[${candidate(
        parts*
      )}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"totalTokenCount":5}}"""
  private def sse(chunks: String*): String =
    chunks.map(c => s"""data: {"candidates":[{"content":{"role":"model","parts":[$c]}}]}""").mkString("", "\n", "\n")

  private val city = ujson.Obj("city" -> "Paris")

  private def modelPartsOf(request: ujson.Value): Seq[ujson.Value] =
    request("contents").arr.toSeq.filter(_("role").str == "model").flatMap(_("parts").arr.toSeq)

  private def sentSignatures(request: ujson.Value): Seq[String] =
    request("contents").arr.toSeq
      .flatMap(_("parts").arr.toSeq)
      .flatMap(_.obj.get("thoughtSignature").map(_.str))

  /** The continuation request: the user's turn, the assistant turn Gemini returned, its tool results, a question. */
  private def continuation(first: AssistantMessage, extra: Message*): Conversation =
    Conversation(
      Seq[Message](UserMessage("Weather in Paris and Rome?"), first) ++ first.toolCalls.map(tc =>
        ToolMessage("sunny", tc.id)
      ) ++ extra
    )

  private val firstTurn = Conversation(Seq(UserMessage("Weather in Paris and Rome?")))

  private val clients: Seq[(String, String, String => (LLMClient, Wire))] = Seq(
    ("Gemini API", "gemini", model => geminiClient(model)),
    ("Vertex AI", "vertexai", model => vertexClient(model))
  )

  private def blocksOf(message: AssistantMessage, provider: String): Seq[ujson.Obj] =
    message.thinking
      .collect { case ThinkingBlock.Opaque(`provider`, data) => ujson.read(data).obj }
      .map(ujson.Obj.from(_))

  for ((label, provider, make) <- clients) {

    // ---------------------------------------------------------------- keeping signatures

    s"$label" should "keep the signature of a function call as sealed thinking on the assistant message" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(call("get_weather", city, Some("SIG-CALL")))

      val completion = client.complete(firstTurn, CompletionOptions()).value
      val message    = completion.message

      message.toolCalls.map(_.name) shouldBe Seq("get_weather")
      message.content shouldBe ""
      message.hasSealedThinking shouldBe true
      message.thinkingBinding shouldBe defined
      val blocks = blocksOf(message, provider)
      blocks should have size 1
      blocks.head("call").str shouldBe message.toolCalls.head.id
      blocks.head("sig").str shouldBe "SIG-CALL"
    }

    it should "leave a response without signatures unsealed and unbound" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(text("Hello!"))

      val message = client.complete(firstTurn, CompletionOptions()).value.message

      message.content shouldBe "Hello!"
      message.thinking shouldBe empty
      message.hasSealedThinking shouldBe false
      message.thinkingBinding shouldBe None
    }

    it should "keep a thought summary as unsigned thinking text, out of the answer" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(text("Considering the question.", thought = true), text("Paris is sunny."))

      val completion = client.complete(firstTurn, CompletionOptions()).value

      completion.content shouldBe "Paris is sunny."
      completion.message.content shouldBe "Paris is sunny."
      completion.message.thinkingText shouldBe Some("Considering the question.")
      completion.message.hasSealedThinking shouldBe false
    }

    // ---------------------------------------------------------------- sending them back

    it should "send a function call's signature back on the same part of the model turn" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(call("get_weather", city, Some("SIG-CALL")))
      val first = client.complete(firstTurn, CompletionOptions()).value.message

      wire.body = response(text("Sunny."))
      client.complete(continuation(first), CompletionOptions()).value

      val parts = modelPartsOf(wire.lastRequest)
      parts shouldBe Seq(
        ujson.Obj(
          "functionCall"     -> ujson.Obj("name" -> "get_weather", "args" -> city),
          "thoughtSignature" -> "SIG-CALL"
        )
      )
      sentSignatures(wire.lastRequest) shouldBe Seq("SIG-CALL")
    }

    it should "send only the first of parallel calls with a signature, and every call" in {
      val (client, wire) = make("gemini-2.0-flash")
      val rome           = ujson.Obj("city" -> "Rome")
      wire.body = response(call("get_weather", city, Some("SIG-FIRST")), call("get_weather", rome))
      val first = client.complete(firstTurn, CompletionOptions()).value.message
      first.toolCalls should have size 2

      wire.body = response(text("Both sunny."))
      client.complete(continuation(first), CompletionOptions()).value

      modelPartsOf(wire.lastRequest) shouldBe Seq(
        ujson
          .Obj("functionCall" -> ujson.Obj("name" -> "get_weather", "args" -> city), "thoughtSignature" -> "SIG-FIRST"),
        ujson.Obj("functionCall" -> ujson.Obj("name" -> "get_weather", "args" -> rome))
      )
    }

    it should "split the text where a signature sits, so a signed part is never merged with an unsigned one" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(text("Hello "), text("world", Some("SIG-TEXT")))
      val first = client.complete(firstTurn, CompletionOptions()).value.message
      first.content shouldBe "Hello world"

      wire.body = response(text("Ok."))
      client.complete(
        Conversation(Seq(UserMessage("Weather in Paris and Rome?"), first, UserMessage("Again?"))),
        CompletionOptions()
      )

      modelPartsOf(wire.lastRequest) shouldBe Seq(
        ujson.Obj("text" -> "Hello "),
        ujson.Obj("text" -> "world", "thoughtSignature" -> "SIG-TEXT")
      )
    }

    it should "send back a signature that arrived on an empty text part, after the text" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(text("Hi there."), text("", Some("SIG-EMPTY")))
      val first = client.complete(firstTurn, CompletionOptions()).value.message

      wire.body = response(text("Ok."))
      client.complete(
        Conversation(Seq(UserMessage("Weather in Paris and Rome?"), first, UserMessage("Again?"))),
        CompletionOptions()
      )

      modelPartsOf(wire.lastRequest) shouldBe Seq(
        ujson.Obj("text" -> "Hi there."),
        ujson.Obj("text" -> "", "thoughtSignature" -> "SIG-EMPTY")
      )
    }

    it should "send a conversation without signatures exactly as before" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(text("Hello!"))
      val first = client.complete(firstTurn, CompletionOptions()).value.message

      client.complete(
        Conversation(Seq(UserMessage("Weather in Paris and Rome?"), first, UserMessage("Again?"))),
        CompletionOptions()
      )

      modelPartsOf(wire.lastRequest) shouldBe Seq(ujson.Obj("text" -> "Hello!"))
      sentSignatures(wire.lastRequest) shouldBe empty
    }

    // ---------------------------------------------------------------- when they are not sent

    it should "keep sending a signature when the conversation before the turn was pruned or edited" in {
      // The reverse of Anthropic's prefix rule: Google says modified or trimmed history must preserve
      // thought signatures, and Gemini 3 answers HTTP 400 when the current turn's function-call
      // signature is missing, so compressing or pruning earlier turns must not strip it.
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(call("get_weather", city, Some("SIG-CALL")))
      val first = client.complete(firstTurn, CompletionOptions()).value.message

      val edited = Conversation(
        Seq[Message](UserMessage("A different question")) ++ Seq[Message](first) ++ first.toolCalls.map(tc =>
          ToolMessage("sunny", tc.id)
        )
      )
      wire.body = response(text("Sunny."))
      client.complete(edited, CompletionOptions()).value

      sentSignatures(wire.lastRequest) shouldBe Seq("SIG-CALL")
      val signedPart = modelPartsOf(wire.lastRequest).find(_.obj.contains("functionCall")).get
      signedPart("thoughtSignature").str shouldBe "SIG-CALL"
    }

    it should "adopt Gemini's functionCall.id as the tool call's id and echo it on the functionResponse" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = s"""{"candidates":[{"content":{"role":"model","parts":[
        {"functionCall":{"id":"fc-9","name":"get_weather","args":{"city":"Paris"}},"thoughtSignature":"SIG-ID"}
      ]},"finishReason":"STOP"}]}"""

      val first = client.complete(firstTurn, CompletionOptions()).value.message
      first.toolCalls.map(_.id) shouldBe Seq("fc-9")

      wire.body = response(text("Sunny."))
      client.complete(continuation(first), CompletionOptions()).value

      val responses = wire
        .lastRequest("contents")
        .arr
        .toSeq
        .flatMap(_("parts").arr.toSeq)
        .flatMap(_.obj.get("functionResponse"))
      responses.map(_("id").str) shouldBe Seq("fc-9")
    }

    it should "replay a signed call exactly as returned: the id kept, absent args stay absent" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = s"""{"candidates":[{"content":{"role":"model","parts":[
        {"functionCall":{"id":"fc-9","name":"ping"},"thoughtSignature":"SIG-X"}
      ]},"finishReason":"STOP"}]}"""

      val first = client.complete(firstTurn, CompletionOptions()).value.message
      wire.body = response(text("Done."))
      client.complete(continuation(first), CompletionOptions()).value

      val sent = modelPartsOf(wire.lastRequest).filter(_.obj.contains("functionCall"))
      sent should have size 1
      sent.head("functionCall") shouldBe ujson.Obj("id" -> "fc-9", "name" -> "ping")
      sent.head("functionCall").obj.contains("args") shouldBe false
      sent.head("thoughtSignature").str shouldBe "SIG-X"
    }

    it should "not put an id on the functionResponse of a call Gemini sent without one" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(call("get_weather", city, Some("SIG-CALL")))
      val first = client.complete(firstTurn, CompletionOptions()).value.message

      wire.body = response(text("Sunny."))
      client.complete(continuation(first), CompletionOptions()).value

      val responses = wire
        .lastRequest("contents")
        .arr
        .toSeq
        .flatMap(_("parts").arr.toSeq)
        .flatMap(_.obj.get("functionResponse"))
      responses should have size 1
      responses.head.obj.contains("id") shouldBe false
    }

    it should "not send a signature once the turn's own tool calls or content were changed" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(call("get_weather", city, Some("SIG-CALL")))
      val first   = client.complete(firstTurn, CompletionOptions()).value.message
      val changed = first.withToolCalls(first.toolCalls.map(tc => tc.copy(arguments = ujson.Obj("city" -> "Berlin"))))

      wire.body = response(text("Sunny."))
      client.complete(continuation(changed), CompletionOptions()).value

      sentSignatures(wire.lastRequest) shouldBe empty
    }

    it should "not send a signature to a different model of the same provider" in {
      val (producer, producerWire) = make("gemini-2.0-flash")
      producerWire.body = response(call("get_weather", city, Some("SIG-CALL")))
      val first = producer.complete(firstTurn, CompletionOptions()).value.message
      first.hasSealedThinking shouldBe true

      val (other, otherWire) = make("gemini-1.5-pro")
      otherWire.body = response(text("Sunny."))
      other.complete(continuation(first), CompletionOptions()).value

      sentSignatures(otherWire.lastRequest) shouldBe empty
    }

    it should "not send a signature that the other Gemini client produced" in {
      val (producer, producerWire) = if (provider == "gemini") vertexClient() else geminiClient()
      producerWire.body = response(call("get_weather", city, Some("SIG-FOREIGN")))
      val foreign = producer.complete(firstTurn, CompletionOptions()).value.message
      foreign.hasSealedThinking shouldBe true

      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(text("Sunny."))
      client.complete(continuation(foreign), CompletionOptions()).value

      sentSignatures(wire.lastRequest) shouldBe empty
    }

    it should "ignore a block that is not a signature for this client or is malformed" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.body = response(call("get_weather", city, Some("SIG-CALL")))
      val first = client.complete(firstTurn, CompletionOptions()).value.message
      val noisy = first.withThinking(
        first.thinking ++ Seq(
          ThinkingBlock.Opaque("openrouter", """{"call":"x","sig":"WRONG"}"""),
          ThinkingBlock.Opaque(provider, "not json")
        )
      )

      wire.body = response(text("Sunny."))
      client.complete(continuation(noisy), CompletionOptions()).value

      // re-bound by hand it would be replayable; unbound, the whole message is unsealed: nothing is sent
      sentSignatures(wire.lastRequest) should not contain "WRONG"
    }

    // ---------------------------------------------------------------- streaming

    it should "keep a streamed function call's signature, matched to the call that carries it" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.sse = sse(text("Let me check. "), call("get_weather", city, Some("SIG-STREAM")))
      val chunks     = ListBuffer.empty[StreamedChunk]
      val completion = client.streamComplete(firstTurn, CompletionOptions(), chunks += _).value
      val message    = completion.message

      completion.toolCalls should have size 1
      message.content shouldBe "Let me check. "
      message.hasSealedThinking shouldBe true
      val blocks = blocksOf(message, provider)
      blocks should have size 1
      blocks.head("call").str shouldBe completion.toolCalls.head.id
      blocks.head("sig").str shouldBe "SIG-STREAM"

      wire.body = response(text("Sunny."))
      client.complete(continuation(message), CompletionOptions()).value
      modelPartsOf(wire.lastRequest) shouldBe Seq(
        ujson.Obj("text" -> "Let me check. "),
        ujson.Obj(
          "functionCall"     -> ujson.Obj("name" -> "get_weather", "args" -> city),
          "thoughtSignature" -> "SIG-STREAM"
        )
      )
    }

    it should "record where a signature on a later empty text chunk sits, and send it back after the text" in {
      val (client, wire) = make("gemini-2.0-flash")
      wire.sse = sse(text("Hel"), text("lo."), text("", Some("SIG-LAST")))
      val message = client.streamComplete(firstTurn, CompletionOptions(), _ => ()).value.message

      message.content shouldBe "Hello."
      blocksOf(message, provider).map(b => (b("at").num.toInt, b("text").str, b("sig").str)) shouldBe Seq(
        (6, "", "SIG-LAST")
      )

      wire.body = response(text("Ok."))
      client.complete(
        Conversation(Seq(UserMessage("Weather in Paris and Rome?"), message, UserMessage("Again?"))),
        CompletionOptions()
      )
      modelPartsOf(wire.lastRequest) shouldBe Seq(
        ujson.Obj("text" -> "Hello."),
        ujson.Obj("text" -> "", "thoughtSignature" -> "SIG-LAST")
      )
    }

    it should "keep every parallel call of one stream chunk, with only the first signed" in {
      val (client, wire) = make("gemini-2.0-flash")
      val rome           = ujson.Obj("city" -> "Rome")
      wire.sse = sse(call("get_weather", city, Some("SIG-FIRST")) + "," + call("get_weather", rome))
      val chunks     = ListBuffer.empty[StreamedChunk]
      val completion = client.streamComplete(firstTurn, CompletionOptions(), chunks += _).value

      completion.toolCalls.map(_.arguments("city").str) shouldBe Seq("Paris", "Rome")
      chunks.flatMap(_.toolCall).map(_.id).distinct should have size 2

      wire.body = response(text("Both sunny."))
      client.complete(continuation(completion.message), CompletionOptions()).value
      modelPartsOf(wire.lastRequest).map(p => p.obj.get("thoughtSignature").map(_.str)) shouldBe Seq(
        Some("SIG-FIRST"),
        None
      )
    }
  }

  // ---------------------------------------------------------------- the helper on its own

  private def newIds(): () => String = {
    var n = 0
    () => { n += 1; s"id-$n" }
  }

  private def parseParts(parts: String*): ujson.Value = ujson.read(s"[${parts.mkString(",")}]")

  "GeminiThoughtSignatures.parse" should "record the offset of each signed text part" in {
    val parsed = GeminiThoughtSignatures.parse(
      "gemini",
      parseParts(text("ab"), text("cde", Some("S1")), text("", Some("S2"))).arr.toSeq,
      textOffset = 10,
      newId = newIds()
    )

    parsed.text shouldBe "abcde"
    parsed.signatures.collect { case ThinkingBlock.Opaque(_, d) => ujson.read(d)("at").num.toInt } shouldBe Seq(12, 15)
  }

  it should "skip a signature on a part that is neither text nor a function call, and on a thought summary" in {
    val parts = parseParts(
      part("inlineData" -> ujson.Obj("mimeType" -> "image/png", "data" -> "AAAA"), "thoughtSignature" -> "S-IMG"),
      text("reasoning", Some("S-THOUGHT"), thought = true),
      text("answer")
    ).arr.toSeq

    val parsed = GeminiThoughtSignatures.parse("gemini", parts, 0, newIds())

    parsed.signatures shouldBe empty
    parsed.text shouldBe "answer"
    parsed.thought shouldBe "reasoning"
  }

  it should "accept a function call without args" in {
    val parts  = parseParts(part("functionCall" -> ujson.Obj("name" -> "ping"))).arr.toSeq
    val parsed = GeminiThoughtSignatures.parse("gemini", parts, 0, newIds())

    parsed.calls.map(c => (c.name, c.arguments)) shouldBe Seq(("ping", ujson.Obj()))
  }

  "GeminiThoughtSignatures.parts" should "send one unsigned part when the signed text no longer fits the content" in {
    val message = AssistantMessage(
      contentOpt = Some("Something else entirely"),
      thinking = Seq(ThinkingBlock.Opaque("gemini", ujson.Obj("at" -> 0, "text" -> "Hello", "sig" -> "S").render()))
    )

    GeminiThoughtSignatures.parts("gemini", message) shouldBe Seq(ujson.Obj("text" -> "Something else entirely"))
  }

  it should "send one unsigned part when signed texts overlap" in {
    val message = AssistantMessage(
      contentOpt = Some("abcdef"),
      thinking = Seq(
        ThinkingBlock.Opaque("gemini", ujson.Obj("at" -> 0, "text" -> "abcd", "sig" -> "S1").render()),
        ThinkingBlock.Opaque("gemini", ujson.Obj("at" -> 2, "text" -> "cdef", "sig" -> "S2").render())
      )
    )

    GeminiThoughtSignatures.parts("gemini", message) shouldBe Seq(ujson.Obj("text" -> "abcdef"))
  }

  it should "keep a signed part in the middle of the text, with plain parts either side" in {
    val message = AssistantMessage(
      contentOpt = Some("one two three"),
      thinking = Seq(ThinkingBlock.Opaque("gemini", ujson.Obj("at" -> 4, "text" -> "two ", "sig" -> "S").render()))
    )

    GeminiThoughtSignatures.parts("gemini", message) shouldBe Seq(
      ujson.Obj("text" -> "one "),
      ujson.Obj("text" -> "two ", "thoughtSignature" -> "S"),
      ujson.Obj("text" -> "three")
    )
  }

  it should "send a function call's signature but not the thought summary text kept beside it" in {
    val call = ToolCall(id = "call-1", name = "f", arguments = ujson.Obj())
    val message = AssistantMessage(
      toolCalls = Seq(call),
      thinking = Seq(
        ThinkingBlock.Text("The model's own reasoning summary."),
        ThinkingBlock.Opaque("gemini", ujson.Obj("call" -> "call-1", "sig" -> "S").render())
      )
    )

    GeminiThoughtSignatures.parts("gemini", message) shouldBe Seq(
      ujson.Obj("functionCall" -> ujson.Obj("name" -> "f", "args" -> ujson.Obj()), "thoughtSignature" -> "S")
    )
  }

  it should "not attach a signature to a call it does not belong to" in {
    val call = ToolCall(id = "real-id", name = "f", arguments = ujson.Obj())
    val message = AssistantMessage(
      toolCalls = Seq(call),
      thinking = Seq(ThinkingBlock.Opaque("gemini", ujson.Obj("call" -> "other-id", "sig" -> "S").render()))
    )

    GeminiThoughtSignatures.parts("gemini", message) shouldBe Seq(
      ujson.Obj("functionCall" -> ujson.Obj("name" -> "f", "args" -> ujson.Obj()))
    )
  }

  "GeminiThoughtSignatures.chunks" should "put the text on the first chunk and the finish reason on the last" in {
    val parsed = GeminiThoughtSignatures.Parsed(
      text = "hi",
      thought = "hmm",
      calls = Seq(ToolCall("a", "f", ujson.Obj()), ToolCall("b", "g", ujson.Obj())),
      signatures = Seq.empty
    )

    val chunks = GeminiThoughtSignatures.chunks(parsed, "m", Some("STOP"))

    chunks.map(_.toolCall.map(_.id)) shouldBe Seq(Some("a"), Some("b"))
    chunks.map(_.content) shouldBe Seq(Some("hi"), None)
    chunks.map(_.thinkingDelta) shouldBe Seq(Some("hmm"), None)
    chunks.map(_.finishReason) shouldBe Seq(None, Some("STOP"))
  }

  it should "make a single chunk when there is no function call, even for an empty part" in {
    val parsed = GeminiThoughtSignatures.Parsed("", "", Seq.empty, Seq.empty)

    val chunks = GeminiThoughtSignatures.chunks(parsed, "m", Some("STOP"))

    chunks should have size 1
    chunks.head.content shouldBe None
    chunks.head.finishReason shouldBe Some("STOP")
  }

  // -------------------------------------------------------------------------

  implicit private class ResultOps[A](private val r: org.llm4s.types.Result[A]) {
    def value: A = r.fold(e => fail(s"expected success, got $e"), identity)
  }
}
