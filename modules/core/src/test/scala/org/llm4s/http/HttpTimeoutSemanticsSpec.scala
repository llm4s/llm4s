package org.llm4s.http

import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import org.llm4s.error.TimeoutError
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Try

/**
 * What a request's timeouts bound (#2802): the wait for the response head, and each wait for more of the
 * body. The JDK's own request timeout stops at the head, so a body that stalls would block its reader for good.
 */
class HttpTimeoutSemanticsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private var server: HttpServer = _
  private var baseUrl: String    = _
  private val client             = Llm4sHttpClient.create()
  private val executor           = Executors.newCachedThreadPool()
  private val release            = new CountDownLatch(1)
  private given ExecutionContext = ExecutionContext.fromExecutor(executor)

  /** Fails the test rather than hanging it when the client never gives up. */
  private def within[A](limit: FiniteDuration)(body: => A): A =
    Try(Await.result(Future(body), limit)).fold(
      {
        case _: java.util.concurrent.TimeoutException => fail(s"still blocked after $limit")
        case other                                    => throw other
      },
      identity
    )

  override def beforeAll(): Unit = {
    server = HttpServer.create(new InetSocketAddress(0), 0)
    // Sends the head and the first bytes of a chunked body, then nothing until the suite ends.
    server.createContext(
      "/stall",
      new HttpHandler {
        override def handle(exchange: HttpExchange): Unit = {
          exchange.getRequestBody.readAllBytes()
          exchange.sendResponseHeaders(200, 0)
          exchange.getResponseBody.write("data: first\n\n".getBytes(StandardCharsets.UTF_8))
          exchange.getResponseBody.flush()
          release.await(30, TimeUnit.SECONDS)
          Try(exchange.close())
        }
      }
    )
    server.setExecutor(executor)
    server.start()
    baseUrl = s"http://localhost:${server.getAddress.getPort}"
  }

  override def afterAll(): Unit = {
    release.countDown()
    if (server != null) server.stop(0)
    client.close()
    executor.shutdownNow()
  }

  "A streamed body that stalls" should "fail its read once the timeout passes, not block for good" in {
    val response = client.postStream(s"$baseUrl/stall", body = "{}", timeout = 500.millis).toOption.get
    val outcome = within(10.seconds) {
      Try {
        val first = new String(response.body.readNBytes(13), StandardCharsets.UTF_8)
        first shouldBe "data: first\n\n"
        response.body.read()
      }
    }
    Try(response.body.close())
    outcome.failed.toOption.map(_.getClass) shouldBe Some(classOf[java.net.SocketTimeoutException])
  }

  "A buffered body that stalls" should "be a TimeoutError" in {
    val result = within(10.seconds)(client.post(s"$baseUrl/stall", body = "{}", timeout = 500.millis))
    result.left.toOption.map(_.getClass) shouldBe Some(classOf[TimeoutError])
  }
}
