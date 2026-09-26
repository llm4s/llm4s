package org.llm4s.llmconnect.provider

import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/**
 * Cohere streaming, which did not exist before Cohere moved onto the shared client:
 * `CohereClient.streamComplete` returned "not supported" (#925). Cohere's Compatibility API
 * streams OpenAI-format chunks ending in `[DONE]`, so the base client's parser serves it; no
 * second parser for Cohere's native v2 event stream is needed.
 */
class CohereClientStreamingSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val path = "/compatibility/v1/chat/completions"

  private def config(baseUrl: String) = CohereConfig("test-key", "command-a-03-2025", baseUrl, 256000, 4096)

  private def event(delta: String, finish: String = "null", usage: String = ""): String =
    s"""data: {"id":"c-s","object":"chat.completion.chunk","created":1700000000,"model":"command-a-03-2025",""" +
      s""""choices":[{"index":0,"delta":$delta,"finish_reason":$finish}]$usage}"""

  private def sse(events: String*): String = (events :+ "data: [DONE]").mkString("\n\n") + "\n\n"

  "CohereClient.streamComplete" should "stream text, with usage, from the Compatibility API" in {
    var requestBody = ""
    withServer(path) { exchange =>
      requestBody = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      sendSseResponse(
        exchange,
        sse(
          event("""{"role":"assistant","content":""}"""),
          event("""{"content":"Recursive"}"""),
          event("""{"content":" loops"}"""),
          event("{}", "\"stop\"", ""","usage":{"prompt_tokens":8,"completion_tokens":3,"total_tokens":11}""")
        )
      )
    } { root =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val completion = new CohereClient(config(s"$root/compatibility/v1"))
        .streamComplete(Conversation(Seq(UserMessage("Write a haiku"))), CompletionOptions(), chunks += _)
        .value

      completion.content shouldBe "Recursive loops"
      completion.usage shouldBe Some(TokenUsage(8, 3, 11))
      chunks.flatMap(_.content).mkString shouldBe "Recursive loops"
    }
    ujson.read(requestBody)("stream").bool shouldBe true
  }

  it should "stream a tool call split across deltas" in
    withServer(path) { exchange =>
      sendSseResponse(
        exchange,
        sse(
          event(
            """{"role":"assistant","tool_calls":[{"index":0,"id":"get_flight_info0","type":"function",""" +
              """"function":{"name":"get_flight_info","arguments":""}}]}"""
          ),
          event("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"loc_origin\": \"Miami\","}}]}"""),
          event("""{"tool_calls":[{"index":0,"function":{"arguments":" \"loc_destination\": \"Seattle\"}"}}]}"""),
          event("{}", "\"tool_calls\"")
        )
      )
    } { root =>
      val completion = new CohereClient(config(s"$root/compatibility/v1"))
        .streamComplete(Conversation(Seq(UserMessage("Next flight?"))), CompletionOptions(), _ => ())
        .value

      completion.toolCalls.map(tc => (tc.id, tc.name, tc.arguments)) shouldBe List(
        ("get_flight_info0", "get_flight_info", ujson.Obj("loc_origin" -> "Miami", "loc_destination" -> "Seattle"))
      )
    }

  it should "reach the compatibility endpoint from a configured native API root" in
    withServer(path)(exchange => sendSseResponse(exchange, sse(event("""{"content":"hi"}""", "\"stop\"")))) { root =>
      new CohereClient(config(root))
        .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
        .value
        .content shouldBe "hi"
    }

  it should "map an error status, deliver no chunks, and record one exchange" in {
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit = recorded += exchange

    withServer(path)(exchange => sendJsonResponse(exchange, 401, """{"message":"invalid api token"}""")) { root =>
      var chunks = 0
      val result = new CohereClient(
        config(s"$root/compatibility/v1"),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      ).streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => chunks += 1)

      result.left.value shouldBe an[AuthenticationError]
      chunks shouldBe 0
    }
    recorded should have size 1
    recorded.head.provider shouldBe "cohere"
  }
}
