package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ProcessingError, ServiceError, ValidationError }
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.llm4s.testutil.SmallStack
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import upickle.default.{ macroRW, ReadWriter }

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._

/**
 * Tool calling through Ollama's native `/api/chat` (#1219): the client sends `tools`, forwards a
 * tool-call turn and its `role: tool` results, and reads `message.tool_calls` - whole or
 * streamed - into `ToolCall`s. Ollama's native API sends no tool-call ids, so the client
 * synthesizes them; they must be unique across a conversation, because a `ToolMessage` finds its
 * call by id.
 *
 * Everything runs against a local fake server: no Ollama, no network.
 */
class OllamaToolCallingSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  import OllamaToolCallingSpec._

  private def config(baseUrl: String): OllamaConfig =
    OllamaConfig(model = "llama3.1", baseUrl = baseUrl, contextWindow = 4096, reserveCompletion = 512)

  private val bodyBuilder = new OllamaClient(config("http://localhost:11434"))

  private def requestBody(conversation: Conversation, options: CompletionOptions = CompletionOptions()): ujson.Obj =
    bodyBuilder.createRequestBody(conversation, options, stream = false)

  private val weather = ToolCall("call_a", "get_weather", ujson.Obj("location" -> "Paris"))
  private val time    = ToolCall("call_b", "get_time", ujson.Obj("zone" -> "UTC"))

  /** A fake `/api/chat`: answers each request with the next of `responses`, recording what it was sent. */
  private def withOllama(responses: String*)(test: (OllamaClient, () => List[ujson.Value]) => Any): Unit = {
    val seen = new ConcurrentLinkedQueue[ujson.Value]()
    val next = new AtomicInteger(0)
    withServer("/api/chat") { exchange =>
      seen.add(ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      sendJsonResponse(exchange, 200, responses(math.min(next.getAndIncrement(), responses.size - 1)))
    } { baseUrl =>
      val client = new OllamaClient(config(baseUrl))
      try test(client, () => seen.asScala.toList)
      finally client.close()
    }
  }

  private def ask(client: OllamaClient, tools: Seq[ToolFunction[_, _]] = Seq(weatherTool)): Result[Completion] =
    client.complete(
      Conversation(Seq(UserMessage("What is the weather in Paris?"))),
      CompletionOptions().withTools(tools)
    )

  private type Result[A] = org.llm4s.types.Result[A]

  "the request body" should {

    "send an assistant message's thinking text as its `thinking`, and none when it has none" in {
      val thought = AssistantMessage("Paris is sunny.").withThinking(
        Seq(
          ThinkingBlock.Text("Recall ", Some("sig")),
          ThinkingBlock.Redacted("x"),
          ThinkingBlock.Text("the forecast.")
        )
      )
      val messages =
        requestBody(Conversation(Seq(UserMessage("hi"), thought, AssistantMessage("plain"))))("messages").arr
      messages(1)("thinking").str shouldBe "Recall the forecast."
      messages(2).obj.contains("thinking") shouldBe false
    }

    "carry no `tools` field when the options hold no tools" in {
      requestBody(Conversation(Seq(UserMessage("hi")))).obj.contains("tools") shouldBe false
    }

    "carry the tools in Ollama's function format, without OpenAI's strict flag" in {
      val body  = requestBody(Conversation(Seq(UserMessage("hi"))), CompletionOptions().withTools(Seq(weatherTool)))
      val tools = body("tools").arr

      tools should have size 1
      tools.head("type").str shouldBe "function"
      val function = tools.head("function")
      function("name").str shouldBe "get_weather"
      function("description").str shouldBe "Get the current weather in a location"
      function("parameters")("type").str shouldBe "object"
      function("parameters")("properties")("location")("type").str shouldBe "string"
      function.obj.contains("strict") shouldBe false
    }

    "forward an assistant turn's tool calls with their ids and object arguments" in {
      val body = requestBody(
        Conversation(
          Seq(UserMessage("weather?"), AssistantMessage(None, Seq(weather)), ToolMessage("Sunny", weather.id))
        )
      )

      val assistant = body("messages")(1)
      assistant("role").str shouldBe "assistant"
      assistant("content").str shouldBe ""
      val calls = assistant("tool_calls").arr
      calls should have size 1
      calls.head("function")("name").str shouldBe "get_weather"
      calls.head("function")("arguments") shouldBe ujson.Obj("location" -> "Paris")
      calls.head("id").str shouldBe weather.id
      calls.head("function")("index").num shouldBe 0
    }

    "give each of an assistant turn's parallel tool calls its own consecutive `function.index`" in {
      val body = requestBody(
        Conversation(
          Seq(
            UserMessage("both?"),
            AssistantMessage(None, Seq(weather, time, ToolCall("c3", "get_weather", ujson.Obj("location" -> "Rome"))))
          )
        )
      )

      val calls = body("messages")(1)("tool_calls").arr
      calls.map(_("function")("index").num.toInt) shouldBe Seq(0, 1, 2)
      calls.map(_("id").str) shouldBe Seq(weather.id, time.id, "c3")
    }

    "number `function.index` afresh in each assistant turn" in {
      val body = requestBody(
        Conversation(
          Seq(
            UserMessage("q"),
            AssistantMessage(None, Seq(weather)),
            ToolMessage("Sunny", weather.id),
            AssistantMessage(None, Seq(time)),
            ToolMessage("12:00", time.id)
          )
        )
      )

      body("messages")(1)("tool_calls")(0)("function")("index").num shouldBe 0
      body("messages")(3)("tool_calls")(0)("function")("index").num shouldBe 0
    }

    "keep the text of an assistant turn that has both text and tool calls" in {
      val body = requestBody(
        Conversation(Seq(UserMessage("q"), AssistantMessage(Some("Let me look."), Seq(weather))))
      )

      body("messages")(1)("content").str shouldBe "Let me look."
      body("messages")(1)("tool_calls").arr should have size 1
    }

    "send arguments that arrive as a JSON string as an object, and anything else as an empty object" in {
      def argumentsSent(arguments: ujson.Value): ujson.Value =
        requestBody(
          Conversation(Seq(UserMessage("q"), AssistantMessage(None, Seq(ToolCall("c", "get_weather", arguments)))))
        )("messages")(1)("tool_calls")(0)("function")("arguments")

      argumentsSent(ujson.Str("""{"location":"Rome"}""")) shouldBe ujson.Obj("location" -> "Rome")
      argumentsSent(ujson.Str("not json")) shouldBe ujson.Obj()
      argumentsSent(ujson.Str("[1,2]")) shouldBe ujson.Obj()
      argumentsSent(ujson.Str("\"Paris\"")) shouldBe ujson.Obj()
      argumentsSent(ujson.Arr(1, 2)) shouldBe ujson.Obj()
      argumentsSent(ujson.Num(7)) shouldBe ujson.Obj()
      argumentsSent(ujson.Null) shouldBe ujson.Obj()
    }

    "send an empty object for string arguments nested too deeply, never the parsed document" in {
      // A string argument is model text (the accumulator keeps streamed arguments as a `Str`). Parsing
      // one this deep succeeds - the parser is iterative - but the object it builds overflows the stack
      // when the request body is rendered, so it is refused before parsing and sent as `{}`, as any
      // string that is not a JSON object is. On a 1 MB stack, so an overflow is a `Left` here (#1562).
      val deep = "{\"a\":" * 100000 + "1" + "}" * 100000
      val call = ToolCall("c", "get_weather", ujson.Str(deep))
      val outcome = SmallStack.run {
        val body = requestBody(Conversation(Seq(UserMessage("q"), AssistantMessage(None, Seq(call)))))
        val sent = body("messages")(1)("tool_calls")(0)("function")("arguments")
        (sent == ujson.Obj(), ujson.write(body).length < 10000)
      }
      outcome shouldBe Right((true, true))
    }

    "send a tool result as `role: tool`, naming the tool of the call it answers" in {
      val body = requestBody(
        Conversation(
          Seq(UserMessage("weather?"), AssistantMessage(None, Seq(weather)), ToolMessage("Sunny", weather.id))
        )
      )

      val result = body("messages")(2)
      result("role").str shouldBe "tool"
      result("content").str shouldBe "Sunny"
      result("tool_name").str shouldBe "get_weather"
      result("tool_call_id").str shouldBe weather.id
    }

    "send a server-provided call id on both the assistant's call and the result that answers it" in {
      val serverCall = ToolCall("call_from_server_7", "get_weather", ujson.Obj("location" -> "Paris"))
      val body = requestBody(
        Conversation(
          Seq(UserMessage("weather?"), AssistantMessage(None, Seq(serverCall)), ToolMessage("Sunny", serverCall.id))
        )
      )

      body("messages")(1)("tool_calls")(0)("id").str shouldBe "call_from_server_7"
      body("messages")(2)("tool_call_id").str shouldBe "call_from_server_7"
    }

    "match each of several tool results to its own call by id, whatever order they come in" in {
      val body = requestBody(
        Conversation(
          Seq(
            UserMessage("both?"),
            AssistantMessage(None, Seq(weather, time)),
            ToolMessage("12:00", time.id),
            ToolMessage("Sunny", weather.id)
          )
        )
      )

      body("messages")(2)("tool_name").str shouldBe "get_time"
      body("messages")(3)("tool_name").str shouldBe "get_weather"
    }

    "omit `tool_name` when the call a result answers is not in the conversation" in {
      val body = requestBody(Conversation(Seq(UserMessage("q"), ToolMessage("orphan", "call_unknown"))))

      body("messages")(1)("role").str shouldBe "tool"
      body("messages")(1).obj.contains("tool_name") shouldBe false
      body("messages")(1)("tool_call_id").str shouldBe "call_unknown"
    }
  }

  "a non-streaming reply" should {

    "send the tools over HTTP and read one tool call, with a synthesized id" in {
      withOllama(reply("", call("get_weather", ujson.Obj("location" -> "Paris")))) { (client, seen) =>
        val completion = ask(client).toOption.get

        seen().head("tools").arr should have size 1
        completion.toolCalls should have size 1
        val toolCall = completion.toolCalls.head
        toolCall.name shouldBe "get_weather"
        toolCall.arguments shouldBe ujson.Obj("location" -> "Paris")
        (toolCall.id should fullyMatch).regex(callId)
        completion.message.toolCalls shouldBe completion.toolCalls
        completion.message.contentOpt shouldBe None
        completion.content shouldBe ""
        completion.hasToolCalls shouldBe true
      }
    }

    "read `message.thinking` beside the tool calls as the completion's thinking" in {
      val message = ujson.Obj(
        "role"       -> "assistant",
        "content"    -> "",
        "thinking"   -> "The user wants Paris weather; call the tool.",
        "tool_calls" -> ujson.Arr(call("get_weather", ujson.Obj("location" -> "Paris")))
      )
      withOllama(rawReply(message)) { (client, _) =>
        val completion = ask(client).toOption.get

        completion.thinking shouldBe Some("The user wants Paris weather; call the tool.")
        completion.message.thinkingText shouldBe Some("The user wants Paris weather; call the tool.")
        completion.toolCalls.map(_.name) shouldBe List("get_weather")
      }
    }

    "send the tool-call turn's thinking back with its content and tool calls in the follow-up request (#1381)" in {
      val message = ujson.Obj(
        "role"       -> "assistant",
        "content"    -> "",
        "thinking"   -> "The user wants Paris weather; call the tool.",
        "tool_calls" -> ujson.Arr(call("get_weather", ujson.Obj("location" -> "Paris")))
      )
      withOllama(rawReply(message), reply("Sunny.")) { (client, seen) =>
        val first  = ask(client).toOption.get
        val callId = first.toolCalls.head.id
        val followUp =
          Conversation(Seq(UserMessage("What is the weather in Paris?"), first.message, ToolMessage("sunny", callId)))
        client.complete(followUp, CompletionOptions().withTools(Seq(weatherTool))) shouldBe a[Right[_, _]]

        val replayed = seen()(1)("messages").arr(1)
        replayed("role").str shouldBe "assistant"
        replayed("thinking").str shouldBe "The user wants Paris weather; call the tool."
        replayed("tool_calls").arr.map(_("function")("name").str) shouldBe Seq("get_weather")
      }
    }

    "report no thinking when `message.thinking` is absent or empty" in {
      withOllama(reply("Hi"))((client, _) => ask(client).toOption.get.thinking shouldBe None)
      withOllama(rawReply(ujson.Obj("role" -> "assistant", "content" -> "Hi", "thinking" -> ""))) { (client, _) =>
        ask(client).toOption.get.thinking shouldBe None
      }
    }

    "read several tool calls in order, with distinct ids" in {
      withOllama(
        reply("", call("get_weather", ujson.Obj("location" -> "Paris")), call("get_time", ujson.Obj("zone" -> "UTC")))
      ) { (client, _) =>
        val calls = ask(client).toOption.get.toolCalls

        calls.map(_.name) shouldBe List("get_weather", "get_time")
        calls.map(_.id).distinct should have size 2
        calls.map(c => indexOf(c.id)) shouldBe List(0, 1)
        calls.map(c => prefixOf(c.id)).distinct should have size 1
      }
    }

    "give the calls of different replies different ids, so a conversation never repeats one" in {
      val one = reply("", call("get_weather", ujson.Obj("location" -> "Paris")))
      withOllama(one, one) { (client, _) =>
        val first  = ask(client).toOption.get.toolCalls.head.id
        val second = ask(client).toOption.get.toolCalls.head.id

        first should not be second
        prefixOf(first) should not be prefixOf(second)
      }
    }

    "keep the text of a reply that also calls a tool" in {
      withOllama(reply("Let me check.", call("get_weather", ujson.Obj("location" -> "Paris")))) { (client, _) =>
        val completion = ask(client).toOption.get

        completion.content shouldBe "Let me check."
        completion.message.contentOpt shouldBe Some("Let me check.")
        completion.toolCalls should have size 1
      }
    }

    "use an id the server sends, when it sends one" in {
      withOllama(reply("", call("get_weather", ujson.Obj("location" -> "Paris"), id = Some("call_srv_1")))) {
        (client, _) => ask(client).toOption.get.toolCalls.map(_.id) shouldBe List("call_srv_1")
      }
    }

    "read arguments that a server sends as a JSON string" in {
      withOllama(reply("", call("get_weather", ujson.Str("""{"location":"Paris"}""")))) { (client, _) =>
        ask(client).toOption.get.toolCalls.head.arguments shouldBe ujson.Obj("location" -> "Paris")
      }
    }

    "read a call without arguments as an empty object" in {
      withOllama(reply("", ujson.Obj("function" -> ujson.Obj("name" -> "get_time")))) { (client, _) =>
        ask(client).toOption.get.toolCalls.head.arguments shouldBe ujson.Obj()
      }
    }

    "refuse string arguments nested too deeply as a malformed call, never an overflow" in {
      // The string is model text inside the envelope's string literal: the envelope parses, and the
      // second parse is the boundary. On a 1 MB stack, reduced inside the thread, since rendering a
      // value this deep in a failure message would itself overflow (#1562).
      val deep = "{\"a\":" * 100000 + "1" + "}" * 100000
      withOllama(reply("", call("get_weather", ujson.Str(deep)))) { (client, _) =>
        val outcome = SmallStack.run(
          ask(client).left.map(e => (e.getClass.getSimpleName, e.message)).map(_ => "a completion")
        )
        outcome match {
          case Right(Left((kind, message))) =>
            kind shouldBe "ProcessingError"
            message should include("malformed tool call")
            message should include("arguments are nested more than 512 levels deep")
          case Right(Right(other)) => fail(s"expected a Left, got $other")
          case Left(thrown)        => fail(s"expected a Left, but complete threw $thrown")
        }
      }
    }

    "name the depth when refusing string arguments just over the limit, never 'not a JSON object'" in {
      // 513 levels: one over the limit, and a document the parser would otherwise have read as an object.
      // The site tells a too-deep refusal from a parse failure through `BoundedJson.TooDeep` (#1651); if
      // that detection fell through, the call would be refused for the wrong reason.
      val justOver = "{\"a\":" * 513 + "1" + "}" * 513
      withOllama(reply("", call("get_weather", ujson.Str(justOver)))) { (client, _) =>
        ask(client) match {
          case Left(e: ProcessingError) =>
            e.message should include("arguments are nested more than 512 levels deep")
            (e.message should not).include("not a JSON object")
          case other => fail(s"expected a ProcessingError, got $other")
        }
      }
      val atLimit = "{\"a\":" * 512 + "1" + "}" * 512
      withOllama(reply("", call("get_weather", ujson.Str(atLimit)))) { (client, _) =>
        ask(client).toOption.get.toolCalls.head.arguments.obj.keySet shouldBe Set("a")
      }
    }

    "read no tool calls from a missing, null or empty `tool_calls`" in {
      def callsOf(message: ujson.Obj): List[ToolCall] = {
        var calls = List.empty[ToolCall]
        withOllama(rawReply(message))((client, _) => calls = ask(client).toOption.get.toolCalls)
        calls
      }

      callsOf(ujson.Obj("role" -> "assistant", "content" -> "hi")) shouldBe Nil
      callsOf(ujson.Obj("role" -> "assistant", "content" -> "hi", "tool_calls" -> ujson.Null)) shouldBe Nil
      callsOf(ujson.Obj("role" -> "assistant", "content" -> "hi", "tool_calls" -> ujson.Arr())) shouldBe Nil
    }

    "turn a malformed tool call into a typed error, not an exception" in {
      val malformed = Seq(
        "`tool_calls` is not an array" -> ujson.Obj("content" -> "", "tool_calls" -> ujson.Str("oops")),
        "a call that is not an object" -> ujson.Obj("content" -> "", "tool_calls" -> ujson.Arr(ujson.Str("x"))),
        "a call without `function`"    -> ujson.Obj("content" -> "", "tool_calls" -> ujson.Arr(ujson.Obj())),
        "a function without a name" -> ujson.Obj(
          "content"    -> "",
          "tool_calls" -> ujson.Arr(ujson.Obj("function" -> ujson.Obj("arguments" -> ujson.Obj())))
        ),
        "a blank name" -> ujson.Obj("content" -> "", "tool_calls" -> ujson.Arr(call("  ", ujson.Obj()))),
        "arguments that are not JSON" -> ujson.Obj(
          "content"    -> "",
          "tool_calls" -> ujson.Arr(call("get_weather", ujson.Str("not json")))
        ),
        "arguments that are a number" -> ujson.Obj(
          "content"    -> "",
          "tool_calls" -> ujson.Arr(call("get_weather", ujson.Num(3)))
        ),
        "two calls with one id" -> ujson.Obj(
          "content" -> "",
          "tool_calls" -> ujson.Arr(
            call("get_weather", ujson.Obj("a" -> 1), id = Some("c1")),
            call("get_time", ujson.Obj(), id = Some("c1"))
          )
        )
      )

      malformed.foreach { case (label, message) =>
        withOllama(rawReply(message)) { (client, _) =>
          withClue(label) {
            val result = ask(client)
            result.isLeft shouldBe true
            result.left.toOption.get shouldBe a[ProcessingError]
          }
        }
      }
    }
  }

  "a model without tool support" should {

    /** A fake `/api/chat` that answers every request with `status` and `body`. */
    def withStatus(status: Int, body: String)(test: OllamaClient => Any): Unit =
      withServer("/api/chat")(exchange => sendJsonResponse(exchange, status, body)) { baseUrl =>
        val client = new OllamaClient(config(baseUrl))
        try test(client)
        finally client.close()
      }

    // What Ollama answers (HTTP 400) when `tools` is sent to a model whose capabilities lack it.
    val unsupported = """{"error":"registry.ollama.ai/library/llama3:latest does not support tools"}"""

    def streamAsk(client: OllamaClient, tools: Seq[ToolFunction[_, _]] = Seq(weatherTool)): Result[Completion] =
      client.streamComplete(
        Conversation(Seq(UserMessage("What is the weather in Paris?"))),
        CompletionOptions().withTools(tools),
        _ => ()
      )

    def validation(result: Result[Completion]): ValidationError =
      result.left.toOption.getOrElse(fail("expected a failure")) match {
        case error: ValidationError => error
        case other                  => fail(s"expected a ValidationError, got $other")
      }

    "be a ValidationError on `tools` that names the model and the missing capability" in {
      withStatus(400, unsupported) { client =>
        val error = validation(ask(client))
        error.field shouldBe "tools"
        error.message should include("llama3.1")
        error.message should include("does not support tool calling")
      }
    }

    "be reported the same way when streaming" in {
      withStatus(400, unsupported) { client =>
        val error = validation(streamAsk(client))
        error.field shouldBe "tools"
        error.message should include("llama3.1")
      }
    }

    "be recognised whatever the case of the server's message" in {
      withStatus(400, """{"error":"Model DOES NOT SUPPORT TOOLS"}""") { client =>
        validation(ask(client)).field shouldBe "tools"
      }
    }

    "leave any other 400 to the generic mapping" in {
      withStatus(400, """{"error":"invalid option: temperature"}""") { client =>
        validation(ask(client)).field shouldBe "request"
        validation(streamAsk(client)).field shouldBe "request"
      }
    }

    "leave the same message alone when the request sent no tools" in {
      withStatus(400, unsupported)(client => validation(ask(client, tools = Seq.empty)).field shouldBe "request")
    }

    "leave the same message alone on any other status" in {
      withStatus(500, unsupported)(client => ask(client).left.toOption.get shouldBe a[ServiceError])
    }
  }

  "a full tool round trip" should {

    "send the tool call and its result back, and read the final answer" in {
      withOllama(
        reply("", call("get_weather", ujson.Obj("location" -> "Paris"))),
        reply("It is sunny in Paris.")
      ) { (client, seen) =>
        val options  = CompletionOptions().withTools(Seq(weatherTool))
        val first    = client.complete(Conversation(Seq(UserMessage("Weather in Paris?"))), options).toOption.get
        val toolCall = first.toolCalls.head

        val messages = Seq(
          UserMessage("Weather in Paris?"),
          first.message,
          ToolMessage("Sunny, 22C", toolCall.id)
        )
        Message.validateConversation(messages.toList) shouldBe Right(())

        val second = client.complete(Conversation(messages), options).toOption.get
        second.content shouldBe "It is sunny in Paris."

        val sent = seen()(1)("messages").arr
        sent.map(_("role").str) shouldBe Seq("user", "assistant", "tool")
        sent(1)("tool_calls")(0)("function")("name").str shouldBe "get_weather"
        sent(2)("tool_name").str shouldBe "get_weather"
        sent(2)("content").str shouldBe "Sunny, 22C"
      }
    }
  }

  "a streamed reply" should {

    def stream(client: OllamaClient): (Result[Completion], List[StreamedChunk]) = {
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(
        Conversation(Seq(UserMessage("What is the weather in Paris?"))),
        CompletionOptions().withTools(Seq(weatherTool)),
        chunks += _
      )
      (result, chunks.toList)
    }

    "read a whole tool call, with a synthesized id, and finish with `tool_calls`" in {
      withOllama(
        ndjson(
          line("", Seq(call("get_weather", ujson.Obj("location" -> "Paris")))),
          doneLine()
        )
      ) { (client, seen) =>
        val (result, chunks) = stream(client)
        val completion       = result.toOption.get

        seen().head("stream").bool shouldBe true
        seen().head("tools").arr should have size 1
        completion.toolCalls should have size 1
        completion.toolCalls.head.name shouldBe "get_weather"
        completion.toolCalls.head.arguments shouldBe ujson.Obj("location" -> "Paris")
        (completion.toolCalls.head.id should fullyMatch).regex(callId)
        chunks.flatMap(_.toolCall) should have size 1
        chunks.last.finishReason shouldBe Some("tool_calls")
        completion.usage.map(_.totalTokens) shouldBe Some(15)
      }
    }

    "accumulate streamed `thinking` into the completion, including thinking on the tool-call line" in {
      withOllama(
        ndjson(
          line("", thinking = Some("The user wants ")),
          line("", Seq(call("get_weather", ujson.Obj("location" -> "Paris"))), thinking = Some("Paris weather.")),
          doneLine()
        )
      ) { (client, _) =>
        val (result, chunks) = stream(client)
        val completion       = result.toOption.get

        chunks.flatMap(_.thinkingDelta) shouldBe List("The user wants ", "Paris weather.")
        completion.thinking shouldBe Some("The user wants Paris weather.")
        completion.message.thinking shouldBe Seq(ThinkingBlock.Text("The user wants Paris weather."))
        completion.toolCalls.map(_.name) shouldBe List("get_weather")
        chunks.last.finishReason shouldBe Some("tool_calls")
      }
    }

    "finish with `stop` when no tool was called" in {
      withOllama(ndjson(line("Hello"), doneLine())) { (client, _) =>
        val (result, chunks) = stream(client)

        result.toOption.get.content shouldBe "Hello"
        result.toOption.get.toolCalls shouldBe Nil
        chunks.last.finishReason shouldBe Some("stop")
      }
    }

    "keep text that comes before the tool call" in {
      withOllama(
        ndjson(
          line("Checking. "),
          line("", Seq(call("get_weather", ujson.Obj("location" -> "Paris")))),
          doneLine()
        )
      ) { (client, _) =>
        val completion = stream(client)._1.toOption.get

        completion.content shouldBe "Checking. "
        completion.toolCalls.map(_.name) shouldBe List("get_weather")
      }
    }

    "number the calls of one stream consecutively, across chunks, with one prefix" in {
      withOllama(
        ndjson(
          line("", Seq(call("get_weather", ujson.Obj("location" -> "Paris")), call("get_time", ujson.Obj()))),
          line("", Seq(call("get_weather", ujson.Obj("location" -> "Rome")))),
          doneLine()
        )
      ) { (client, _) =>
        val calls = stream(client)._1.toOption.get.toolCalls

        calls.map(_.name) shouldBe List("get_weather", "get_time", "get_weather")
        calls.map(c => indexOf(c.id)) shouldBe List(0, 1, 2)
        calls.map(c => prefixOf(c.id)).distinct should have size 1
        calls.map(_.id).distinct should have size 3
      }
    }

    "give two streams different id prefixes" in {
      val body = ndjson(line("", Seq(call("get_weather", ujson.Obj("location" -> "Paris")))), doneLine())
      withOllama(body, body) { (client, _) =>
        val first  = stream(client)._1.toOption.get.toolCalls.head.id
        val second = stream(client)._1.toOption.get.toolCalls.head.id

        prefixOf(first) should not be prefixOf(second)
      }
    }

    "read a whole call that carries the server's id, as Ollama streams it" in {
      withOllama(
        ndjson(
          line("", Seq(call("get_weather", ujson.Obj("location" -> "Paris"), id = Some("call_x")))),
          line("", Seq(call("get_weather", ujson.Str("""{"location":"Rome"}"""), id = Some("call_y")))),
          doneLine()
        )
      ) { (client, _) =>
        val (result, chunks) = stream(client)
        val calls            = result.toOption.get.toolCalls

        calls.map(_.id) shouldBe List("call_x", "call_y")
        calls.map(_.name) shouldBe List("get_weather", "get_weather")
        calls.map(_.arguments) shouldBe List(ujson.Obj("location" -> "Paris"), ujson.Obj("location" -> "Rome"))
        chunks.flatMap(_.toolCall).map(_.id) shouldBe List("call_x", "call_y")
      }
    }

    // Ollama emits each call whole, with a fresh id; a repeated id is not a continuation to merge, and
    // passing it to the accumulator, which keys calls by id, would concatenate `{"a":1}{"b":2}`.
    "reject an id the stream already used, without merging the two entries" in {
      withOllama(
        ndjson(
          line("", Seq(call("get_weather", ujson.Obj("a" -> 1), id = Some("call_y")))),
          line("", Seq(call("get_weather", ujson.Obj("b" -> 2), id = Some("call_y")))),
          line("never read"),
          doneLine()
        )
      ) { (client, _) =>
        val (result, chunks) = stream(client)

        result.left.toOption.get shouldBe a[ProcessingError]
        result.left.toOption.get.message should include("call_y")
        chunks.flatMap(_.toolCall) should have size 1
        chunks.flatMap(_.content) should not contain "never read"
      }
    }

    "reject an entry that carries only a fragment of its arguments" in {
      withOllama(
        ndjson(
          line("", Seq(call("get_weather", ujson.Str("""{"location":"""), id = Some("call_x")))),
          doneLine()
        )
      )((client, _) => stream(client)._1.left.toOption.get shouldBe a[ProcessingError])
    }

    "reject an entry with an id that has no name" in {
      withOllama(
        ndjson(
          line("", Seq(ujson.Obj("id" -> "c1", "function" -> ujson.Obj("arguments" -> ujson.Obj("a" -> 1))))),
          doneLine()
        )
      ) { (client, _) =>
        val result = stream(client)._1
        result.left.toOption.get shouldBe a[ProcessingError]
        result.left.toOption.get.message should include("no name")
      }
    }

    "reject an entry with an id whose arguments are not an object" in {
      withOllama(
        ndjson(
          line("", Seq(ujson.Obj("id" -> "c1", "function" -> ujson.Obj("name" -> "get_weather", "arguments" -> 3)))),
          doneLine()
        )
      )((client, _) => stream(client)._1.left.toOption.get shouldBe a[ProcessingError])
    }

    "reject an entry with no id and no name, even after a call was accepted" in {
      withOllama(
        ndjson(
          line("", Seq(call("get_weather", ujson.Obj("location" -> "Paris")))),
          line("", Seq(ujson.Obj("function" -> ujson.Obj("index" -> 0, "arguments" -> ujson.Obj())))),
          doneLine()
        )
      )((client, _) => stream(client)._1.left.toOption.get shouldBe a[ProcessingError])
    }

    "turn a malformed streamed tool call into a typed error and stop reading" in {
      withOllama(
        ndjson(
          line("", Seq(ujson.Obj("function" -> ujson.Obj("arguments" -> ujson.Obj())))),
          line("never read"),
          doneLine()
        )
      ) { (client, _) =>
        val (result, chunks) = stream(client)

        result.isLeft shouldBe true
        result.left.toOption.get shouldBe a[ProcessingError]
        chunks.flatMap(_.content) should not contain "never read"
      }
    }
  }
}

private object OllamaToolCallingSpec {

  final case class Weather(forecast: String)
  implicit private val weatherRW: ReadWriter[Weather] = macroRW

  /** A tool whose definition the client has to send. */
  val weatherTool: ToolFunction[Map[String, Any], Weather] =
    ToolBuilder[Map[String, Any], Weather](
      "get_weather",
      "Get the current weather in a location",
      Schema
        .`object`[Map[String, Any]]("Weather parameters")
        .withProperty(Schema.property("location", Schema.string("City or location name")))
    ).withHandler(extractor => extractor.getString("location").map(location => Weather(s"Sunny in $location")))
      .buildSafe()
      .fold(error => throw new IllegalStateException(error.message), identity)

  /** The shape of an id the client synthesizes: `call_<12 hex>_<index>`. */
  val callId = """call_[0-9a-f]{12}_\d+"""

  private val callIdPattern = """call_[0-9a-f]{12}_(\d+)""".r

  def indexOf(id: String): Int = id match {
    case callIdPattern(index) => index.toInt
    case other                => throw new IllegalStateException(s"not a synthesized id: $other")
  }

  def prefixOf(id: String): String = id.substring(0, id.lastIndexOf('_'))

  /** One entry of `message.tool_calls`; `id` only when a server sends one. */
  def call(name: String, arguments: ujson.Value, id: Option[String] = None): ujson.Obj = {
    val entry = ujson.Obj("function" -> ujson.Obj("name" -> name, "arguments" -> arguments))
    id.foreach(i => entry("id") = i)
    entry
  }

  /** A complete (non-streaming) `/api/chat` reply. */
  def reply(content: String, calls: ujson.Value*): String = {
    val message = ujson.Obj("role" -> "assistant", "content" -> content)
    if (calls.nonEmpty) message("tool_calls") = ujson.Arr.from(calls)
    rawReply(message)
  }

  def rawReply(message: ujson.Obj): String =
    ujson.write(
      ujson.Obj(
        "model"             -> "llama3.1",
        "message"           -> message,
        "done"              -> true,
        "prompt_eval_count" -> 10,
        "eval_count"        -> 5
      )
    )

  /** One NDJSON line of a streamed reply. */
  def line(content: String, calls: Seq[ujson.Value] = Nil, thinking: Option[String] = None): ujson.Value = {
    val message = ujson.Obj("role" -> "assistant", "content" -> content)
    if (calls.nonEmpty) message("tool_calls") = ujson.Arr.from(calls)
    thinking.foreach(t => message("thinking") = t)
    ujson.Obj("message" -> message, "done" -> false)
  }

  /** The closing line, which is where Ollama puts the token counts. */
  def doneLine(): ujson.Value =
    ujson.Obj(
      "message"           -> ujson.Obj("role" -> "assistant", "content" -> ""),
      "done"              -> true,
      "done_reason"       -> "stop",
      "prompt_eval_count" -> 10,
      "eval_count"        -> 5
    )

  def ndjson(lines: ujson.Value*): String = lines.map(ujson.write(_)).mkString("", "\n", "\n")
}
