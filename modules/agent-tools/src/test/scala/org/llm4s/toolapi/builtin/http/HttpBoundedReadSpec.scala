package org.llm4s.toolapi.builtin.http

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The HTTP tool cuts a response off at `maxResponseSize` instead of reading the whole body first (issue #1408,
 * finding F4). The end-to-end test uses an in-process server on loopback that streams far more than the cap and
 * counts how much of it was written before the client went away.
 */
class HttpBoundedReadSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Declared first: the tests below register closures that use them.
  private val Chunk              = Array.fill[Byte](64 * 1024)('a'.toByte)
  private val ChunksOnOffer      = 1024 // 64 MiB in all
  private val bytesWritten       = new AtomicLong(0)
  private var server: HttpServer = _

  // ---- the helper, on its own

  /** What the tool did before: read everything, then cut. */
  private def formerly(body: String, cap: Long): (String, Boolean) =
    if (body.length > cap) (body.take(cap.toInt), true) else (body, false)

  "readBounded" should "give the same result as reading everything and then cutting, for any body and cap" in {
    val bodies = Seq("", "a", "abc", "héllo wörld", "😀😀😀", "x" * 1000)
    for {
      body <- bodies
      cap  <- Seq(0L, 1L, 2L, 3L, 5L, 999L, 1000L, 1001L, 100000L)
    } withClue(s"body of ${body.length} chars, cap $cap: ")(
      HTTPTool.readBounded(body.iterator, cap) shouldBe formerly(body, cap)
    )
  }

  it should "report a body of exactly the cap as whole and one more character as cut" in {
    HTTPTool.readBounded("abcde".iterator, 5L) shouldBe (("abcde", false))
    HTTPTool.readBounded("abcdef".iterator, 5L) shouldBe (("abcde", true))
  }

  it should "never take more than the cap plus one character from the source" in {
    val taken  = new AtomicLong(0)
    val source = Iterator.continually { taken.incrementAndGet(); 'a' }

    val (body, truncated) = HTTPTool.readBounded(source, 100L)

    body.length shouldBe 100
    truncated shouldBe true
    taken.get() shouldBe 101L
  }

  it should "treat a negative cap as zero, and a huge cap as no cut for a short body" in {
    HTTPTool.readBounded("abc".iterator, -5L) shouldBe (("", true))
    HTTPTool.readBounded("abc".iterator, Long.MaxValue) shouldBe (("abc", false))
  }

  // ---- end to end

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/big",
      (exchange: HttpExchange) => {
        exchange.sendResponseHeaders(200, 0) // chunked: the length is not announced
        val out = exchange.getResponseBody

        @tailrec
        def stream(sent: Int): Unit =
          if (sent < ChunksOnOffer && Try(out.write(Chunk)).isSuccess) {
            bytesWritten.addAndGet(Chunk.length.toLong)
            stream(sent + 1)
          }

        stream(0)
        Try(exchange.close())
        ()
      }
    )
    server.createContext(
      "/small",
      (exchange: HttpExchange) => {
        val bytes = "small body".getBytes("UTF-8")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
  }

  override def afterAll(): Unit =
    if (server != null) server.stop(0)

  private def get(path: String, maxResponseSize: Long): Either[String, HTTPResult] = {
    val config = HttpConfig(
      blockedDomains = Seq.empty,
      blockInternalIPs = false,
      maxResponseSize = maxResponseSize,
      timeout = 30.seconds
    )
    HTTPTool
      .createSafe(config)
      .fold(e => fail(e.formatted), identity)
      .handler(SafeParameterExtractor(ujson.Obj("url" -> s"http://127.0.0.1:${server.getAddress.getPort}$path")))
  }

  "The HTTP tool" should "cut a huge response at the cap and stop the server from sending the rest" in {
    bytesWritten.set(0)

    val result = get("/big", maxResponseSize = 1024).fold(e => fail(e), identity)

    result.truncated shouldBe true
    result.body.length shouldBe 1024
    // The whole body is 64 MiB. Socket buffers hold a few MiB and a platform may take a while to notice a closed
    // connection; reading the body in full would make this exactly 64 MiB.
    bytesWritten.get() should be < (48L * 1024 * 1024)
  }

  it should "return a body under the cap whole" in {
    val result = get("/small", maxResponseSize = 1024).fold(e => fail(e), identity)

    result.body shouldBe "small body"
    result.truncated shouldBe false
  }
}
