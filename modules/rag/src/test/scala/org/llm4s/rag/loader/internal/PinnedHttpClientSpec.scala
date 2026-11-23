package org.llm4s.rag.loader.internal

import org.llm4s.error.NetworkError
import org.llm4s.rag.loader.LoopbackHttpServer
import org.llm4s.rag.loader.LoopbackHttpServer.*
import org.llm4s.rag.loader.UrlPolicy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ BufferedReader, InputStreamReader }
import java.net.{ InetAddress, InetSocketAddress, ServerSocket }
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

class PinnedHttpClientSpec extends AnyFlatSpec with Matchers {

  private val timeout = 5.seconds

  private def get(url: String, policy: UrlPolicy = loopbackPolicy, headers: Seq[(String, String)] = Seq.empty) =
    PinnedHttpClient.get(url, headers, timeout, policy)

  "PinnedHttpClient.get" should "read a Content-Length body and the response headers" in {
    withServer("/a" -> Reply(200, "héllo", Map("ETag" -> "\"v1\""))) { server =>
      val resp = get(s"${server.baseUrl}/a?x=1").toOption.get
      resp.status shouldBe 200
      resp.body shouldBe "héllo"
      resp.header("etag") shouldBe Some("\"v1\"")
      server.paths shouldBe Seq("/a?x=1")
    }
  }

  it should "percent-encode a literal non-ASCII path and query as UTF-8" in {
    withServer("/日本語" -> Reply(200, "ja")) { server =>
      get(s"${server.baseUrl}/日本語?q=東京").map(_.body) shouldBe Right("ja")
      server.paths shouldBe Seq("/%E6%97%A5%E6%9C%AC%E8%AA%9E?q=%E6%9D%B1%E4%BA%AC")
    }
  }

  it should "decode a chunked body" in {
    val body = "chunk " * 5000
    withServer("/c" -> Reply(200, body, chunked = true)) { server =>
      get(s"${server.baseUrl}/c").map(_.body) shouldBe Right(body)
    }
  }

  it should "return a non-redirect error status as a response" in {
    withServer()(server => get(s"${server.baseUrl}/missing").map(_.status) shouldBe Right(404))
  }

  it should "connect to the address the policy returned and send the original Host header" in {
    // `pinned.test` exists only in the policy's resolver: the request can only arrive if the client connects to
    // that address rather than resolving the name again.
    withServer("/p" -> Reply(200, "pinned")) { server =>
      val policy = loopbackPolicy.withResolver(fakeDns("pinned.test"))
      get(s"${server.url("pinned.test")}/p", policy).map(_.body) shouldBe Right("pinned")
      server.requests.map(_.host) shouldBe Seq(Some(s"pinned.test:${server.port}"))
    }
  }

  it should "send the caller's headers, but never its own Host or Connection" in {
    withServer("/h" -> Reply(200, "ok")) { server =>
      get(
        s"${server.baseUrl}/h",
        headers = Seq("User-Agent" -> "ua/1", "X-Token" -> "t", "Host" -> "evil.test", "Connection" -> "keep-alive")
      ).isRight shouldBe true
      val seen = server.requests.head
      seen.userAgent shouldBe Some("ua/1")
      seen.headers.get("x-token") shouldBe Some("t")
      seen.host shouldBe Some(s"127.0.0.1:${server.port}")
      seen.headers.get("connection") shouldBe Some("close")
    }
  }

  it should "refuse a header that would split the request" in {
    withServer() { server =>
      val result = get(s"${server.baseUrl}/", headers = Seq("X-A" -> "v\r\nX-Injected: 1"))
      result.left.map(_.message) shouldBe Left("Invalid HTTP header 'X-A'")
      get(s"${server.baseUrl}/", headers = Seq("Bad Name" -> "v")).isLeft shouldBe true
      server.requests shouldBe empty
    }
  }

  it should "refuse the first URL with the policy's own error, without connecting" in {
    withServer() { server =>
      val result = get(s"${server.baseUrl}/", UrlPolicy.default)
      result.left.map(_.message).left.toOption.get should include("blocked range")
      server.requests shouldBe empty
    }
  }

  it should "follow a redirect, asking the policy again" in {
    withServer("/from" -> redirect("/to"), "/to" -> Reply(200, "arrived")) { server =>
      val resp = get(s"${server.baseUrl}/from").toOption.get
      resp.body shouldBe "arrived"
      resp.url should endWith("/to")
      server.paths shouldBe Seq("/from", "/to")
    }
  }

  it should "not follow a redirect the policy refuses" in {
    withServer("/start" -> redirect("http://169.254.169.254/latest/meta-data/")) { server =>
      val result = get(s"${server.baseUrl}/start")
      result.left.toOption.get.message should startWith("Redirect blocked by SSRF protection")
      server.paths shouldBe Seq("/start")
    }
  }

  it should "not follow a redirect to a host outside the allow-list" in {
    withServer("/start" -> redirect("http://other.test/x")) { server =>
      withServer("/x" -> Reply(200, "secret")) { other =>
        val policy = loopbackPolicy.withResolver(fakeDns("site.test", "other.test")).withAllowedHosts(Set("site.test"))
        val result = get(s"${server.url("site.test")}/start", policy)
        result.left.toOption.get.message should include("not in the allowed hosts")
        other.requests shouldBe empty
      }
    }
  }

  it should "stop after too many redirects, and fail a redirect without a Location" in {
    withServer("/loop" -> redirect("/loop"), "/bare" -> Reply(301, "")) { server =>
      PinnedHttpClient.get(s"${server.baseUrl}/loop", Seq.empty, timeout, loopbackPolicy, maxRedirects = 2) shouldBe
        Left(NetworkError("Too many redirects", None, "http"))
      server.paths should have size 3
      get(s"${server.baseUrl}/bare").left.toOption.get.message should include("without Location header")
    }
  }

  it should "fail on an unparseable URL or redirect location" in {
    get("http://exa mple.com/").left.toOption.get.message should include("Invalid URL")
    withServer("/r" -> redirect("http://bad host/")) { server =>
      get(s"${server.baseUrl}/r").left.toOption.get.message should include("Invalid redirect location")
    }
  }

  it should "fail when nothing listens at any approved address" in {
    val port   = Using.resource(new ServerSocket(0))(_.getLocalPort)
    val result = get(s"http://127.0.0.1:$port/")
    result.left.toOption.get.message should startWith("Connection failed")
  }

  it should "fail an https request whose peer does not speak TLS" in {
    withRawServer("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok", readRequest = false) { port =>
      get(s"https://127.0.0.1:$port/").isLeft shouldBe true
    }
  }

  it should "fail a malformed status line or a truncated body" in {
    withRawServer("garbage\r\n\r\n") { port =>
      get(s"http://127.0.0.1:$port/").left.toOption.get.message should include("Malformed HTTP status line")
    }
    withRawServer("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort") { port =>
      get(s"http://127.0.0.1:$port/").left.toOption.get.message should include("ended after 5 of 10 bytes")
    }
    withRawServer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n") { port =>
      get(s"http://127.0.0.1:$port/").left.toOption.get.message should include("Malformed chunk size")
    }
  }

  it should "skip an interim 1xx response and read a body that ends with the connection" in {
    withRawServer("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\nX-A: 1\nX-A: 2\nnoColon\n\nto the end") { port =>
      val resp = get(s"http://127.0.0.1:$port/").toOption.get
      resp.status shouldBe 200
      resp.header("X-A") shouldBe Some("1")
      resp.body shouldBe "to the end"
    }
  }

  it should "read chunk extensions and trailers" in {
    withRawServer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3;ext=1\r\nabc\r\n0\r\nX-T: 1\r\n\r\n") {
      port => get(s"http://127.0.0.1:$port/").map(_.body) shouldBe Right("abc")
    }
  }

  it should "follow a redirect without reading its body" in {
    withServer("/to" -> Reply(200, "arrived")) { target =>
      // A redirect whose promised body never comes: reading it would wait out the timeout
      val head = s"HTTP/1.1 302 Found\r\nLocation: ${target.baseUrl}/to\r\nContent-Length: 1000000\r\n\r\nstart"
      withRawServer(head, holdOpen = true) { port =>
        val resp = PinnedHttpClient.get(s"http://127.0.0.1:$port/from", Seq.empty, 2.seconds, loopbackPolicy)
        resp.map(_.body) shouldBe Right("arrived")
        target.paths shouldBe Seq("/to")
      }
    }
  }

  it should "return an error status without reading its body" in {
    withRawServer("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 1000000\r\n\r\nstart", holdOpen = true) {
      port =>
        val resp = PinnedHttpClient.get(s"http://127.0.0.1:$port/", Seq.empty, 2.seconds, loopbackPolicy)
        resp.map(r => (r.status, r.body)) shouldBe Right((500, ""))
    }
  }

  /**
   * A one-shot server that reads the request head (if `readRequest`) and answers `response` verbatim, then closes, or
   * with `holdOpen` waits for the client to close first.
   */
  private def withRawServer[A](response: String, readRequest: Boolean = true, holdOpen: Boolean = false)(
    f: Int => A
  ): A =
    Using.resource(new ServerSocket()) { server =>
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress, 0))
      val thread = new Thread(() => {
        Try {
          Using.resource(server.accept()) { socket =>
            val in = new BufferedReader(new InputStreamReader(socket.getInputStream, StandardCharsets.ISO_8859_1))
            if (readRequest)
              Iterator.continually(in.readLine()).takeWhile(l => l != null && l.nonEmpty).foreach(_ => ())
            socket.getOutputStream.write(response.getBytes(StandardCharsets.UTF_8))
            socket.getOutputStream.flush()
            if (holdOpen) in.read()
          }
        }
        ()
      })
      thread.setDaemon(true)
      thread.start()
      val result = f(server.getLocalPort)
      thread.join(5000)
      result
    }
}
