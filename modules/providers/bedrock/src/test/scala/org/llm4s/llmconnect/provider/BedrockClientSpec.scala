package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.{ AuthenticationError, NetworkError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.llmconnect.config.{ BedrockConfig, BedrockCredentials }
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/**
 * `BedrockClient` against a local server speaking Bedrock's wire formats - JSON for Converse and
 * the binary event stream for ConverseStream - so the AWS SDK's own request signing, error
 * parsing and event decoding run for real. No network, no AWS account.
 */
class BedrockClientSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val conversation = Conversation(Seq(UserMessage("Hello")))

  private def bodyOf(exchange: HttpExchange): ujson.Value =
    ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))

  private def collect(
    client: BedrockClient,
    conv: Conversation = conversation,
    options: CompletionOptions = CompletionOptions()
  ) = {
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conv, options, chunk => chunks += chunk)
    (result, chunks.toList)
  }

  "BedrockClient.complete" should {

    "parse a Converse response into a completion with usage" in {
      withServer("/")(sendJsonResponse(_, 200, converseResponse("Hello! How can I help you?"))) { url =>
        val completion = new BedrockClient(config(url)).complete(conversation, CompletionOptions()).toOption.value

        completion.content shouldBe "Hello! How can I help you?"
        completion.model shouldBe Model
        completion.usage.map(u => (u.promptTokens, u.completionTokens, u.totalTokens)) shouldBe Some((10, 8, 18))
      }
    }

    "send the model id in the path, system text apart from messages, and the inference settings" in {
      var seen: Option[(String, ujson.Value)] = None
      withServer("/") { exchange =>
        seen = Some((exchange.getRequestURI.getPath, bodyOf(exchange)))
        sendJsonResponse(exchange, 200, converseResponse("ok"))
      } { url =>
        val conv = Conversation(
          Seq(
            SystemMessage("Be brief."),
            UserMessage("Hi"),
            AssistantMessage(Some("Hello"), Seq.empty),
            UserMessage("2+2?")
          )
        )
        new BedrockClient(config(url)).complete(conv, CompletionOptions(maxTokens = Some(100), temperature = 0.25))
      }

      val (path, body) = seen.value
      path should (startWith("/model/").and(endWith("/converse")))
      body("system").arr.map(_("text").str).toSeq shouldBe Seq("Be brief.")
      body("messages").arr.map(m => (m("role").str, m("content")(0)("text").str)).toSeq shouldBe
        Seq(("user", "Hi"), ("assistant", "Hello"), ("user", "2+2?"))
      body("inferenceConfig")("maxTokens").num shouldBe 100.0
      body("inferenceConfig")("temperature").num shouldBe 0.25
    }

    "sign the request with the configured credentials, including a session token" in {
      var headers: Map[String, String] = Map.empty
      withServer("/") { exchange =>
        headers = exchange.getRequestHeaders
          .entrySet()
          .toArray
          .map { e =>
            val entry = e.asInstanceOf[java.util.Map.Entry[String, java.util.List[String]]]
            entry.getKey.toLowerCase -> entry.getValue.get(0)
          }
          .toMap
        sendJsonResponse(exchange, 200, converseResponse("ok"))
      } { url =>
        val withToken =
          config(url).copy(credentials = Some(BedrockCredentials("AKIDEXAMPLE", "secret", Some("session-token-1"))))
        new BedrockClient(withToken).complete(conversation, CompletionOptions())
      }

      headers("authorization") should (include("AWS4-HMAC-SHA256")
        .and(include("Credential=AKIDEXAMPLE/"))
        .and(include("/us-east-1/bedrock/")))
      headers("x-amz-security-token") shouldBe "session-token-1"
    }

    "return an assistant message with no text when Bedrock answers with no content blocks" in {
      val empty =
        """{"output":{"message":{"role":"assistant","content":[]}},"stopReason":"end_turn","usage":{"inputTokens":5,"outputTokens":0,"totalTokens":5}}"""
      withServer("/")(sendJsonResponse(_, 200, empty)) { url =>
        val completion = new BedrockClient(config(url)).complete(conversation, CompletionOptions()).toOption.value
        completion.content shouldBe ""
        completion.toolCalls should have size 0
      }
    }

    "parse tool use blocks into tool calls, with integer-valued arguments intact" in {
      val body =
        """{"output":{"message":{"role":"assistant","content":[{"toolUse":{"toolUseId":"tc-42","name":"get_weather","input":{"location":"Paris","days":3}}}]}},"stopReason":"tool_use","usage":{"inputTokens":20,"outputTokens":10,"totalTokens":30}}"""
      withServer("/")(sendJsonResponse(_, 200, body)) { url =>
        val completion = new BedrockClient(config(url)).complete(conversation, CompletionOptions()).toOption.value
        completion.toolCalls should have size 1
        completion.toolCalls.head.id shouldBe "tc-42"
        completion.toolCalls.head.name shouldBe "get_weather"
        completion.toolCalls.head.arguments("location").str shouldBe "Paris"
        completion.toolCalls.head.arguments("days").num shouldBe 3.0
      }
    }

    "send tool definitions, and tool calls and results from earlier turns" in {
      var body: ujson.Value = ujson.Null
      withServer("/") { exchange =>
        body = bodyOf(exchange)
        sendJsonResponse(exchange, 200, converseResponse("The weather is 22C"))
      } { url =>
        val tool = ToolBuilder[Map[String, Any], String](
          "get_weather",
          "Returns current weather for a location",
          Schema
            .`object`[Map[String, Any]]("Weather params")
            .withProperty(Schema.property("location", Schema.string("City name")))
        ).withHandler(_ => Right("sunny")).buildSafe().toOption.get
        val conv = Conversation(
          Seq(
            UserMessage("What is the weather in London?"),
            AssistantMessage(None, Seq(ToolCall("tc-1", "get_weather", ujson.Obj("location" -> "London")))),
            ToolMessage(toolCallId = "tc-1", content = """{"temp": 22}""")
          )
        )
        new BedrockClient(config(url)).complete(conv, CompletionOptions(tools = Seq(tool)))
      }

      body("toolConfig")("tools")(0)("toolSpec")("name").str shouldBe "get_weather"
      val messages = body("messages").arr
      messages(1)("role").str shouldBe "assistant"
      messages(1)("content")(0)("toolUse")("toolUseId").str shouldBe "tc-1"
      messages(2)("role").str shouldBe "user"
      messages(2)("content")(0)("toolResult")("toolUseId").str shouldBe "tc-1"
    }

    "refuse a conversation with only a system message, without calling Bedrock" in {
      var called = false
      withServer("/") { exchange =>
        called = true; sendJsonResponse(exchange, 200, converseResponse("never"))
      } { url =>
        val result = new BedrockClient(config(url))
          .complete(Conversation(Seq(SystemMessage("Only a system prompt"))), CompletionOptions())
        result.left.toOption.value shouldBe a[ValidationError]
      }
      called shouldBe false
    }

    "record the exchange when logging is enabled" in {
      val exchanges = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink {
        override def record(exchange: ProviderExchange): Unit = exchanges += exchange
      }
      withServer("/")(sendJsonResponse(_, 200, converseResponse("Logged response"))) { url =>
        val client = new BedrockClient(config(url), exchangeLogging = ProviderExchangeLogging.Enabled(sink))
        client.complete(conversation, CompletionOptions()).isRight shouldBe true
      }

      exchanges should have size 1
      exchanges.head.provider shouldBe "bedrock"
      exchanges.head.model shouldBe Some(Model)
      exchanges.head.requestBody should include("Hello")
      exchanges.head.responseBody.value should include("Logged response")
    }
  }

  "BedrockClient error mapping" should {

    def failWith(status: Int, errorType: String): org.llm4s.error.LLMError =
      var error: Option[org.llm4s.error.LLMError] = None
      withServer("/")(sendJsonResponse(_, status, errorBody(errorType, "boom"))) { url =>
        error = new BedrockClient(config(url)).complete(conversation, CompletionOptions()).left.toOption
      }
      error.value

    "map ThrottlingException (429) to RateLimitError" in {
      failWith(429, "ThrottlingException") shouldBe a[RateLimitError]
    }

    "map ServiceQuotaExceededException (400) to RateLimitError" in {
      failWith(400, "ServiceQuotaExceededException") shouldBe a[RateLimitError]
    }

    "map ValidationException (400) to ValidationError" in {
      failWith(400, "ValidationException") shouldBe a[ValidationError]
    }

    "map AccessDeniedException (403) to AuthenticationError" in {
      failWith(403, "AccessDeniedException") shouldBe a[AuthenticationError]
    }

    "map a 403 the SDK has no named exception for to AuthenticationError" in {
      failWith(403, "UnrecognizedClientException") shouldBe a[AuthenticationError]
    }

    "map InternalServerException (500) to ServiceError carrying the status" in {
      failWith(500, "InternalServerException") should matchPattern { case e: ServiceError if e.httpStatus == 500 => }
    }

    "map a failure to reach Bedrock at all to NetworkError, not UnknownError" in {
      val client = new BedrockClient(config("http://localhost:1"))
      client.complete(conversation, CompletionOptions()).left.toOption.value shouldBe a[NetworkError]
    }
  }

  "BedrockClient.streamComplete" should {

    "stream the text deltas through ConverseStream, in order, then return the accumulated completion" in {
      withServer("/") { exchange =>
        isStreamRequest(exchange) shouldBe true
        sendEventStream(exchange, textStream("Hel", "lo!"))
      } { url =>
        val (result, chunks) = collect(new BedrockClient(config(url)))

        chunks.flatMap(_.content) shouldBe List("Hel", "lo!")
        val completion = result.toOption.value
        completion.content shouldBe "Hello!"
        completion.model shouldBe Model
        completion.usage.map(u => (u.promptTokens, u.completionTokens)) shouldBe Some((10, 5))
        chunks.last.finishReason shouldBe Some("end_turn")
      }
    }

    "stream a tool call's name and argument fragments into one tool call" in {
      val frames = Seq(
        eventFrame("messageStart", """{"role":"assistant"}"""),
        eventFrame(
          "contentBlockStart",
          """{"contentBlockIndex":1,"start":{"toolUse":{"toolUseId":"tc-9","name":"get_weather"}}}"""
        ),
        eventFrame(
          "contentBlockDelta",
          ujson
            .Obj("contentBlockIndex" -> 1, "delta" -> ujson.Obj("toolUse" -> ujson.Obj("input" -> """{"loc""")))
            .render()
        ),
        eventFrame(
          "contentBlockDelta",
          ujson
            .Obj(
              "contentBlockIndex" -> 1,
              "delta"             -> ujson.Obj("toolUse" -> ujson.Obj("input" -> """ation":"Rome"}"""))
            )
            .render()
        ),
        eventFrame("contentBlockStop", """{"contentBlockIndex":1}"""),
        eventFrame("messageStop", """{"stopReason":"tool_use"}"""),
        eventFrame(
          "metadata",
          """{"usage":{"inputTokens":7,"outputTokens":3,"totalTokens":10},"metrics":{"latencyMs":5}}"""
        )
      )
      withServer("/")(sendEventStream(_, frames)) { url =>
        val (result, _) = collect(new BedrockClient(config(url)))

        val completion = result.toOption.value
        completion.toolCalls should have size 1
        completion.message.toolCalls shouldBe completion.toolCalls
        completion.toolCalls.head.id shouldBe "tc-9"
        completion.toolCalls.head.name shouldBe "get_weather"
        completion.toolCalls.head.arguments("location").str shouldBe "Rome"
      }
    }

    "stream reasoning text as a thinking delta" in {
      val frames = Seq(
        eventFrame(
          "contentBlockDelta",
          """{"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"Let me think"}}}"""
        ),
        textDelta("Done", index = 1),
        eventFrame("messageStop", """{"stopReason":"end_turn"}""")
      )
      withServer("/")(sendEventStream(_, frames)) { url =>
        val (result, chunks) = collect(new BedrockClient(config(url)))
        chunks.flatMap(_.thinkingDelta) shouldBe List("Let me think")
        result.toOption.value.thinking shouldBe Some("Let me think")
        result.toOption.value.content shouldBe "Done"
      }
    }

    "map an error status before the stream starts, as complete does" in {
      withServer("/")(sendJsonResponse(_, 429, errorBody("ThrottlingException", "slow down"))) { url =>
        collect(new BedrockClient(config(url)))._1.left.toOption.value shouldBe a[RateLimitError]
      }
    }

    "map an exception event in the middle of the stream, keeping the chunks already delivered" in {
      val frames = Seq(textDelta("partial"), exceptionFrame("throttlingException", "slow down"))
      withServer("/")(sendEventStream(_, frames)) { url =>
        val (result, chunks) = collect(new BedrockClient(config(url)))
        chunks.flatMap(_.content) shouldBe List("partial")
        result.left.toOption.value shouldBe a[RateLimitError]
      }
    }

    "refuse a conversation with only a system message" in {
      val (result, chunks) =
        collect(new BedrockClient(config("http://localhost:1")), Conversation(Seq(SystemMessage("system only"))))
      result.left.toOption.value shouldBe a[ValidationError]
      chunks should have size 0
    }

    "record the exchange, with the events it received" in {
      val exchanges = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink {
        override def record(exchange: ProviderExchange): Unit = exchanges += exchange
      }
      withServer("/")(sendEventStream(_, textStream("Hi", "!"))) { url =>
        val client = new BedrockClient(config(url), exchangeLogging = ProviderExchangeLogging.Enabled(sink))
        collect(client)._1.isRight shouldBe true
      }
      exchanges should have size 1
      exchanges.head.responseBody.value should include("Hi")
    }
  }

  "BedrockClient lifecycle" should {

    "return CancelledError, and keep the interrupt flag, when a stream is interrupted after its first chunk" in {
      withServer("/")(streamFramesThenHold(_, Seq(textDelta("first")))) { url =>
        org.llm4s.testkit.ProviderModuleChecks.assertCancelsStreamWhenInterrupted(new BedrockClient(config(url)))
      }
    }

    "report the closed client rather than calling Bedrock" in {
      val client = new BedrockClient(config("http://localhost:1"))
      client.close()
      client.close() // idempotent
      client.complete(conversation, CompletionOptions()).isLeft shouldBe true
      client.streamComplete(conversation, CompletionOptions(), _ => ()).isLeft shouldBe true
    }

    "close an async client that streaming started" in {
      withServer("/")(sendEventStream(_, textStream("a", "b"))) { url =>
        val client = new BedrockClient(config(url))
        collect(client)._1.isRight shouldBe true
        noException should be thrownBy client.close()
      }
    }

    "build through the factory" in {
      BedrockClient(config("http://localhost:1")).isRight shouldBe true
    }

    "return a Left, not an exception, and send nothing, when the configured profile does not exist" in {
      var called = false
      withServer("/") { exchange =>
        called = true; sendJsonResponse(exchange, 200, converseResponse("never"))
      } { url =>
        val client = BedrockClient(config(url).copy(credentials = None, profile = Some("llm4s-no-such-profile")))
        client.toOption.value.complete(conversation, CompletionOptions()).isLeft shouldBe true
      }
      called shouldBe false
    }

    "report its context window and completion reserve from the config" in {
      val client = new BedrockClient(config("http://localhost:1"))
      client.getContextWindow() shouldBe 32000
      client.getReserveCompletion() shouldBe 4096
    }

    "build with the default credential chain when the config names no credentials" in {
      BedrockClient(
        BedrockConfig("us-east-1", Model, 32000, 4096, endpointUrl = Some("http://localhost:1"))
      ).isRight shouldBe true
    }
  }

  "BedrockClient's document conversion" should {

    "round-trip objects, arrays and scalars, keeping whole numbers whole" in {
      val original = ujson.Obj(
        "name"   -> "tool",
        "count"  -> 42,
        "ratio"  -> 0.5,
        "active" -> true,
        "none"   -> ujson.Null,
        "tags"   -> ujson.Arr("a", "b"),
        "meta"   -> ujson.Obj("key" -> "value")
      )
      val doc = BedrockClient.ujsonToDocument(original)
      doc.asMap().get("count").asNumber().stringValue() shouldBe "42"

      BedrockClient.documentToUjson(doc) shouldBe ujson.Obj(
        "name"   -> "tool",
        "count"  -> 42.0,
        "ratio"  -> 0.5,
        "active" -> true,
        "none"   -> ujson.Null,
        "tags"   -> ujson.Arr("a", "b"),
        "meta"   -> ujson.Obj("key" -> "value")
      )
    }
  }
}
