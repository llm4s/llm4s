package org.llm4s.toolapi.builtin.http

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ InetSocketAddress, URI, URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets

/**
 * Sensitive headers stay stripped once a redirect leaves the original origin (issue #1408, finding F7). The tool used
 * to compare each hop with the previous one by host alone and forward the original headers, so `127.0.0.1` ->
 * `localhost` -> `localhost` sent `Authorization` again on the third hop, and a hop to another port on the same host
 * kept it. Two in-process servers on loopback give two origins that differ only by port. Since #1734 a cross-origin
 * hop also drops every other caller-set header not on `HttpConfig.redirectSafeHeaders`, such as `X-Custom` here
 * (`HttpRedirectAllowlistSpec` covers that rule).
 */
class HttpRedirectHeaderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var serverA: HttpServer = _
  private var serverB: HttpServer = _

  private def portA = serverA.getAddress.getPort
  private def portB = serverB.getAddress.getPort

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  /** A URL on `base` that redirects (302) to `target`. */
  private def via(base: String, target: String): String = s"$base/to?u=${enc(target)}"

  private def start(): HttpServer = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/to",
      (ex: HttpExchange) => {
        val target = Option(ex.getRequestURI.getRawQuery)
          .flatMap(_.split('&').find(_.startsWith("u=")))
          .map(q => URLDecoder.decode(q.drop(2), StandardCharsets.UTF_8))
          .getOrElse("/echo")
        ex.getResponseHeaders.set("Location", target)
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )
    server.createContext(
      "/echo",
      (ex: HttpExchange) => {
        def h(name: String) = Option(ex.getRequestHeaders.getFirst(name)).getOrElse("")
        val body =
          ujson
            .Obj(
              "auth"      -> h("Authorization"),
              "cookie"    -> h("Cookie"),
              "proxyAuth" -> h("Proxy-Authorization"),
              "custom"    -> h("X-Custom")
            )
            .render()
            .getBytes(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(200, body.length.toLong)
        ex.getResponseBody.write(body)
        ex.close()
      }
    )
    server.start()
    server
  }

  override def beforeAll(): Unit = {
    serverA = start()
    serverB = start()
  }

  override def afterAll(): Unit = {
    if (serverA != null) serverA.stop(0)
    if (serverB != null) serverB.stop(0)
  }

  private val config = HttpConfig(
    blockedDomains = Seq.empty,
    blockInternalIPs = false,
    followRedirects = true,
    maxRedirects = 5
  )

  private val sensitive = Map(
    "Authorization"       -> "Bearer MODEL-SUPPLIED-TOKEN",
    "Cookie"              -> "session=abc",
    "Proxy-Authorization" -> "Basic cHJveHk6cHc=",
    "X-Custom"            -> "keep-me"
  )

  /** The headers the final `/echo` hop received. */
  private def echoed(url: String): Map[String, String] = {
    val params =
      ujson.Obj("url" -> url, "headers" -> ujson.Obj.from(sensitive.map { case (k, v) => k -> ujson.Str(v) }))
    val result =
      HTTPTool.createSafe(config).fold(e => fail(e.formatted), identity).handler(SafeParameterExtractor(params))
    val body = result.fold(e => fail(e), _.body)
    ujson.read(body).obj.map { case (k, v) => k -> v.str }.toMap
  }

  private def strippedAll(received: Map[String, String]): Unit = {
    received("auth") shouldBe ""
    received("cookie") shouldBe ""
    received("proxyAuth") shouldBe ""
    received("custom") shouldBe "" // not on the redirect allowlist (#1734)
  }

  "HTTPTool redirect header stripping" should
    "keep sensitive headers stripped on every hop after leaving the origin (127.0.0.1 -> localhost -> localhost)" in {
      val a = s"http://127.0.0.1:$portA"
      val l = s"http://localhost:$portA"
      strippedAll(echoed(via(a, via(l, s"$l/echo")))) // the issue's 3-hop reproduction
    }

  it should "keep them stripped when a later hop comes back to the original origin" in {
    val a = s"http://127.0.0.1:$portA"
    val l = s"http://localhost:$portA"
    strippedAll(echoed(via(a, via(l, s"$a/echo"))))
  }

  it should "strip them on a hop to another port of the same host" in {
    strippedAll(echoed(via(s"http://127.0.0.1:$portA", s"http://127.0.0.1:$portB/echo")))
  }

  it should "keep them stripped after a port change returns to the original port" in {
    val a = s"http://127.0.0.1:$portA"
    val b = s"http://127.0.0.1:$portB"
    strippedAll(echoed(via(a, via(b, s"$a/echo"))))
  }

  it should "send them on every hop of a redirect chain that stays on the original origin" in {
    val a        = s"http://127.0.0.1:$portA"
    val received = echoed(via(a, via(a, s"$a/echo")))
    received("auth") shouldBe "Bearer MODEL-SUPPLIED-TOKEN"
    received("cookie") shouldBe "session=abc"
    // Proxy-Authorization is not asserted here: HttpURLConnection itself never sends it to an origin server.
    received("custom") shouldBe "keep-me"
  }

  it should "treat a relative Location as the same origin" in {
    val a = s"http://127.0.0.1:$portA"
    echoed(via(a, "/echo"))("auth") shouldBe "Bearer MODEL-SUPPLIED-TOKEN"
  }

  // ---- the origin rule on its own, for the cases a plain-HTTP loopback server cannot show

  private def origin(url: String) = HTTPTool.Origin.of(URI.create(url).toURL)

  "HTTPTool.Origin" should "compare scheme, host and port, filling in the scheme's default port" in {
    origin("http://api.example.com/x") shouldBe origin("http://api.example.com:80/y")
    origin("https://api.example.com/x") shouldBe origin("https://API.example.com:443/y")
    origin("https://api.example.com/") should not be origin("http://api.example.com/")
    origin("http://api.example.com/") should not be origin("http://api.example.com:8080/")
    origin("http://api.example.com/") should not be origin("http://other.example.com/")
  }

  "HTTPTool.headersForHop" should "strip on a downgrade from https to http on the same host and port number" in {
    val headers = Some(sensitive)
    val (sent, sticky) = HTTPTool.headersForHop(
      headers,
      origin("https://h.example:8443/"),
      origin("http://h.example:8443/"),
      alreadyStripped = false
    )
    sent.get.keySet shouldBe empty
    sticky shouldBe true
  }

  it should "strip on an upgrade from http to https, which is a different origin too" in {
    val (sent, _) = HTTPTool.headersForHop(
      Some(sensitive),
      origin("http://h.example/"),
      origin("https://h.example/"),
      alreadyStripped = false
    )
    sent.get.keySet shouldBe empty
  }

  it should "keep stripping on a same-origin hop once stripping has started, and match header names in any case" in {
    val headers   = Some(Map("authorization" -> "x", "COOKIE" -> "y", "proxy-AUTHORIZATION" -> "z", "Accept" -> "a"))
    val o         = origin("https://h.example/")
    val (sent, s) = HTTPTool.headersForHop(headers, o, o, alreadyStripped = true)
    sent shouldBe Some(Map("Accept" -> "a"))
    s shouldBe true
  }

  it should "leave headers alone on a same-origin hop before any cross-origin hop" in {
    val o = origin("https://h.example/")
    HTTPTool.headersForHop(Some(sensitive), o, o, alreadyStripped = false) shouldBe ((Some(sensitive), false))
  }
}
