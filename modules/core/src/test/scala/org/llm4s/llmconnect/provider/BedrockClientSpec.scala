package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError, UnknownError, ValidationError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.{ BedrockConfig, ContextWindowResolver }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  CompletionOptions,
  Conversation,
  SystemMessage,
  ToolCall,
  ToolMessage,
  UserMessage
}
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

class BedrockClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def withServer(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/", exchange => handler(exchange))
    server.start()
    val baseUrl = s"http://localhost:${server.getAddress.getPort}"
    try test(baseUrl)
    finally server.stop(0)
  }

  private def config(endpointUrl: String): BedrockConfig =
    BedrockConfig(
      region = "us-east-1",
      model = "amazon.titan-text-express-v1",
      contextWindow = 32000,
      reserveCompletion = 4096,
      accessKeyId = Some("test-key-id"),
      secretAccessKey = Some("test-secret-key"),
      endpointUrl = Some(endpointUrl)
    )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Hello")))

  private def sendJson(exchange: HttpExchange, statusCode: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(statusCode, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  }

  private def bedrockSuccessResponse(text: String): String =
    s"""{
       |  "output": {
       |    "message": {
       |      "role": "assistant",
       |      "content": [{"text": "$text"}]
       |    }
       |  },
       |  "stopReason": "end_turn",
       |  "usage": {
       |    "inputTokens": 10,
       |    "outputTokens": 8,
       |    "totalTokens": 18
       |  }
       |}""".stripMargin

  "BedrockClient.complete" should "parse a successful Bedrock Converse response" in withServer { exchange =>
    sendJson(exchange, 200, bedrockSuccessResponse("Hello! How can I help you?"))
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.content shouldBe "Hello! How can I help you?"
        completion.model shouldBe "amazon.titan-text-express-v1"
        completion.usage.isDefined shouldBe true
        completion.usage.foreach { u =>
          u.promptTokens shouldBe 10
          u.completionTokens shouldBe 8
          u.totalTokens shouldBe 18
        }
      }
    )
  }

  it should "return correct context window" in {
    val cfg    = config("http://localhost:9999")
    val client = new BedrockClient(cfg)
    client.getContextWindow() shouldBe 32000
  }

  it should "return correct reserve completion" in {
    val cfg    = config("http://localhost:9999")
    val client = new BedrockClient(cfg)
    client.getReserveCompletion() shouldBe 4096
  }

  it should "handle response with empty content blocks gracefully" in withServer { exchange =>
    val body =
      """{
        |  "output": {
        |    "message": {
        |      "role": "assistant",
        |      "content": []
        |    }
        |  },
        |  "stopReason": "end_turn",
        |  "usage": {"inputTokens": 5, "outputTokens": 0, "totalTokens": 5}
        |}""".stripMargin
    sendJson(exchange, 200, body)
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.content shouldBe ""
        completion.toolCalls shouldBe empty
      }
    )
  }

  it should "map HTTP 403 to AuthenticationError" in withServer { exchange =>
    val body =
      """{
        |  "__type": "AccessDeniedException",
        |  "message": "User is not authorized to perform bedrock:InvokeModel"
        |}""".stripMargin
    sendJson(exchange, 403, body)
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[AuthenticationError],
      _ => fail("Expected Left(AuthenticationError)")
    )
  }

  it should "map HTTP 429 to RateLimitError" in withServer { exchange =>
    val body =
      """{
        |  "__type": "ThrottlingException",
        |  "message": "Too many requests"
        |}""".stripMargin
    sendJson(exchange, 429, body)
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[RateLimitError],
      _ => fail("Expected Left(RateLimitError)")
    )
  }

  it should "map HTTP 400 ServiceQuotaExceededException to RateLimitError" in withServer { exchange =>
    val body =
      """{
        |  "__type": "ServiceQuotaExceededException",
        |  "message": "Service quota exceeded"
        |}""".stripMargin
    sendJson(exchange, 400, body)
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[RateLimitError],
      _ => fail("Expected Left(RateLimitError)")
    )
  }

  it should "map HTTP 400 to ValidationError" in withServer { exchange =>
    val body =
      """{
        |  "__type": "ValidationException",
        |  "message": "Input validation failed"
        |}""".stripMargin
    sendJson(exchange, 400, body)
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ValidationError],
      _ => fail("Expected Left(ValidationError)")
    )
  }

  it should "map HTTP 500 to ServiceError" in withServer { exchange =>
    val body =
      """{
        |  "__type": "InternalServerException",
        |  "message": "Internal server error"
        |}""".stripMargin
    sendJson(exchange, 500, body)
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ServiceError],
      _ => fail("Expected Left(ServiceError)")
    )
  }

  it should "map a non-SDK exception (connection refused) to UnknownError" in {
    val cfg = BedrockConfig(
      region = "us-east-1",
      model = "amazon.titan-text-express-v1",
      contextWindow = 32000,
      reserveCompletion = 4096,
      accessKeyId = Some("test-key-id"),
      secretAccessKey = Some("test-secret-key"),
      endpointUrl = Some("http://localhost:1")
    )
    val client = new BedrockClient(cfg)
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[UnknownError],
      _ => fail("Expected Left(UnknownError)")
    )
  }

  it should "handle multi-turn conversations including system messages" in withServer { exchange =>
    sendJson(exchange, 200, bedrockSuccessResponse("Sure, I can help!"))
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val multiConv = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Hello"),
        AssistantMessage(Some("Hi there!"), Seq.empty),
        UserMessage("What is 2+2?")
      )
    )
    val result = client.complete(multiConv, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "Sure, I can help!"
    )
  }

  it should "handle ToolMessage and AssistantMessage with tool calls" in withServer { exchange =>
    sendJson(exchange, 200, bedrockSuccessResponse("The weather is 22C"))
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val toolConv = Conversation(
      Seq(
        UserMessage("What is the weather in London?"),
        AssistantMessage(
          None,
          Seq(ToolCall(id = "tc-1", name = "get_weather", arguments = ujson.Obj("location" -> "London")))
        ),
        ToolMessage(toolCallId = "tc-1", content = """{"temp": 22}""")
      )
    )
    val result = client.complete(toolConv, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "The weather is 22C"
    )
  }

  it should "parse a response with tool use blocks" in withServer { exchange =>
    val body =
      """{
        |  "output": {
        |    "message": {
        |      "role": "assistant",
        |      "content": [
        |        {
        |          "toolUse": {
        |            "toolUseId": "tc-42",
        |            "name": "get_weather",
        |            "input": {"location": "Paris"}
        |          }
        |        }
        |      ]
        |    }
        |  },
        |  "stopReason": "tool_use",
        |  "usage": {"inputTokens": 20, "outputTokens": 10, "totalTokens": 30}
        |}""".stripMargin
    sendJson(exchange, 200, body)
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.toolCalls should have size 1
        completion.toolCalls.head.id shouldBe "tc-42"
        completion.toolCalls.head.name shouldBe "get_weather"
        completion.toolCalls.head.arguments("location").str shouldBe "Paris"
      }
    )
  }

  it should "send tool definitions when options.tools is non-empty" in withServer { exchange =>
    sendJson(exchange, 200, bedrockSuccessResponse("Done"))
  } { baseUrl =>
    val tool = ToolBuilder[Map[String, Any], String](
      "get_weather",
      "Returns current weather for a location",
      Schema
        .`object`[Map[String, Any]]("Weather params")
        .withProperty(Schema.property("location", Schema.string("City name")))
    ).withHandler(_ => Right("sunny")).buildSafe().toOption.get

    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions(tools = Seq(tool)))
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "Done"
    )
  }

  it should "record provider exchanges when logging is enabled" in withServer { exchange =>
    sendJson(exchange, 200, bedrockSuccessResponse("Logged response"))
  } { baseUrl =>
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit = exchanges += exchange
    }
    val client = new BedrockClient(
      config(baseUrl),
      exchangeLogging = ProviderExchangeLogging.Enabled(sink)
    )
    val result = client.complete(conversation, CompletionOptions())

    result.isRight shouldBe true
    exchanges should have size 1
    exchanges.head.provider shouldBe "bedrock"
    exchanges.head.model shouldBe Some("amazon.titan-text-express-v1")
    exchanges.head.requestBody should include("Hello")
    exchanges.head.responseBody.value should include("Logged response")
  }

  it should "apply maxTokens from CompletionOptions" in withServer { exchange =>
    sendJson(exchange, 200, bedrockSuccessResponse("Short response"))
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.complete(conversation, CompletionOptions(maxTokens = Some(100)))
    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => completion.content shouldBe "Short response"
    )
  }

  it should "close cleanly without throwing" in {
    val client = new BedrockClient(config("http://localhost:9999"))
    noException should be thrownBy client.close()
  }

  "BedrockClient.streamComplete" should "emit full response as single chunk and return completion" in withServer {
    exchange => sendJson(exchange, 200, bedrockSuccessResponse("Streamed response"))
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val chunks = ListBuffer.empty[org.llm4s.llmconnect.model.StreamedChunk]

    val result = client.streamComplete(conversation, CompletionOptions(), chunk => chunks += chunk)

    result.fold(
      err => fail(s"Expected Right, got Left($err)"),
      completion => {
        completion.content shouldBe "Streamed response"
        chunks should have size 1
        chunks.head.content shouldBe Some("Streamed response")
        chunks.head.finishReason shouldBe Some("stop")
      }
    )
  }

  it should "propagate errors from the underlying converse call" in withServer { exchange =>
    val body =
      """{
        |  "__type": "ThrottlingException",
        |  "message": "Too many requests"
        |}""".stripMargin
    sendJson(exchange, 429, body)
  } { baseUrl =>
    val client = new BedrockClient(config(baseUrl))
    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())
    result.fold(
      err => err shouldBe a[RateLimitError],
      _ => fail("Expected Left(RateLimitError)")
    )
  }

  "BedrockClient.apply" should "construct a client via the two-arg factory method" in withServer { exchange =>
    sendJson(exchange, 200, bedrockSuccessResponse("Factory built"))
  } { baseUrl =>
    val result = BedrockClient(config(baseUrl))
    result.isRight shouldBe true
    result.foreach(client => client.complete(conversation, CompletionOptions()).isRight shouldBe true)
  }

  it should "construct a client via the three-arg factory method with exchange logging" in withServer { exchange =>
    sendJson(exchange, 200, bedrockSuccessResponse("Factory logged"))
  } { baseUrl =>
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(e: ProviderExchange): Unit = exchanges += e
    }
    val result = BedrockClient(
      config(baseUrl),
      org.llm4s.metrics.MetricsCollector.noop,
      ProviderExchangeLogging.Enabled(sink)
    )
    result.isRight shouldBe true
    result.foreach { client =>
      client.complete(conversation, CompletionOptions())
      exchanges should have size 1
    }
  }

  "BedrockClient" should "use default credential chain when no explicit credentials are provided" in {
    val cfg = BedrockConfig(
      region = "us-east-1",
      model = "amazon.titan-text-express-v1",
      contextWindow = 32000,
      reserveCompletion = 4096,
      endpointUrl = Some("http://localhost:9999")
    )
    noException should be thrownBy new BedrockClient(cfg)
  }

  "BedrockClient ujsonToDocument / documentToUjson" should "round-trip a JSON object" in {
    val cfg    = config("http://localhost:9999")
    val client = new BedrockClient(cfg)

    val original = ujson.Obj(
      "name"   -> "tool",
      "count"  -> 42.0,
      "active" -> true,
      "tags"   -> ujson.Arr("a", "b"),
      "meta"   -> ujson.Obj("key" -> "value")
    )
    val doc       = client.ujsonToDocument(original)
    val roundTrip = client.documentToUjson(doc)

    roundTrip("name").str shouldBe "tool"
    roundTrip("count").num shouldBe 42.0
    roundTrip("active").bool shouldBe true
    roundTrip("tags").arr.map(_.str).toSeq shouldBe Seq("a", "b")
    roundTrip("meta")("key").str shouldBe "value"
  }

  it should "round-trip null and primitive scalar values" in {
    val cfg    = config("http://localhost:9999")
    val client = new BedrockClient(cfg)

    client.documentToUjson(client.ujsonToDocument(ujson.Null)) shouldBe ujson.Null
    client.documentToUjson(client.ujsonToDocument(ujson.Str("hello"))) shouldBe ujson.Str("hello")
    client.documentToUjson(client.ujsonToDocument(ujson.Num(3.14))) shouldBe ujson.Num(3.14)
    client.documentToUjson(client.ujsonToDocument(ujson.Bool(false))) shouldBe ujson.Bool(false)
    client
      .documentToUjson(client.ujsonToDocument(ujson.Arr(ujson.Num(1), ujson.Num(2))))
      .arr
      .map(_.num)
      .toSeq shouldBe Seq(1.0, 2.0)
  }

  "BedrockConfig.fromValues" should "resolve context window for claude models" in {
    given ContextWindowResolver = ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())
    val cfg                     = BedrockConfig.fromValues("anthropic.claude-3-5-sonnet-20241022-v2:0", "us-east-1")
    cfg.region shouldBe "us-east-1"
    cfg.model shouldBe "anthropic.claude-3-5-sonnet-20241022-v2:0"
    cfg.contextWindow should be > 0
    cfg.reserveCompletion should be > 0
  }

  it should "apply the llama fallback for unregistered llama models" in {
    given ContextWindowResolver = ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())
    val cfg                     = BedrockConfig.fromValues("bedrock.fake-llama-experimental-v99", "eu-west-1")
    cfg.contextWindow shouldBe 128000
    cfg.region shouldBe "eu-west-1"
  }

  it should "apply the mistral fallback for unregistered mistral models" in {
    given ContextWindowResolver = ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())
    val cfg                     = BedrockConfig.fromValues("bedrock.fake-mistral-experimental-v99", "us-west-2")
    cfg.contextWindow shouldBe 32768
  }

  it should "apply the titan fallback for unregistered titan models" in {
    given ContextWindowResolver = ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())
    val cfg                     = BedrockConfig.fromValues("bedrock.fake-titan-experimental-v99", "us-east-1")
    cfg.contextWindow shouldBe 32000
  }

  it should "use a conservative default for completely unknown models" in {
    given ContextWindowResolver = ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())
    val cfg                     = BedrockConfig.fromValues("bedrock.totally-unknown-model-v99", "ap-southeast-1")
    cfg.contextWindow shouldBe 8192
  }

  "BedrockConfig.toString" should "redact the access key and show region and model" in {
    val cfg = BedrockConfig(
      region = "us-east-1",
      model = "amazon.titan-text-express-v1",
      contextWindow = 32000,
      reserveCompletion = 4096,
      accessKeyId = Some("AKIAIOSFODNN7EXAMPLE")
    )
    val str = cfg.toString
    str should include("us-east-1")
    str should include("amazon.titan-text-express-v1")
    (str should not).include("AKIAIOSFODNN7EXAMPLE")
  }

  it should "show default chain label when no access key is set" in {
    val cfg = BedrockConfig(
      region = "us-east-1",
      model = "amazon.titan-text-express-v1",
      contextWindow = 32000,
      reserveCompletion = 4096
    )
    cfg.toString should include("default chain")
  }
}
