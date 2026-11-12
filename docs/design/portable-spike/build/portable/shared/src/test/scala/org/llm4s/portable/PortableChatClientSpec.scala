package org.llm4s.portable

import org.llm4s.error.{ NetworkError, ServiceError }
import org.llm4s.llmconnect.model.StreamedChunk
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client4.testing.SyncBackendStub
import sttp.model.{ Method, StatusCode }

import java.io.IOException

/** The client over sttp's in-memory stub backend: no network, and the same on all three platforms. */
class PortableChatClientSpec extends AnyFlatSpec with Matchers {
  import Fixtures._

  private val reply =
    """{"id":"c1","created":1700000000,"model":"llama3","choices":[{"index":0,"message":{"role":"assistant","content":"Sunny."}}]}"""

  "complete" should "POST the body to {baseUrl}/chat/completions with the key as a bearer token" in {
    val backend = SyncBackendStub
      .whenRequestMatches { r =>
        r.method == Method.POST &&
        r.uri.toString == "http://localhost:11434/v1/chat/completions" &&
        r.header("Authorization").contains("Bearer test-key") &&
        r.body.toString.contains("\"model\":\"llama3\"")
      }
      .thenRespondAdjust(reply)
    val client = new PortableChatClient(backend, "http://localhost:11434/v1/", "llama3", apiKey = Some("test-key"))
    client.complete(textConversation).map(_.content) shouldBe Right("Sunny.")
  }

  it should "send no Authorization header without a key" in {
    val backend = SyncBackendStub
      .whenRequestMatches(_.header("Authorization").isEmpty)
      .thenRespondAdjust(reply)
    new PortableChatClient(backend, "http://localhost:11434/v1", "llama3")
      .complete(textConversation)
      .isRight shouldBe true
  }

  it should "map an error status and a transport failure to core's errors" in {
    val failing = SyncBackendStub.whenAnyRequest.thenRespondAdjust("""{"error":{"message":"busy"}}""", StatusCode(503))
    new PortableChatClient(failing, "http://h/v1", "m").complete(textConversation) match {
      case Left(e: ServiceError) => e.httpStatus shouldBe 503
      case other                 => fail(s"expected a ServiceError, got $other")
    }
    val down = SyncBackendStub.whenAnyRequest.thenThrow(new IOException("connection refused"))
    new PortableChatClient(down, "http://h/v1", "m").complete(textConversation) match {
      case Left(e: NetworkError) => e.message should include("connection refused")
      case other                 => fail(s"expected a NetworkError, got $other")
    }
  }

  "streamComplete" should "deliver the chunks and the assembled completion" in {
    val sse = Seq(
      """data: {"id":"c1","choices":[{"index":0,"delta":{"content":"Sun"}}]}""",
      """data: {"id":"c1","choices":[{"index":0,"delta":{"content":"ny."},"finish_reason":"stop"}]}""",
      "data: [DONE]"
    ).mkString("", "\n\n", "\n\n")
    val backend = SyncBackendStub
      .whenRequestMatches(_.body.toString.contains("\"stream\":true"))
      .thenRespondAdjust(sse)
    val chunks = Vector.newBuilder[StreamedChunk]
    val result = new PortableChatClient(backend, "http://h/v1", "m").streamComplete(textConversation)(chunks += _)
    chunks.result().flatMap(_.content) shouldBe Vector("Sun", "ny.")
    result.map(_.content) shouldBe Right("Sunny.")
  }
}
