package org.llm4s.portable.demo

import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.scalajs.js

/**
 * The browser path over Node's own fetch API: `globalThis.fetch` is replaced by one answering from memory, with the
 * body split into byte chunks that cut through events and through a multi-byte character, as a network would.
 */
class FetchStreamingSpec extends AsyncFlatSpec with Matchers {
  implicit override def executionContext: ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue

  private val sse =
    Seq(
      """data: {"id":"c1","model":"llama3","choices":[{"index":0,"delta":{"content":"Bonjour, "}}]}""",
      """data: {"id":"c1","choices":[{"index":0,"delta":{"content":"été!"},"finish_reason":"stop"}]}""",
      """data: {"id":"c1","choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}""",
      "data: [DONE]"
    ).mkString("", "\n\n", "\n\n")

  private def stubFetch(status: Int, body: String, pieceBytes: Int): js.Dynamic => Unit = { requests =>
    val bytes = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(body)
    val fetch: js.Function2[String, js.Dynamic, js.Any] = (url, init) => {
      requests.push(js.Dynamic.literal(url = url, body = init.body))
      val stream = js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(js.Dynamic.literal(start = {
        (c: js.Dynamic) =>
          val length = bytes.length.asInstanceOf[Int]
          (0 until length by pieceBytes).foreach(i => c.enqueue(bytes.slice(i, math.min(i + pieceBytes, length))))
          c.close()
      }: js.Function1[js.Dynamic, Unit]))
      js.Promise.resolve[js.Any](
        js.Dynamic.newInstance(js.Dynamic.global.Response)(stream, js.Dynamic.literal(status = status))
      )
    }
    js.Dynamic.global.fetch = fetch
  }

  private val conversation = Conversation(Seq(UserMessage("Say hello")))

  "FetchStreaming" should "deliver chunks as the body arrives and assemble the completion" in {
    val requests = js.Array[js.Dynamic]()
    stubFetch(200, sse, pieceBytes = 5)(requests.asInstanceOf[js.Dynamic])
    val chunks = js.Array[StreamedChunk]()
    FetchStreaming
      .streamComplete("http://localhost:11434/v1/", "llama3", conversation, CompletionOptions())(c => chunks.push(c))
      .map { result =>
        requests.head.url.asInstanceOf[String] shouldBe "http://localhost:11434/v1/chat/completions"
        requests.head.body.asInstanceOf[String] should include("\"stream\":true")
        chunks.toSeq.flatMap(_.content) shouldBe Seq("Bonjour, ", "été!")
        result.map(_.content) shouldBe Right("Bonjour, été!")
        result.toOption.flatMap(_.usage).map(_.totalTokens) shouldBe Some(5)
      }
  }

  it should "turn an error status into core's error" in {
    stubFetch(500, """{"error":{"message":"model not loaded"}}""", pieceBytes = 1000)(
      js.Array[js.Dynamic]().asInstanceOf[js.Dynamic]
    )
    FetchStreaming.streamComplete("http://h/v1", "m", conversation, CompletionOptions())(_ => ()).map {
      case Left(e: ServiceError) => e.message should include("model not loaded")
      case other                 => fail(s"expected a ServiceError, got $other")
    }
  }
}
