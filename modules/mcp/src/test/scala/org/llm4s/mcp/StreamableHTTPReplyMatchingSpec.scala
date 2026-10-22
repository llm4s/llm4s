package org.llm4s.mcp

import com.sun.net.httpserver.HttpExchange
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration._
import scala.util.Using

/**
 * A Streamable HTTP SSE reply is matched to its request, and a reply is bounded in size (#1846).
 *
 * A server may interleave other messages with the reply on the same stream: progress notifications, its own
 * requests to the client, and - from a misbehaving or multiplexing server - a response to another id. Only
 * the response whose `id` is the request's is this call's reply.
 */
class StreamableHTTPReplyMatchingSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val request = JsonRpcRequest(jsonrpc = "2.0", id = "7", method = "tools/list", params = None)

  private def event(json: String): String = s"event: message\ndata: $json\n\n"

  private val serverRequest =
    event("""{"jsonrpc":"2.0","id":"7","method":"sampling/createMessage","params":{"messages":[]}}""")
  private val otherIdReply = event("""{"jsonrpc":"2.0","id":"6","result":{"tools":[{"name":"wrong"}]}}""")
  private val progress     = event("""{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""")
  private val realReply    = event("""{"jsonrpc":"2.0","id":"7","result":{"tools":[{"name":"right"}]}}""")

  private def sendSse(body: String)(exchange: HttpExchange): Unit =
    LocalProviderTestServer.sendSseResponse(exchange, body)

  private def call(body: String) = {
    var result: Option[Either[String, JsonRpcResponse]] = None
    LocalProviderTestServer.withServer("/mcp")(sendSse(body)) { url =>
      result = Some(
        new StreamableHTTPTransportImpl(s"$url/mcp", "matching", 5.seconds).sendRequest(request).left.map(_.message)
      )
    }
    result.getOrElse(fail("the server never ran the call"))
  }

  "StreamableHTTPTransportImpl" should "skip a server request and a response to another id, and return the reply to its own id" in {
    val reply = call(serverRequest + otherIdReply + progress + realReply).value

    reply.id shouldBe "7"
    reply.result.map(_("tools")(0)("name").str) shouldBe Some("right")
  }

  it should "fail, not return another call's reply, when the stream holds no response to its id" in {
    val error = call(serverRequest + otherIdReply + progress).left.value

    error should include("No valid JSON-RPC response found in SSE stream")
  }

  it should "return a JSON-RPC error addressed to its id as the call's error" in {
    val error =
      call(
        otherIdReply + event("""{"jsonrpc":"2.0","id":"7","error":{"code":-32601,"message":"no such method"}}""")
      ).left.value

    error should include("JSON-RPC Error -32601")
  }

  it should "refuse an SSE event larger than the reply bound, rather than buffer it" in {
    val chunk = "x" * (1024 * 1024)
    val huge  = "event: message\n" + Seq.fill(11)(s"data: $chunk\n").mkString + "\n"
    val error = call(huge + realReply).left.value

    error should include("reply exceeds")
  }

  "JsonRpcReplies.isResponseTo" should "accept only a response object with the request's id" in {
    def isReply(json: String) = JsonRpcReplies.isResponseTo("7", ujson.read(json))

    isReply("""{"jsonrpc":"2.0","id":"7","result":{}}""") shouldBe true
    isReply("""{"jsonrpc":"2.0","id":"7","error":{"code":1,"message":"m"}}""") shouldBe true

    isReply("""{"jsonrpc":"2.0","id":"6","result":{}}""") shouldBe false              // another call's reply
    isReply("""{"jsonrpc":"2.0","id":"7","method":"ping"}""") shouldBe false          // a server-to-client request
    isReply("""{"jsonrpc":"2.0","id":"7","method":"x","result":{}}""") shouldBe false // has a method: not a response
    isReply("""{"jsonrpc":"2.0","method":"notifications/progress"}""") shouldBe false // a notification
    isReply("""{"jsonrpc":"2.0","id":7,"result":{}}""") shouldBe false                // a number is not the id "7"
    isReply("""[{"jsonrpc":"2.0","id":"7","result":{}}]""") shouldBe false            // not an object
  }

  "BoundedResponseBody" should "return a whole body within its bound, and refuse one past it" in {
    def body(text: String, max: Int) =
      new BoundedResponseBody(
        new ByteArrayInputStream(text.getBytes(UTF_8)),
        Deadline.now + 5.seconds,
        5.seconds,
        "http://h/mcp",
        max
      )

    Using.resource(body("0123456789", 10))(_.readAll().value shouldBe "0123456789")
    Using.resource(body("0123456789A", 10))(
      _.readAll().left.value.message should include("reply exceeds 10 characters")
    )
  }

  it should "read lines ended by LF, CR or CRLF, and refuse a line past its bound" in {
    def body(text: String) =
      new BoundedResponseBody(
        new ByteArrayInputStream(text.getBytes(UTF_8)),
        Deadline.now + 5.seconds,
        5.seconds,
        "http://h",
        4
      )

    Using.resource(body("a\nbb\r\nccc\rdddd")) { lines =>
      Iterator.continually(lines.readLine().value).takeWhile(_.isDefined).flatten.toList shouldBe
        List("a", "bb", "ccc", "dddd")
      lines.readLine().value shouldBe None
    }
    Using.resource(body("abcde\n"))(_.readLine().left.value.message should include("reply exceeds 4 characters"))
  }
}
