package org.llm4s.toolapi.builtin.http

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ InetSocketAddress, URI, URLDecoder, URLEncoder }
import java.nio.charset.StandardCharsets
import java.util.Locale
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * A redirect to another origin forwards only the headers on `HttpConfig.redirectSafeHeaders` (issue #1734). The tool
 * used to strip only `Authorization`, `Cookie` and `Proxy-Authorization`, so `X-Api-Key`, `Api-Key`, `X-Auth-Token`
 * and every other credential header a model put in a call reached whatever origin the redirect named. Two in-process
 * servers on loopback give two origins that differ only by port.
 */
class HttpRedirectAllowlistSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var serverA: HttpServer = _
  private var serverB: HttpServer = _

  private def portA = serverA.getAddress.getPort
  private def portB = serverB.getAddress.getPort

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  /** A URL on `base` that redirects with `status` to `target`. */
  private def via(base: String, target: String, status: Int = 302): String =
    s"$base/to?s=$status&u=${enc(target)}"

  private def start(): HttpServer = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/to",
      (ex: HttpExchange) => {
        val query = Option(ex.getRequestURI.getRawQuery).toSeq.flatMap(_.split('&'))
        def param(name: String) =
          query
            .find(_.startsWith(s"$name="))
            .map(q => URLDecoder.decode(q.drop(name.length + 1), StandardCharsets.UTF_8))
        ex.getRequestBody.readAllBytes()
        ex.getResponseHeaders.set("Location", param("u").getOrElse("/echo"))
        ex.sendResponseHeaders(param("s").map(_.toInt).getOrElse(302), -1)
        ex.close()
      }
    )
    server.createContext(
      "/echo",
      (ex: HttpExchange) => {
        // Every request header, lower-cased, so the spec sees exactly what reached this origin.
        val headers = ex.getRequestHeaders.asScala.map { case (k, v) =>
          k.toLowerCase(java.util.Locale.ROOT) -> ujson.Str(v.asScala.mkString(","))
        }
        val body = ujson
          .Obj("headers" -> ujson.Obj.from(headers), "body" -> new String(ex.getRequestBody.readAllBytes(), "UTF-8"))
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

  /** The credential headers the issue names, and others a model is likely to send. */
  private val credentials = Map(
    "X-Api-Key"            -> "k1",
    "Api-Key"              -> "k2",
    "X-Auth-Token"         -> "k3",
    "X-Goog-Api-Key"       -> "k4",
    "X-Amz-Security-Token" -> "k5",
    "X-Access-Token"       -> "k6",
    "X-Session-Token"      -> "k7",
    "X-Client-Secret"      -> "k8",
    "Private-Token"        -> "k9",
    "Authorization"        -> "Bearer k10",
    "Cookie"               -> "session=k11",
    "X-Custom"             -> "not-a-credential-but-not-allowlisted"
  )

  private val safe = Map(
    "Accept"          -> "application/json",
    "Accept-Language" -> "en-GB",
    "User-Agent"      -> "agent-supplied/1.0"
  )

  private val all = credentials ++ safe ++ Map("Content-Type" -> "application/json")

  /** What the final `/echo` hop received: its lower-cased headers and its body. */
  private def echoed(
    url: String,
    headers: Map[String, String],
    cfg: HttpConfig = config,
    method: String = "GET",
    body: Option[String] = None
  ): (Map[String, String], String) = {
    val params = ujson.Obj(
      "url"     -> url,
      "method"  -> method,
      "headers" -> ujson.Obj.from(headers.map { case (k, v) => k -> ujson.Str(v) })
    )
    body.foreach(b => params("body") = b)
    val result =
      HTTPTool.createSafe(cfg).fold(e => fail(e.formatted), identity).handler(SafeParameterExtractor(params))
    val json = ujson.read(result.fold(e => fail(e), _.body))
    (json("headers").obj.map { case (k, v) => k -> v.str }.toMap, json("body").str)
  }

  private def originA = s"http://127.0.0.1:$portA"
  private def originB = s"http://127.0.0.1:$portB"

  "HTTPTool on a cross-origin redirect" should "strip every credential header the caller set" in {
    val (received, _) = echoed(via(originA, s"$originB/echo"), credentials ++ safe)
    val leaked        = credentials.keySet.map(_.toLowerCase(java.util.Locale.ROOT)).intersect(received.keySet)
    leaked shouldBe empty
  }

  it should "forward the safe headers on the default allowlist" in {
    val (received, _) = echoed(via(originA, s"$originB/echo"), credentials ++ safe)
    received.get("accept") shouldBe Some("application/json")
    received.get("accept-language") shouldBe Some("en-GB")
    received.get("user-agent") shouldBe Some("agent-supplied/1.0")
  }

  "HTTPTool on a same-origin redirect" should "forward every header the caller set" in {
    val (received, _) = echoed(via(originA, s"$originA/echo"), credentials ++ safe)
    for ((name, value) <- credentials ++ safe - "Cookie" - "Authorization")
      withClue(name)(received.get(name.toLowerCase(java.util.Locale.ROOT)) shouldBe Some(value))
    received.get("authorization") shouldBe Some("Bearer k10")
    received.get("cookie") shouldBe Some("session=k11")
  }

  it should "forward exactly the headers a configured allowlist names, in any case" in {
    val cfg           = config.copy(redirectSafeHeaders = Seq("x-custom", "ACCEPT"))
    val (received, _) = echoed(via(originA, s"$originB/echo"), credentials ++ safe, cfg)
    received.get("x-custom") shouldBe Some("not-a-credential-but-not-allowlisted")
    received.get("accept") shouldBe Some("application/json")
    received.get("accept-language") shouldBe None
    received.get("user-agent") shouldBe Some(cfg.userAgent) // the tool's own, not the caller's
  }

  it should "forward no caller-set header with an empty allowlist" in {
    val cfg           = config.copy(redirectSafeHeaders = Seq.empty)
    val (received, _) = echoed(via(originA, s"$originB/echo"), credentials ++ safe, cfg)
    credentials.keySet.map(_.toLowerCase(Locale.ROOT)).intersect(received.keySet) shouldBe empty
    received.get("accept") should not be Some("application/json") // HttpURLConnection's own default, if any
    received.get("accept-language") shouldBe None
    received.get("user-agent") shouldBe Some(cfg.userAgent)
  }

  it should "never forward a credential header, even one the allowlist names" in {
    val cfg = config.copy(redirectSafeHeaders = credentials.keys.toSeq ++ Seq("Proxy-Authorization", "Cookie2"))
    val (received, _) = echoed(via(originA, s"$originB/echo"), credentials, cfg)
    received.keySet.intersect(credentials.keySet.map(_.toLowerCase(Locale.ROOT))) shouldBe Set("x-custom")
  }

  it should "forward Content-Type with a body a 307 re-sends, and drop it when a 302 drops the body" in {
    val cfg     = config.withAllMethods
    val headers = Map("Content-Type" -> "text/plain", "X-Api-Key" -> "k1")
    val (kept, keptBody) =
      echoed(via(originA, s"$originB/echo", 307), headers, cfg, method = "DELETE", body = Some("payload"))
    kept.get("content-type") shouldBe Some("text/plain")
    kept.get("x-api-key") shouldBe None
    keptBody shouldBe "payload"
    val (dropped, droppedBody) =
      echoed(via(originA, s"$originB/echo", 302), headers, cfg, method = "DELETE", body = Some("payload"))
    dropped.get("content-type") shouldBe None
    droppedBody shouldBe ""
  }

  it should "keep stripping when a later hop returns to the original origin" in {
    val (received, _) = echoed(via(originA, via(originB, s"$originA/echo")), credentials ++ safe)
    credentials.keySet.map(_.toLowerCase(Locale.ROOT)).intersect(received.keySet) shouldBe empty
    received.get("accept") shouldBe Some("application/json")
  }

  // ---- the rule on its own, for the cases a plain-HTTP loopback server cannot show

  private def origin(url: String) = HTTPTool.Origin.of(URI.create(url).toURL)

  "HTTPTool.headersForHop" should "strip every non-allowlisted header on a downgrade from https to http" in {
    val (sent, sticky) =
      HTTPTool.headersForHop(Some(all), origin("https://h.example/"), origin("http://h.example:443/"), false)
    sent.get.keySet shouldBe safe.keySet
    sticky shouldBe true
  }

  it should "send everything on a same-origin hop, and count a default port as the same origin" in {
    HTTPTool.headersForHop(Some(all), origin("https://h.example/"), origin("https://H.example:443/x"), false) shouldBe
      ((Some(all), false))
  }

  it should "send Content-Type on a cross-origin hop only with a body" in {
    val (a, b) = (origin("https://a.example/"), origin("https://b.example/"))
    HTTPTool.headersForHop(Some(all), a, b, false, sendsBody = true)._1.get.keySet shouldBe
      safe.keySet + "Content-Type"
    HTTPTool.headersForHop(Some(all), a, b, false, sendsBody = false)._1.get.keySet shouldBe safe.keySet
  }

  it should "withhold any header core's redaction calls sensitive, whatever the allowlist says" in {
    val names = Seq(
      "X-Goog-Api-Key",
      "X_API_KEY",
      "x-refresh-token",
      "X-Db-Password",
      "X-Client-Secret",
      "Token",
      "X-Amz-Security-Token",
      "Private-Token",
      "X-CSRF-Token",
      "Ocp-Apim-Subscription-Key",
      "Cookie2",
      "proxy-authorization"
    )
    val (sent, _) = HTTPTool.headersForHop(
      Some(names.map(_ -> "v").toMap),
      origin("https://a.example/"),
      origin("https://b.example/"),
      false,
      safe = names
    )
    sent shouldBe Some(Map.empty)
  }

  // ---- locale independence: under tr-TR the default-locale "I".toLowerCase is the dotless ı

  private def underTurkish[A](body: => A): A = {
    val previous = Locale.getDefault
    val outcome = Try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      body
    }
    Locale.setDefault(previous)
    outcome.get
  }

  "HttpConfig under a Turkish default locale" should "still block a blocked domain written in capitals" in
    underTurkish {
      val cfg = HttpConfig(blockedDomains = Seq("internal.example"), allowedDomains = Some(Seq("API.EXAMPLE.COM")))
      cfg.isDomainAllowed("INTERNAL.example") shouldBe false
      cfg.isDomainAllowed("x.INTERNAL.EXAMPLE") shouldBe false
      cfg.isDomainAllowed("api.example.com") shouldBe true
      cfg.isDomainAllowed("WWW.API.EXAMPLE.COM") shouldBe true
    }

  it should "match methods with an I in them" in underTurkish {
    val cfg = HttpConfig().withAllMethods
    cfg.isMethodAllowed("options") shouldBe true
    HttpConfig().isMethodAllowed("delete") shouldBe false
  }

  "HTTPTool under a Turkish default locale" should "match allowlisted header names with an I in them" in
    underTurkish {
      val (sent, _) = HTTPTool.headersForHop(
        Some(Map("X-TRACE-ID" -> "t", "AUTHORIZATION" -> "a")),
        origin("https://a.example/"),
        origin("https://b.example/"),
        false,
        safe = Seq("x-trace-id", "Authorization")
      )
      sent shouldBe Some(Map("X-TRACE-ID" -> "t"))
    }

  it should "send a lower-case method name upper-cased in the root locale" in underTurkish {
    val params = ujson.Obj("url" -> s"$originA/echo", "method" -> "options")
    val result = HTTPTool
      .createSafe(config.withAllMethods)
      .fold(e => fail(e.formatted), identity)
      .handler(SafeParameterExtractor(params))
    result.map(_.method) shouldBe Right("OPTIONS")
  }
}
