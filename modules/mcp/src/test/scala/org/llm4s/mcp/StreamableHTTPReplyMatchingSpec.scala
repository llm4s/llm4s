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

  it should "answer a ping and refuse another request the server sends in the reply stream (#3079)" in {
    val posted                                          = new java.util.concurrent.ConcurrentLinkedQueue[ujson.Value]()
    val ping                                            = event("""{"jsonrpc":"2.0","id":"7","method":"ping"}""")
    val roots                                           = event("""{"jsonrpc":"2.0","id":41,"method":"roots/list"}""")
    var result: Option[Either[String, JsonRpcResponse]] = None
    LocalProviderTestServer.withServer("/mcp") { exchange =>
      val body = ujson.read(new String(exchange.getRequestBody.readAllBytes(), UTF_8))
      if (body.obj.contains("method")) sendSse(ping + roots + realReply)(exchange)
      else {
        posted.add(body): Unit
        LocalProviderTestServer.sendJsonResponse(exchange, 202, "")
      }
    } { url =>
      result = Some(
        new StreamableHTTPTransportImpl(s"$url/mcp", "answering", 5.seconds).sendRequest(request).left.map(_.message)
      )
      // The answers are posted off the reader thread: wait for both before the server goes
      val deadline = Deadline.now + 5.seconds
      while (posted.size < 2 && deadline.hasTimeLeft()) Thread.sleep(10)
    }

    result.flatMap(_.toOption).map(_.id) shouldBe Some("7")
    val answers = posted.toArray.toSeq.collect { case v: ujson.Value => v }
    answers.map(_("id")) should contain theSameElementsAs Seq(ujson.Str("7"), ujson.Num(41))
    answers.find(_("id") == ujson.Str("7")).map(_("result")) shouldBe Some(ujson.Obj())
    answers.find(_("id") == ujson.Num(41)).map(_("error")("code").num.toInt) shouldBe Some(-32601)
  }

  it should "read on past a server request whose answer the server is slow to acknowledge" in {
    val ping                                            = event("""{"jsonrpc":"2.0","id":"7","method":"ping"}""")
    val release                                         = new java.util.concurrent.CountDownLatch(1)
    val answered                                        = new java.util.concurrent.CountDownLatch(1)
    var result: Option[Either[String, JsonRpcResponse]] = None
    LocalProviderTestServer.withServer("/mcp") { exchange =>
      val body = ujson.read(new String(exchange.getRequestBody.readAllBytes(), UTF_8))
      if (body.obj.contains("method")) sendSse(ping + realReply)(exchange)
      else {
        // The answer to the ping: held open until the test ends
        answered.countDown()
        release.await(30, java.util.concurrent.TimeUnit.SECONDS): Unit
        LocalProviderTestServer.sendJsonResponse(exchange, 202, "")
      }
    } { url =>
      try {
        val started = System.nanoTime()
        result = Some(
          new StreamableHTTPTransportImpl(s"$url/mcp", "slow-ack", 5.seconds).sendRequest(request).left.map(_.message)
        )
        (System.nanoTime() - started).nanos should be < 5.seconds
        answered.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      } finally release.countDown()
    }

    result.flatMap(_.toOption).map(_.id) shouldBe Some("7")
  }

  it should "include a JSON-RPC error's data in the error (#3079)" in {
    val error = call(
      event(
        """{"jsonrpc":"2.0","id":"7","error":{"code":-32602,"message":"Invalid params","data":"path must be absolute"}}"""
      )
    ).left.value

    error shouldBe "JSON-RPC Error -32602: Invalid params (data: path must be absolute)"
  }

  "JsonRpcReplies.serverRequest" should "take only an object with a method and an id for a server's request" in {
    def req(json: String) = JsonRpcReplies.serverRequest(ujson.read(json))

    req("""{"jsonrpc":"2.0","id":"a","method":"ping"}""") shouldBe Some(ujson.Str("a") -> "ping")
    req("""{"jsonrpc":"2.0","id":3,"method":"roots/list"}""") shouldBe Some(ujson.Num(3) -> "roots/list")
    req("""{"jsonrpc":"2.0","method":"notifications/progress"}""") shouldBe None // a notification
    req("""{"jsonrpc":"2.0","id":"a","result":{}}""") shouldBe None              // a response
  }

  "JsonRpcReplies.errorMessage" should "show an object's data as JSON, and nothing for null data" in {
    JsonRpcReplies.errorMessage(JsonRpcError(1, "m", Some(ujson.Obj("field" -> "x")))) shouldBe
      """JSON-RPC Error 1: m (data: {"field":"x"})"""
    JsonRpcReplies.errorMessage(JsonRpcError(1, "m", Some(ujson.Null))) shouldBe "JSON-RPC Error 1: m"
    JsonRpcReplies.errorMessage(JsonRpcError(1, "m", None)) shouldBe "JSON-RPC Error 1: m"
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
