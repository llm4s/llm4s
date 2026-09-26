package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import org.llm4s.model.ModelRegistryService

/**
 * `CohereClient` against a local server speaking Cohere's Compatibility API.
 *
 * These cases moved from core with the client (#1132). The client used to call Cohere's
 * native v2 `/v2/chat`; it now calls `/compatibility/v1/chat/completions` on the shared
 * `OpenAICompatibleClient`, so the server here speaks that format, and each case keeps its
 * old intent in the new format. Two say what changed: the case that picked the first
 * non-empty element of v2's `message.content` array - a shape the Compatibility API does not
 * send - now checks the request shape instead, and a reply with no text is an empty
 * completion rather than an error. Streaming is in `CohereClientStreamingSpec`.
 */
class CohereClientSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def withServer(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/compatibility/v1/chat/completions", exchange => handler(exchange))
    server.start()

    val baseUrl = s"http://localhost:${server.getAddress.getPort}/compatibility/v1"

    try
      test(baseUrl)
    finally
      server.stop(0)
  }

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  }

  private def reply(content: String, usage: String = ""): String =
    s"""{
       |  "id": "a1b2c3",
       |  "object": "chat.completion",
       |  "created": 1700000000,
       |  "model": "command-r",
       |  "choices": [
       |    { "index": 0, "message": { "role": "assistant", "content": $content }, "finish_reason": "stop" }
       |  ]$usage
       |}""".stripMargin

  private val usage = """, "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }"""

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  private def config(baseUrl: String): CohereConfig =
    CohereConfig(
      apiKey = "test-key",
      model = "command-r",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  "CohereClient.complete" should "parse a successful response" in withServer { exchange =>
    respond(exchange, 200, reply("\"Hello world\"", usage))
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true

    val completion = result.toOption.get
    completion.content shouldBe "Hello world"
    completion.id shouldBe "a1b2c3"
    completion.usage.isDefined shouldBe true
    completion.usage.get.promptTokens shouldBe 10
    completion.usage.get.completionTokens shouldBe 5
  }

  // Was "pick the first non-empty text element from message.content": v2 replied with an array
  // of content parts, and the client had to find the text among them. The Compatibility API
  // replies with a plain string, so the part of the old case that still means something is
  // the other direction - what goes out: string content, and the system prompt under the
  // `developer` role Cohere documents.
  it should "send plain string content, with the system prompt under the developer role" in {
    var sent: Option[ujson.Value] = None
    withServer { exchange =>
      sent = Some(ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      respond(exchange, 200, reply("\"ok\"", usage))
    } { baseUrl =>
      val client = new CohereClient(config(baseUrl))
      val result =
        client.complete(Conversation(Seq(SystemMessage("Be brief."), UserMessage("hello"))), CompletionOptions())
      result.map(_.content) shouldBe Right("ok")
    }
    val messages = sent.value("messages").arr
    messages.map(_("role").str) shouldBe Seq("developer", "user")
    messages.map(_("content").str) shouldBe Seq("Be brief.", "hello")
    sent.value("model").str shouldBe "command-r"
  }

  // Was "fail with ValidationError when required text is missing". Now that Cohere supports
  // tools, a reply whose content is empty is ordinary - the model may have answered with tool
  // calls only - so it is an empty completion, as for every provider on the shared client.
  it should "return an empty completion when the reply has no text" in withServer { exchange =>
    respond(exchange, 200, reply("null"))
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true
    result.toOption.get.content shouldBe ""
    result.toOption.get.message.contentOpt shouldBe None
    result.toOption.get.toolCalls shouldBe empty
  }

  it should "map HTTP 401 to AuthenticationError" in withServer { exchange =>
    respond(exchange, 401, """{ "message": "nope" }""")
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in withServer { exchange =>
    respond(exchange, 429, """{ "message": "slow down" }""")
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[RateLimitError]
  }

  it should "map HTTP 5xx to ServiceError" in withServer { exchange =>
    respond(exchange, 500, """{ "message": "boom" }""")
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err shouldBe a[ServiceError]
    err.context("httpStatus") shouldBe "500"
  }

  // #1185 made Cohere record exactly one exchange per call, success or failure; the shared
  // client does the same for every provider.
  it should "record provider exchanges when logging is enabled" in withServer { exchange =>
    respond(exchange, 200, reply("\"Logged response\"", usage))
  } { baseUrl =>
    val exchanges = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink {
      override def record(exchange: ProviderExchange): Unit =
        exchanges += exchange
    }
    val client = new CohereClient(
      config(baseUrl),
      exchangeLogging = ProviderExchangeLogging.Enabled(sink)
    )

    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true
    exchanges should have size 1
    exchanges.head.provider shouldBe "cohere"
    exchanges.head.model shouldBe Some("command-r")
    exchanges.head.requestBody should include("hello")
    exchanges.head.responseBody.value should include("Logged response")
  }
}
