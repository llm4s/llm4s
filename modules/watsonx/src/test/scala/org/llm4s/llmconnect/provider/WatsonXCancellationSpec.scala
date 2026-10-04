package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/** Interrupting a streaming call stops the read AND closes the connection - against a real socket. */
class WatsonXCancellationSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val firstEvent =
    """id: 1
      |event: message
      |data: {"results":[{"generated_text":"Hi","generated_token_count":1,"input_token_count":4,"stop_reason":"not_finished"}]}
      |
      |""".stripMargin

  private def configAt(baseUrl: String): WatsonXConfig =
    WatsonXConfig(
      apiKey = "k",
      projectId = "p",
      spaceId = None,
      model = "m",
      baseUrl = baseUrl,
      apiVersion = "2024-05-31",
      iamUrl = s"$baseUrl/iam",
      contextWindow = 4096,
      reserveCompletion = 512
    )

  test(
    "interrupting mid-stream returns CancelledError promptly with the flag kept, and the server sees the socket close"
  ) {
    val socketClosed = new CountDownLatch(1)
    val firstChunk   = new CountDownLatch(1)
    val handler: HttpExchange => Unit = exchange =>
      if exchange.getRequestURI.getPath == "/iam" then
        sendJsonResponse(exchange, 200, """{"access_token":"t","expires_in":3600}""")
      else
        exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        val os = exchange.getResponseBody
        val outcome = Try {
          os.write(firstEvent.getBytes(StandardCharsets.UTF_8))
          os.flush()
          // keep the stream alive with comments until the client goes away
          Iterator
            .continually { Thread.sleep(20); os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8)); os.flush() }
            .take(1000)
            .foreach(_ => ())
        }
        if outcome.isFailure then socketClosed.countDown()

    withServer("/")(handler) { baseUrl =>
      val client   = new WatsonXClient(configAt(baseUrl))
      val result   = new AtomicReference[Option[Result[Completion]]](None)
      val flagKept = new AtomicReference[Boolean](false)
      val worker = Thread.ofVirtual().start { () =>
        val r = client.streamComplete(
          Conversation(Seq(UserMessage("Hi"))),
          CompletionOptions(),
          _ => firstChunk.countDown()
        )
        flagKept.set(Thread.currentThread().isInterrupted)
        result.set(Some(r))
      }
      firstChunk.await(10, TimeUnit.SECONDS) shouldBe true
      worker.interrupt()
      worker.join(10000)
      worker.isAlive shouldBe false
      result.get().flatMap(_.left.toOption).exists(_.isInstanceOf[CancelledError]) shouldBe true
      flagKept.get() shouldBe true
      withClue("the server never saw the connection close: ")(socketClosed.await(10, TimeUnit.SECONDS) shouldBe true)
      client.close()
    }
  }
