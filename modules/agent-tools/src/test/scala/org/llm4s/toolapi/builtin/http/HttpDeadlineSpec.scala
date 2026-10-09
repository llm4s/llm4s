package org.llm4s.toolapi.builtin.http

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.toolapi._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ InetSocketAddress, ServerSocket }
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ ExecutorService, Executors }
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Try

/**
 * `HttpConfig.timeout` bounds the whole call (issue #1408, finding F8). It used to be applied per read, so a server
 * that sent one byte every 100 ms held a 500 ms call for as long as it kept sending. The servers here run on loopback
 * and each request gets its own thread, so a slow handler does not hold up the others.
 */
class HttpDeadlineSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var server: HttpServer          = _
  private var executor: ExecutorService   = _
  private var headerDripper: ServerSocket = _
  private def port                        = server.getAddress.getPort

  private val Tick      = 100.millis
  private val DripBytes = 50          // 5 seconds of dripping
  private val Timeout   = 500.millis
  private val Slack     = 1500.millis // generous for a loaded CI runner; the old behaviour took 5 s here
  private val SlowHop   = 300.millis

  private def sleep(d: FiniteDuration): Unit = Thread.sleep(d.toMillis)

  /** Write one byte per tick until `n` bytes are sent or the client goes away. */
  private def drip(ex: HttpExchange, n: Int): Unit = {
    val out = ex.getResponseBody
    @tailrec
    def loop(i: Int): Unit =
      if (i < n && Try { out.write('x'.toInt); out.flush() }.isSuccess) {
        sleep(Tick)
        loop(i + 1)
      }
    loop(0)
    Try(ex.close())
    ()
  }

  override def beforeAll(): Unit = {
    executor = Executors.newCachedThreadPool()
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(executor)
    server.createContext(
      "/drip-chunked",
      (ex: HttpExchange) => {
        ex.sendResponseHeaders(200, 0)
        drip(ex, DripBytes)
      }
    )
    server.createContext(
      "/drip-fixed",
      (ex: HttpExchange) => {
        ex.sendResponseHeaders(200, DripBytes.toLong)
        drip(ex, DripBytes)
      }
    )
    // /slow-hop?n=k waits SlowHop, then redirects to n=k-1; n=0 answers "done"
    server.createContext(
      "/slow-hop",
      (ex: HttpExchange) => {
        val n = Option(ex.getRequestURI.getQuery).map(_.stripPrefix("n=").toInt).getOrElse(0)
        sleep(SlowHop)
        if (n > 0) {
          ex.getResponseHeaders.set("Location", s"/slow-hop?n=${n - 1}")
          ex.sendResponseHeaders(302, -1)
        } else {
          val body = "done".getBytes(StandardCharsets.UTF_8)
          ex.sendResponseHeaders(200, body.length.toLong)
          ex.getResponseBody.write(body)
        }
        ex.close()
      }
    )
    server.createContext(
      "/fast",
      (ex: HttpExchange) => {
        val body = "fast".getBytes(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(200, body.length.toLong)
        ex.getResponseBody.write(body)
        ex.close()
      }
    )
    server.start()

    // A raw server that sends the status line, then drips a header one byte per tick.
    headerDripper = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    executor.submit(new Runnable {
      def run(): Unit = {
        @tailrec
        def accept(): Unit =
          Try(headerDripper.accept()).toOption match {
            case Some(socket) =>
              executor.submit(new Runnable {
                def run(): Unit = {
                  val out = socket.getOutputStream
                  Try(socket.getInputStream.read(new Array[Byte](4096)))
                  val ok = Try {
                    out.write("HTTP/1.1 200 OK\r\nX-Pad: ".getBytes(StandardCharsets.US_ASCII)); out.flush()
                  }
                  @tailrec
                  def loop(i: Int): Unit =
                    if (i < DripBytes && Try { out.write('a'.toInt); out.flush() }.isSuccess) {
                      sleep(Tick)
                      loop(i + 1)
                    }
                  if (ok.isSuccess) loop(0)
                  Try(out.write("\r\nContent-Length: 2\r\n\r\nok".getBytes(StandardCharsets.US_ASCII)))
                  Try(socket.close())
                  ()
                }
              })
              accept()
            case None => ()
          }
        accept()
      }
    })
    ()
  }

  override def afterAll(): Unit = {
    if (server != null) server.stop(0)
    if (headerDripper != null) Try(headerDripper.close())
    if (executor != null) executor.shutdownNow()
    ()
  }

  private def config(timeout: FiniteDuration, followRedirects: Boolean = false) = HttpConfig(
    blockedDomains = Seq.empty,
    blockInternalIPs = false,
    timeout = timeout,
    followRedirects = followRedirects,
    maxRedirects = 10
  )

  /** The call's outcome and how long it took. */
  private def timed(url: String, cfg: HttpConfig): (Either[String, HTTPResult], FiniteDuration) = {
    val tool  = HTTPTool.createSafe(cfg).fold(e => fail(e.formatted), identity)
    val start = System.nanoTime()
    val out   = tool.handler(SafeParameterExtractor(ujson.Obj("url" -> url)))
    (out, (System.nanoTime() - start).nanos)
  }

  private def assertCutAtDeadline(url: String, cfg: HttpConfig): Unit = {
    val (result, took) = timed(url, cfg)
    withClue(s"took ${took.toMillis} ms, result $result: ") {
      result.isLeft shouldBe true
      result.swap.toOption.get should startWith("TIMEOUT")
      took should be >= (cfg.timeout - 50.millis)
      took should be < (cfg.timeout + Slack)
    }
  }

  "HTTPTool timeout" should "cut a chunked body dripped one byte every 100 ms at the deadline" in {
    assertCutAtDeadline(s"http://127.0.0.1:$port/drip-chunked", config(Timeout))
  }

  it should "cut a fixed-length body dripped one byte every 100 ms at the deadline" in {
    assertCutAtDeadline(s"http://127.0.0.1:$port/drip-fixed", config(Timeout))
  }

  it should "cut a response whose headers are dripped one byte every 100 ms at the deadline" in {
    assertCutAtDeadline(s"http://127.0.0.1:${headerDripper.getLocalPort}/", config(Timeout))
  }

  it should "count every redirect hop against one deadline" in {
    // Four hops of 300 ms each: every hop is well inside 700 ms, the chain is not.
    assertCutAtDeadline(s"http://127.0.0.1:$port/slow-hop?n=3", config(700.millis, followRedirects = true))
  }

  it should "let a redirect chain that fits in the deadline complete" in {
    val (result, _) = timed(s"http://127.0.0.1:$port/slow-hop?n=1", config(5.seconds, followRedirects = true))
    result.map(_.body) shouldBe Right("done")
  }

  it should "leave a fast request alone, and keep working after a timed-out call" in {
    assertCutAtDeadline(s"http://127.0.0.1:$port/drip-chunked", config(Timeout))
    for (_ <- 1 to 3) {
      val (result, took) = timed(s"http://127.0.0.1:$port/fast", config(Timeout))
      result.map(r => (r.statusCode, r.body)) shouldBe Right((200, "fast"))
      took should be < Timeout
    }
  }

  it should "fail every call when the timeout is zero (it no longer means no timeout)" in {
    val (result, took) = timed(s"http://127.0.0.1:$port/fast", config(Duration.Zero))
    result.swap.toOption.get should startWith("TIMEOUT")
    took should be < Slack
  }
}
