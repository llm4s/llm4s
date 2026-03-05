package org.llm4s.toolapi.builtin.http

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress

/**
 * Test suite for SSRF protection and validated redirect handling in [[HTTPTool]].
 *
 *   - All tests that exercise redirect logic use an in-process JDK [[HttpServer]]
 *     bound to a random free port on 127.0.0.1.  No real network calls are made.
 *
 *   - The test [[HttpConfig]] sets `blockedDomains = Seq("169.254.169.254")` and
 *     `blockInternalIPs = false`, which means:
 *     - the local test server (127.0.0.1:PORT) is reachable (first hop), AND
 *     - the cloud metadata endpoint (169.254.169.254) is blocked (redirect target).
 *
 * This directly simulates the open-redirect SSRF bypass scenario (Issue #788):
 * a "safe" initial URL redirects to a forbidden internal address.
 */
class HTTPToolSSRFSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var server: HttpServer = _
  private var port: Int          = _

  // ── Embedded test server setup ────────────────────────────────────────────

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    port = server.getAddress.getPort

    // /ok → 200 "hello world"
    server.createContext(
      "/ok",
      { (ex: HttpExchange) =>
        val body = "hello world".getBytes("UTF-8")
        ex.sendResponseHeaders(200, body.length.toLong)
        ex.getResponseBody.write(body)
        ex.close()
      }
    )

    // /api/v2 → 200 "api v2 reached"
    server.createContext(
      "/api/v2",
      { (ex: HttpExchange) =>
        val body = "api v2 reached".getBytes("UTF-8")
        ex.sendResponseHeaders(200, body.length.toLong)
        ex.getResponseBody.write(body)
        ex.close()
      }
    )

    // /redirect-to-ok → 302 Location: /ok  (relative path)
    server.createContext(
      "/redirect-to-ok",
      { (ex: HttpExchange) =>
        ex.getResponseHeaders.set("Location", "/ok")
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )

    // /redirect-to-api → 302 Location: /api/v2  (absolute path)
    server.createContext(
      "/redirect-to-api",
      { (ex: HttpExchange) =>
        ex.getResponseHeaders.set("Location", s"http://127.0.0.1:$port/api/v2")
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )

    // /redirect-to-blocked → 302 Location: http://169.254.169.254/metadata
    // Simulates the open-redirect SSRF bypass attack vector.
    server.createContext(
      "/redirect-to-blocked",
      { (ex: HttpExchange) =>
        ex.getResponseHeaders.set("Location", "http://169.254.169.254/metadata")
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )

    // /redirect-self → 302 Location: /redirect-self  (infinite loop)
    server.createContext(
      "/redirect-self",
      { (ex: HttpExchange) =>
        ex.getResponseHeaders.set("Location", s"http://127.0.0.1:$port/redirect-self")
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )

    // /redirect-lowercase → 302 location: /ok  (lowercase header name)
    server.createContext(
      "/redirect-lowercase",
      { (ex: HttpExchange) =>
        ex.getResponseHeaders.set("location", "/ok") // intentionally lowercase
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )

    // /redirect-to-file → 302 Location: file:///etc/passwd
    // Simulates a protocol-smuggling attack via open redirect.
    server.createContext(
      "/redirect-to-file",
      { (ex: HttpExchange) =>
        ex.getResponseHeaders.set("Location", "file:///etc/passwd")
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )

    // /redirect-chain-1 → 302 Location: /redirect-chain-2
    server.createContext(
      "/redirect-chain-1",
      { (ex: HttpExchange) =>
        ex.getResponseHeaders.set("Location", s"http://127.0.0.1:$port/redirect-chain-2")
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )

    // /redirect-chain-2 → 302 Location: /ok
    server.createContext(
      "/redirect-chain-2",
      { (ex: HttpExchange) =>
        ex.getResponseHeaders.set("Location", s"http://127.0.0.1:$port/ok")
        ex.sendResponseHeaders(302, -1)
        ex.close()
      }
    )

    server.setExecutor(null)
    server.start()
  }

  override def afterAll(): Unit =
    if (server != null) server.stop(0)

  // ── Helpers ───────────────────────────────────────────────────────────────

  /**
   * HttpConfig that allows 127.0.0.1 for the test server while keeping
   * 169.254.169.254 (cloud metadata) on the blocked list.
   */
  def testConfig(followRedirects: Boolean = false, maxRedirects: Int = 5): HttpConfig =
    HttpConfig(
      blockedDomains = Seq("169.254.169.254"),
      blockInternalIPs = false,
      followRedirects = followRedirects,
      maxRedirects = maxRedirects,
      allowedMethods = Seq("GET")
    )

  def invoke(config: HttpConfig, url: String): Either[String, HTTPResult] =
    HTTPTool.makeRequest(
      urlStr = url,
      method = "GET",
      headers = None,
      body = None,
      contentType = None,
      config = config
    )

  // ── SSRF: initial URL validation ──────────────────────────────────────────

  "HTTPTool SSRF protection" should "block direct requests to the cloud metadata IP (169.254.169.254)" in {
    val result = invoke(HttpConfig(), "http://169.254.169.254/latest/meta-data/")
    result.isLeft shouldBe true
    result.swap.toOption.get should include("169.254.169.254")
  }

  it should "block direct requests to localhost" in {
    val result = invoke(HttpConfig(), "http://localhost:8080/secret")
    result.isLeft shouldBe true
    result.swap.toOption.get should include("not allowed")
  }

  it should "block direct requests to private IP range 192.168.x.x" in {
    val result = invoke(HttpConfig(), "http://192.168.1.1/admin")
    result.isLeft shouldBe true
    result.swap.toOption.get should (include("not allowed").or(include("192.168")))
  }

  it should "block direct requests to private IP range 10.x.x.x" in {
    val result = invoke(HttpConfig(), "http://10.0.0.1/internal")
    result.isLeft shouldBe true
  }

  // ── followRedirects default ───────────────────────────────────────────────

  it should "default followRedirects to false in HttpConfig" in {
    HttpConfig().followRedirects shouldBe false
  }

  it should "return 302 as-is when followRedirects=false" in {
    val result = invoke(testConfig(followRedirects = false), s"http://127.0.0.1:$port/redirect-to-ok")
    result.isRight shouldBe true
    result.toOption.get.statusCode shouldBe 302
  }

  // ── followRedirects=true: safe redirects ──────────────────────────────────

  it should "follow a safe relative Location header to a 200 response" in {
    val result = invoke(testConfig(followRedirects = true), s"http://127.0.0.1:$port/redirect-to-ok")
    result.isRight shouldBe true
    result.toOption.get.statusCode shouldBe 200
    result.toOption.get.body should include("hello world")
  }

  it should "follow a safe absolute Location header to a 200 response" in {
    val result = invoke(testConfig(followRedirects = true), s"http://127.0.0.1:$port/redirect-to-api")
    result.isRight shouldBe true
    result.toOption.get.statusCode shouldBe 200
    result.toOption.get.body should include("api v2 reached")
  }

  it should "follow a multi-hop safe redirect chain to a final 200 response" in {
    // /redirect-chain-1 → /redirect-chain-2 → /ok (2 hops, maxRedirects=5)
    val result = invoke(testConfig(followRedirects = true), s"http://127.0.0.1:$port/redirect-chain-1")
    result.isRight shouldBe true
    result.toOption.get.statusCode shouldBe 200
    result.toOption.get.body should include("hello world")
  }

  // ── Per-hop SSRF validation (the core fix for Issue #788) ─────────────────

  it should "block a redirect that targets the cloud metadata IP (open-redirect SSRF bypass)" in {
    // Scenario: attacker supplies a URL on a "trusted" host that subsequently
    // redirects (via 302) to http://169.254.169.254/metadata.
    // With the fix, the second hop is validated and rejected BEFORE the request
    // to 169.254.169.254 is ever issued.
    val result = invoke(testConfig(followRedirects = true), s"http://127.0.0.1:$port/redirect-to-blocked")
    result.isLeft shouldBe true
    val err = result.swap.toOption.get
    err should include("169.254.169.254")
    err.toLowerCase should include("not allowed")
  }

  // ── Redirect loop / DoS prevention ───────────────────────────────────────

  it should "stop following redirects and return an error after maxRedirects hops" in {
    // /redirect-self → /redirect-self → … (infinite), maxRedirects=3
    val result = invoke(
      testConfig(followRedirects = true, maxRedirects = 3),
      s"http://127.0.0.1:$port/redirect-self"
    )
    result.isLeft shouldBe true
    val err = result.swap.toOption.get
    err should include("Too many redirects")
    err should include("3")
  }

  it should "succeed when the number of hops is exactly maxRedirects" in {
    // Chain of 2 hops (/chain-1 → /chain-2 → /ok) with maxRedirects=2
    val result = invoke(
      testConfig(followRedirects = true, maxRedirects = 2),
      s"http://127.0.0.1:$port/redirect-chain-1"
    )
    result.isRight shouldBe true
    result.toOption.get.statusCode shouldBe 200
  }

  it should "fail when the number of hops exceeds maxRedirects" in {
    // Chain of 2 hops but maxRedirects=1: the second redirect is one too many
    val result = invoke(
      testConfig(followRedirects = true, maxRedirects = 1),
      s"http://127.0.0.1:$port/redirect-chain-1"
    )
    result.isLeft shouldBe true
    result.swap.toOption.get should include("Too many redirects")
  }

  // ── Case-insensitive Location header ─────────────────────────────────────

  it should "follow a redirect when the Location header name is lowercase" in {
    // The server sends 'location: /ok' (lowercase). Our equalsIgnoreCase lookup
    // must find it regardless of capitalisation.
    val result = invoke(testConfig(followRedirects = true), s"http://127.0.0.1:$port/redirect-lowercase")
    result.isRight shouldBe true
    result.toOption.get.statusCode shouldBe 200
    result.toOption.get.body should include("hello world")
  }

  // ── Scheme enforcement (protocol smuggling prevention) ────────────────────

  it should "reject an initial request with a file:// scheme" in {
    val result = invoke(testConfig(), "file:///etc/passwd")
    result.isLeft shouldBe true
    val err = result.swap.toOption.get
    err should include("UNSUPPORTED_PROTOCOL")
    err should include("file")
  }

  it should "reject an initial request with an ftp:// scheme" in {
    val result = invoke(testConfig(), "ftp://ftp.example.com/pub/file.txt")
    result.isLeft shouldBe true
    val err = result.swap.toOption.get
    err should include("UNSUPPORTED_PROTOCOL")
    err should include("ftp")
  }

  it should "block a redirect that switches to the file:// scheme (protocol smuggling)" in {
    // Server at /redirect-to-file sends: 302 Location: file:///etc/passwd
    // The scheme check on the redirect target must fire BEFORE any connection is opened.
    val result = invoke(testConfig(followRedirects = true), s"http://127.0.0.1:$port/redirect-to-file")
    result.isLeft shouldBe true
    val err = result.swap.toOption.get
    err should include("UNSUPPORTED_PROTOCOL")
    err should include("file")
  }

  it should "include SSRF_BLOCKED in the error when a domain is rejected" in {
    val result = invoke(testConfig(followRedirects = true), s"http://127.0.0.1:$port/redirect-to-blocked")
    result.isLeft shouldBe true
    result.swap.toOption.get should include("SSRF_BLOCKED")
  }

  it should "include TOO_MANY_REDIRECTS in the error when the hop limit is reached" in {
    val result = invoke(
      testConfig(followRedirects = true, maxRedirects = 2),
      s"http://127.0.0.1:$port/redirect-self"
    )
    result.isLeft shouldBe true
    result.swap.toOption.get should include("TOO_MANY_REDIRECTS")
  }
}
