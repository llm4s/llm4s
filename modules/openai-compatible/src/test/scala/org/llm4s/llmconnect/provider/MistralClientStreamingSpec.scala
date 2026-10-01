package org.llm4s.llmconnect.provider

import org.llm4s.error.RateLimitError
import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/**
 * Mistral streaming, which did not exist before Mistral moved onto the shared client:
 * `MistralClient.streamComplete` returned "not supported" (#925). The events below are in the
 * shape Mistral's `/v1/chat/completions` sends with `"stream": true` - an opening delta with
 * the role and empty content, text deltas, a final delta carrying `finish_reason` and `usage`,
 * then `[DONE]`; a tool call arrives whole in one delta, with its `index` and a nine-character
 * id.
 */
class MistralClientStreamingSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def config(baseUrl: String) = MistralConfig("test-key", "mistral-small-latest", baseUrl, 128000, 4096)

  private def event(delta: String, finish: String = "null", usage: String = ""): String =
    s"""data: {"id":"cmpl-s","object":"chat.completion.chunk","created":1700000000,"model":"mistral-small-latest",""" +
      s""""choices":[{"index":0,"delta":$delta,"finish_reason":$finish}]$usage}"""

  private def sse(events: String*): String = (events :+ "data: [DONE]").mkString("\n\n") + "\n\n"

  private val usage = ""","usage":{"prompt_tokens":12,"total_tokens":19,"completion_tokens":7}"""

  "MistralClient.streamComplete" should "stream text, and report the usage on the final event" in {
    var requestBody = ""
    withServer("/v1/chat/completions") { exchange =>
      requestBody = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      sendSseResponse(
        exchange,
        sse(
          event("""{"role":"assistant","content":""}"""),
          event("""{"content":"Bonjour"}"""),
          event("""{"content":" le monde"}"""),
          event("""{"content":""}""", "\"stop\"", usage)
        )
      )
    } { baseUrl =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val completion =
        new MistralClient(config(baseUrl))
          .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), chunks += _)
          .value

      completion.content shouldBe "Bonjour le monde"
      completion.model shouldBe "mistral-small-latest"
      completion.usage shouldBe Some(TokenUsage(12, 7, 19))
      chunks.flatMap(_.content).mkString shouldBe "Bonjour le monde"
      chunks.flatMap(_.finishReason) shouldBe Seq("stop")
    }
    ujson.read(requestBody)("stream").bool shouldBe true
    ujson.read(requestBody)("model").str shouldBe "mistral-small-latest"
  }

  it should "stream a tool call, and report it on the completion" in
    withServer("/v1/chat/completions") { exchange =>
      sendSseResponse(
        exchange,
        sse(
          event("""{"role":"assistant","content":""}"""),
          event(
            """{"tool_calls":[{"id":"D681PevKs","function":{"name":"get_weather",""" +
              """"arguments":"{\"city\": \"Paris\"}"},"index":0}]}""",
            "\"tool_calls\"",
            usage
          )
        )
      )
    } { baseUrl =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val completion = new MistralClient(config(baseUrl))
        .streamComplete(Conversation(Seq(UserMessage("Weather in Paris?"))), CompletionOptions(), chunks += _)
        .value

      completion.toolCalls.map(tc => (tc.id, tc.name, tc.arguments)) shouldBe
        List(("D681PevKs", "get_weather", ujson.Obj("city" -> "Paris")))
      completion.message.toolCalls shouldBe completion.toolCalls
      chunks.flatMap(_.toolCall).map(_.name) shouldBe Seq("get_weather")
    }

  it should "stream a reasoning model's thinking chunks as thinking, and its text chunks as content" in
    withServer("/v1/chat/completions") { exchange =>
      sendSseResponse(
        exchange,
        sse(
          event(
            """{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"Two plus"}]}]}"""
          ),
          event(
            """{"content":[{"type":"thinking","thinking":[{"type":"text","text":" two."}]},""" +
              """{"type":"text","text":"It is "}]}"""
          ),
          event("""{"content":"4."}""", "\"stop\"", usage)
        )
      )
    } { baseUrl =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val completion = new MistralClient(config(baseUrl).copy(model = "magistral-small-latest"))
        .streamComplete(Conversation(Seq(UserMessage("2+2?"))), CompletionOptions(), chunks += _)
        .value

      completion.content shouldBe "It is 4."
      completion.thinking shouldBe Some("Two plus two.")
      chunks.flatMap(_.thinkingDelta).mkString shouldBe "Two plus two."
    }

  it should "map an error status, deliver no chunks, and record one exchange" in {
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit = recorded += exchange

    withServer("/v1/chat/completions") { exchange =>
      sendJsonResponse(exchange, 429, """{"message":"Requests rate limit exceeded"}""")
    } { baseUrl =>
      var chunks = 0
      val result = new MistralClient(config(baseUrl), exchangeLogging = ProviderExchangeLogging.enabled(sink))
        .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => chunks += 1)

      result.left.value shouldBe a[RateLimitError]
      chunks shouldBe 0
    }
    recorded should have size 1
    recorded.head.provider shouldBe "mistral"
    recorded.head.responseBody shouldBe Some("""{"message":"Requests rate limit exceeded"}""")
  }

  it should "post to <baseUrl>/v1/chat/completions whether or not the base URL already ends in /v1" in
    withServer("/v1/chat/completions") { exchange =>
      sendSseResponse(exchange, sse(event("""{"content":"ok"}""", "\"stop\"")))
    } { baseUrl =>
      Seq(baseUrl, s"$baseUrl/", s"$baseUrl/v1", s"$baseUrl/v1/").foreach { url =>
        withClue(url) {
          new MistralClient(config(url))
            .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
            .value
            .content shouldBe "ok"
        }
      }
    }
}
