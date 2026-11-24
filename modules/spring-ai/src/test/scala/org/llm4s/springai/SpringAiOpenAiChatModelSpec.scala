package org.llm4s.springai

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.{ BeforeAndAfterAll, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.ai.openai.{ OpenAiChatModel, OpenAiChatOptions }

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * [[SpringAiLlmClient]] over Spring AI's real `OpenAiChatModel`, which casts the prompt's options to its own
 * `OpenAiChatOptions` (#2645), against a local server that answers chat completions.
 */
class SpringAiOpenAiChatModelSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterAll {

  private val bodies = new ConcurrentLinkedQueue[String]()

  private val completionJson =
    """{"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"gpt-4o",
      |"choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
      |"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}""".stripMargin.replace("\n", "")

  private def chunk(delta: String, finish: String): String =
    s"""{"id":"chatcmpl-2","object":"chat.completion.chunk","created":1700000000,"model":"gpt-4o",
       |"choices":[{"index":0,"delta":$delta,"finish_reason":$finish}]}""".stripMargin.replace("\n", "")

  private val streamBody =
    Seq(
      chunk("""{"role":"assistant","content":"h"}""", "null"),
      chunk("""{"content":"i"}""", "null"),
      chunk("{}", "\"stop\"")
    ).map(c => s"data: $c\n\n").mkString + "data: [DONE]\n\n"

  private val server: HttpServer = {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext(
      "/",
      (exchange: HttpExchange) => {
        val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        bodies.add(body)
        val streaming = body.contains("\"stream\":true")
        val (contentType, answer) =
          if (streaming) ("text/event-stream", streamBody) else ("application/json", completionJson)
        val bytes = answer.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    s.start()
    s
  }

  override def afterAll(): Unit = server.stop(0)

  private def model(): OpenAiChatModel = {
    val options = OpenAiChatOptions.builder()
    val _       = options.baseUrl(s"http://127.0.0.1:${server.getAddress.getPort}")
    val _       = options.apiKey("test-key")
    val _       = options.model("gpt-4o")
    OpenAiChatModel.builder().options(options.build()).build()
  }

  private val conversation = Conversation(Seq(UserMessage("hi")))

  private def lastBody(): String = bodies.asScala.last

  "SpringAiLlmClient over OpenAiChatModel" should "send a completion request and return its reply" in {
    val completion = new SpringAiLlmClient(model())
      .complete(conversation, CompletionOptions().withTemperature(0.25).withMaxTokens(64))
      .value
    completion.content shouldBe "hi"
    val body = lastBody()
    body should include("\"model\":\"gpt-4o\"")
    body should include("\"temperature\":0.25")
    body should include("hi")
  }

  it should "send llm4s's tools as the model's tools" in {
    val weather = ToolBuilder[String](
      "weather",
      "The weather in a city",
      Schema.`object`[Map[String, Any]]("Weather request").withProperty(Schema.property("city", Schema.string("City")))
    ).withHandler(_ => Right("sunny")).buildSafe().value
    new SpringAiLlmClient(model())
      .complete(conversation, CompletionOptions().withTools(Seq(weather)))
      .value
      .content shouldBe "hi"
    lastBody() should include("\"weather\"")
  }

  it should "stream a completion request and return its reply" in {
    val chunks = Vector.newBuilder[StreamedChunk]
    val completion = new SpringAiLlmClient(model())
      .streamComplete(conversation, CompletionOptions(), c => chunks += c)
      .value
    completion.content shouldBe "hi"
    chunks.result().flatMap(_.content).mkString shouldBe "hi"
    lastBody() should include("\"stream\":true")
  }
}
