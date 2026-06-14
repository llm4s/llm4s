package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.BedrockConfig
import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.model.ModelRegistryService
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

  "BedrockClient.streamComplete" should "emit full response as single chunk and return completion" in withServer {
    exchange =>
      sendJson(exchange, 200, bedrockSuccessResponse("Streamed response"))
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
}
