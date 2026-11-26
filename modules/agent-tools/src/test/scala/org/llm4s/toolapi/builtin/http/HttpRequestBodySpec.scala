package org.llm4s.toolapi.builtin.http

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * A request body goes only with a method that takes one (#3048). `HttpURLConnection` sends a request whose body is
 * written as a `POST`, whatever method was set, so a `GET` with a body reached the server as a write under the
 * read-only `GET`/`HEAD` configuration while `HTTPResult.method` still said `GET`. A loopback server records what
 * actually arrives.
 */
class HttpRequestBodySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  final private case class Seen(method: String, contentType: String, body: String)

  private val seen               = new ConcurrentLinkedQueue[Seen]()
  private var server: HttpServer = _
  private def url                = s"http://127.0.0.1:${server.getAddress.getPort}/echo"

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/echo",
      (ex: HttpExchange) => {
        val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        seen.add(
          Seen(ex.getRequestMethod, Option(ex.getRequestHeaders.getFirst("Content-Type")).getOrElse(""), body)
        )
        ex.sendResponseHeaders(200, -1)
        ex.close()
      }
    )
    server.start()
  }

  override def afterAll(): Unit = if (server != null) server.stop(0)

  private val loopback = HttpConfig(blockedDomains = Seq.empty, blockInternalIPs = false)

  private def call(config: HttpConfig, args: (String, ujson.Value)*): Either[String, HTTPResult] =
    HTTPTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool =>
          tool.handler(SafeParameterExtractor(ujson.Obj.from(("url" -> ujson.Str(url)) +: args))).left.map(_.message)
      )

  "HTTPTool" should "refuse a body on a GET in the read-only configuration, sending nothing" in {
    seen.clear()
    val readOnly = loopback.copy(allowedMethods = HttpConfig().allowedMethods)
    for (method <- Seq("GET", "get", "HEAD")) {
      val result = call(readOnly, "method" -> method, "body" -> """{"delete":"everything"}""")
      result.left.toOption.getOrElse(fail(s"$method with a body was sent: $result")) should include(
        "must not carry a request body"
      )
    }
    seen.asScala shouldBe empty
  }

  it should "refuse a body, even an empty one, on every method that takes none, with every method allowed" in {
    seen.clear()
    for (method <- Seq("GET", "HEAD", "OPTIONS"))
      call(loopback.withAllMethods, "method" -> method, "body" -> "").isLeft shouldBe true
    seen.asScala shouldBe empty
  }

  it should "send a body with POST, PUT and DELETE under the method declared, as JSON unless told otherwise" in {
    for (method <- Seq("POST", "PUT", "DELETE")) {
      seen.clear()
      val result = call(loopback.withAllMethods, "method" -> method, "body" -> """{"id":1}""")
      result.map(_.method) shouldBe Right(method)
      seen.asScala.toList shouldBe List(Seen(method, "application/json", """{"id":1}"""))
    }
  }

  it should "send the content_type given with a body" in {
    seen.clear()
    call(
      loopback.withAllMethods,
      "method"       -> "DELETE",
      "body"         -> "a=1",
      "content_type" -> "text/plain"
    ).isRight shouldBe
      true
    seen.asScala.toList shouldBe List(Seen("DELETE", "text/plain", "a=1"))
  }

  it should "keep a Content-Type header the caller set, rather than replace it with the JSON default" in {
    for (method <- Seq("POST", "DELETE")) {
      seen.clear()
      val headers = ujson.Obj("content-type" -> "text/plain")
      call(loopback.withAllMethods, "method" -> method, "body" -> "a=1", "headers" -> headers).isRight shouldBe true
      seen.asScala.toList shouldBe List(Seen(method, "text/plain", "a=1"))
    }
  }

  it should "send a GET without a body as a GET" in {
    seen.clear()
    call(loopback, "method" -> "GET").map(_.method) shouldBe Right("GET")
    seen.asScala.toList shouldBe List(Seen("GET", "", ""))
  }
}
