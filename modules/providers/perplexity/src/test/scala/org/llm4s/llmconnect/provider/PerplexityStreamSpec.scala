package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ServiceError }
import org.llm4s.http.StreamingHttpResponse
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ ByteArrayInputStream, IOException, InputStream }
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/** `PerplexityClient.streamComplete` over the Agent API's server-sent events. */
class PerplexityStreamSpec extends AnyWordSpec with Matchers:

  import PerplexityFixtures.*

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private def streamed(reply: => Result[StreamingHttpResponse]): (StubHttp, Result[Completion], List[StreamedChunk]) = {
    val http   = new StubHttp(stream = _ => reply)
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = client(http).streamComplete(hello, CompletionOptions(), chunks += _)
    (http, result, chunks.toList)
  }

  "a stream" should {

    "send stream: true, pass each text delta on and return the completed response with citations" in {
      val done = response(s"[$searchResults,${textMessage("Hi there")}]")
      val (http, result, chunks) =
        streamed(
          sse(created, delta("Hi"), """{"type":"response.reasoning.started"}""", delta(" there"), completedEvent(done))
        )
      http.requests.head.streaming shouldBe true
      http.requests.head.headers("Accept") shouldBe "text/event-stream"
      http.lastBody("stream").bool shouldBe true
      chunks.flatMap(_.content) shouldBe List("Hi", " there")
      chunks.head.id shouldBe "resp_s"
      chunks.last.finishReason shouldBe Some("stop")
      val completion = result.fold(e => fail(e.message), identity)
      completion.content shouldBe "Hi there"
      completion.citations.map(_.url) shouldBe List("https://a.example/1", "https://b.example/2")
      completion.usage.map(_.totalTokens) shouldBe Some(17)
      completion.estimatedCost shouldBe Some(0.0035)
    }

    "use the streamed text when the completed response carries none" in {
      val (_, result, _) = streamed(sse(delta("only"), delta(" deltas"), completedEvent(response("[]"))))
      result.map(_.content) shouldBe Right("only deltas")
    }

    "take the stream's response id when the completed response has none" in {
      val (_, result, chunks) =
        streamed(sse(created, delta("x"), completedEvent("""{"status":"completed","output":[]}""")))
      result.map(_.id) shouldBe Right("resp_s")
      chunks.last.id shouldBe "resp_s"
    }

    "report tool calls whole, after the text, with finish reason tool_calls" in {
      val done                = response(s"[${functionCall("call_1", "get_weather", """{"city":"Oslo"}""")}]")
      val (_, result, chunks) = streamed(sse(created, completedEvent(done)))
      chunks.flatMap(_.toolCall) shouldBe List(ToolCall("call_1", "get_weather", ujson.Obj("city" -> "Oslo")))
      chunks.last.finishReason shouldBe Some("tool_calls")
      result.map(_.toolCalls.map(_.id)) shouldBe Right(List("call_1"))
    }

    "end an incomplete response with finish reason length" in {
      val (_, result, chunks) =
        streamed(sse(delta("cut"), completedEvent(response(s"[${textMessage("cut")}]", status = "incomplete"))))
      result.map(_.content) shouldBe Right("cut")
      chunks.last.finishReason shouldBe Some("length")
    }

    "ignore blank data, [DONE], comments and anything after response.completed" in {
      val body =
        ": keep-alive\n\ndata:\n\ndata: [DONE]\n\n" + sseBody(
          delta("a"),
          completedEvent(response(s"[${textMessage("a")}]"))
        ) +
          sseBody(delta("late"))
      val (_, result, chunks) =
        streamed(Right(StreamingHttpResponse(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))))
      result.map(_.content) shouldBe Right("a")
      chunks.flatMap(_.content) shouldBe List("a")
    }

    "fail on response.failed with Perplexity's error, from the event or its response" in {
      val (_, onEvent, _) =
        streamed(
          sse(
            delta("x"),
            """{"type":"response.failed","sequence_number":4,"error":{"code":"overloaded","message":"try later"}}"""
          )
        )
      onEvent.left.toOption.get shouldBe a[ServiceError]
      onEvent.left.toOption.get.message should include("overloaded: try later")
      val (_, onResponse, _) =
        streamed(sse("""{"type":"response.failed","response":{"status":"failed","error":{"message":"inner"}}}"""))
      onResponse.left.toOption.get.message should include("inner")
    }

    "fail a stream that ends without response.completed, or whose completed response failed" in {
      val (_, cut, _) = streamed(sse(created, delta("half")))
      cut.left.toOption.get.message should include("without response.completed")
      val (_, failed, _) =
        streamed(sse(completedEvent("""{"status":"failed","output":[],"error":{"message":"bad"}}""")))
      failed.left.toOption.get.message should include("bad")
    }

    "fail on an event whose data is not JSON" in {
      val (_, result, _) =
        streamed(
          Right(
            StreamingHttpResponse(200, new ByteArrayInputStream("data: {oops\n\n".getBytes(StandardCharsets.UTF_8)))
          )
        )
      result.left.toOption.get.message should include("not JSON")
    }

    "map a read failure to an error" in {
      val broken = new InputStream {
        def read(): Int = throw new IOException("connection reset")
      }
      val (_, result, _) = streamed(Right(StreamingHttpResponse(200, broken)))
      result.isLeft shouldBe true
    }

    "map an HTTP error status to a typed error with Perplexity's message" in {
      val (_, result, _) = streamed(
        Right(
          StreamingHttpResponse(
            401,
            new ByteArrayInputStream("""{"error":{"message":"invalid key"}}""".getBytes(StandardCharsets.UTF_8))
          )
        )
      )
      result.left.toOption.get shouldBe an[AuthenticationError]
    }
  }
