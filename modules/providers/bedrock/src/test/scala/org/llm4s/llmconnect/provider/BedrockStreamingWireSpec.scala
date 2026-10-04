package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.*
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.withServer
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/**
 * ConverseStream's binary event-stream, driven through the real AWS SDK async client: framing
 * split at arbitrary byte boundaries, several tool calls in one turn, in-stream errors, a stream
 * that ends early, and cancellation releasing its connection and threads.
 */
class BedrockStreamingWireSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val conv = Conversation(Seq(UserMessage("Hello")))

  private def run(url: String) = {
    val client = new BedrockClient(config(url))
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conv, CompletionOptions(), c => chunks += c)
    client.close()
    (result, chunks.toList)
  }

  private def delta(index: Int, inner: ujson.Obj): Array[Byte] =
    eventFrame("contentBlockDelta", ujson.Obj("contentBlockIndex" -> index, "delta" -> inner).render())

  private def toolStart(index: Int, id: String, name: String): Array[Byte] =
    eventFrame(
      "contentBlockStart",
      ujson
        .Obj(
          "contentBlockIndex" -> index,
          "start"             -> ujson.Obj("toolUse" -> ujson.Obj("toolUseId" -> id, "name" -> name))
        )
        .render()
    )

  private def toolArgs(index: Int, fragment: String): Array[Byte] =
    delta(index, ujson.Obj("toolUse" -> ujson.Obj("input" -> fragment)))

  private def stop(reason: String) = eventFrame("messageStop", s"""{"stopReason":"$reason"}""")
  private val usage = eventFrame(
    "metadata",
    """{"usage":{"inputTokens":7,"outputTokens":3,"totalTokens":10},"metrics":{"latencyMs":5}}"""
  )
  private def blockStop(index: Int) = eventFrame("contentBlockStop", s"""{"contentBlockIndex":$index}""")

  /** Writes `bytes` in slices of the sizes drawn from `sizes` (cycled), flushing after each. */
  private def sendSplit(exchange: HttpExchange, frames: Seq[Array[Byte]], sizes: Seq[Int]): Unit = {
    exchange.getResponseHeaders.add("Content-Type", EventStreamContentType)
    exchange.sendResponseHeaders(200, 0)
    val os    = exchange.getResponseBody
    val bytes = frames.flatten.toArray
    var pos   = 0
    var i     = 0
    while (pos < bytes.length) {
      val n = math.min(sizes(i % sizes.size), bytes.length - pos)
      os.write(bytes, pos, n)
      os.flush()
      pos += n
      i += 1
    }
    os.close()
  }

  private val mixedTurn = Seq(
    eventFrame("messageStart", """{"role":"assistant"}"""),
    delta(0, ujson.Obj("text" -> "Hello ")),
    delta(0, ujson.Obj("text" -> "wörld ☃")),
    blockStop(0),
    toolStart(1, "tc-a", "get_weather"),
    toolArgs(1, """{"city":"Pa"""),
    toolStart(2, "tc-b", "get_time"),
    toolArgs(1, """ris","days":3}"""),
    toolArgs(2, """{"tz":"""),
    blockStop(1),
    toolArgs(2, """"UTC","h":[1,2.5,null]}"""),
    blockStop(2),
    stop("tool_use"),
    usage
  )

  private def assertMixedTurn(result: Result[Completion], chunks: List[StreamedChunk]) = {
    val c = result.toOption.value
    c.content shouldBe "Hello wörld ☃"
    c.toolCalls.map(t => (t.id, t.name)) shouldBe Seq(("tc-a", "get_weather"), ("tc-b", "get_time"))
    c.toolCalls(0).arguments shouldBe ujson.Obj("city" -> "Paris", "days" -> 3)
    c.toolCalls(1).arguments shouldBe ujson.Obj("tz" -> "UTC", "h" -> ujson.Arr(1, 2.5, ujson.Null))
    c.message.toolCalls shouldBe c.toolCalls
    c.usage.map(u => (u.promptTokens, u.completionTokens)) shouldBe Some((7, 3))
    chunks.flatMap(_.content).mkString shouldBe "Hello wörld ☃"
    chunks.flatMap(_.finishReason) shouldBe List("tool_use")
  }

  private type Result[A] = org.llm4s.types.Result[A]

  "ConverseStream framing" should {

    "decode text plus two interleaved tool calls delivered whole" in {
      withServer("/")(sendEventStream(_, mixedTurn)) { url =>
        val (r, chunks) = run(url)
        assertMixedTurn(r, chunks)
      }
    }

    "decode the same turn when the bytes arrive split at every boundary size" in {
      val schedules = Seq(Seq(1), Seq(2), Seq(3), Seq(5, 1, 11), Seq(7), Seq(13, 2), Seq(64), Seq(997))
      schedules.foreach { sizes =>
        withClue(s"split sizes $sizes: ") {
          withServer("/")(sendSplit(_, mixedTurn, sizes)) { url =>
            val (r, chunks) = run(url)
            assertMixedTurn(r, chunks)
          }
        }
      }
    }

    "give a tool that streams no argument fragments an empty argument object" in {
      val frames = Seq(toolStart(0, "tc-z", "ping"), blockStop(0), stop("tool_use"), usage)
      withServer("/")(sendEventStream(_, frames)) { url =>
        val c = run(url)._1.toOption.value
        c.toolCalls.map(t => (t.id, t.name, t.arguments)) shouldBe Seq(("tc-z", "ping", ujson.Obj()))
      }
    }

    "keep text and reasoning in separate channels" in {
      val frames = Seq(
        delta(0, ujson.Obj("reasoningContent" -> ujson.Obj("text" -> "think "))),
        delta(0, ujson.Obj("reasoningContent" -> ujson.Obj("text" -> "more"))),
        delta(1, ujson.Obj("text" -> "answer")),
        stop("end_turn")
      )
      withServer("/")(sendEventStream(_, frames)) { url =>
        val (r, chunks) = run(url)
        r.toOption.value.thinking shouldBe Some("think more")
        r.toOption.value.content shouldBe "answer"
        chunks.flatMap(_.thinkingDelta).mkString shouldBe "think more"
      }
    }
  }

  "an in-stream exception" should {

    "map to the matching LLMError, after delivering the chunks that preceded it" in {
      val table: Seq[(String, org.llm4s.error.LLMError => Boolean)] = Seq(
        "throttlingException"         -> (_.isInstanceOf[RateLimitError]),
        "validationException"         -> (_.isInstanceOf[ValidationError]),
        "internalServerException"     -> (e => e.isInstanceOf[ServiceError]),
        "serviceUnavailableException" -> (e => e.isInstanceOf[ServiceError]),
        "modelStreamErrorException"   -> (e => e.isInstanceOf[ServiceError])
      )
      table.foreach { case (exceptionType, ok) =>
        withClue(exceptionType + ": ") {
          withServer("/")(
            sendEventStream(_, Seq(delta(0, ujson.Obj("text" -> "partial")), exceptionFrame(exceptionType, "boom")))
          ) { url =>
            val (r, chunks) = run(url)
            chunks.flatMap(_.content) shouldBe List("partial")
            val err = r.left.toOption.value
            withClue(err.toString)(ok(err) shouldBe true)
          }
        }
      }
    }

    "report the status carried by an internal server error" in {
      withServer("/")(sendEventStream(_, Seq(exceptionFrame("internalServerException", "boom")))) { url =>
        run(url)._1.left.toOption.value should matchPattern { case e: ServiceError if e.httpStatus == 500 => }
      }
    }
  }

  "cancelling a stream by interrupt" should {

    "close the connection to the server and leave no SDK threads behind once the client is closed" in {
      def sdkThreads = Thread.getAllStackTraces.keySet.asScala.count(t => t.getName.contains("NettyEventLoop"))
      val before     = sdkThreads

      val serverSawClose = new CountDownLatch(1)
      val firstChunk     = new CountDownLatch(1)
      withServer("/") { ex =>
        ex.getResponseHeaders.add("Content-Type", EventStreamContentType)
        ex.sendResponseHeaders(200, 0)
        val os = ex.getResponseBody
        // Keep sending frames until a write fails, which is when the client has hung up.
        val sent = scala.util.Try {
          while (true) {
            os.write(delta(0, ujson.Obj("text" -> "tick")))
            os.flush()
            Thread.sleep(20)
          }
        }
        if (sent.isFailure) serverSawClose.countDown()
      } { url =>
        val client  = new BedrockClient(config(url))
        val outcome = new java.util.concurrent.atomic.AtomicReference[(org.llm4s.types.Result[Completion], Boolean)]()
        val worker = Thread.ofVirtual().start { () =>
          val r = client.streamComplete(conv, CompletionOptions(), _ => firstChunk.countDown())
          outcome.set((r, Thread.currentThread().isInterrupted))
        }
        firstChunk.await(10, TimeUnit.SECONDS) shouldBe true
        worker.interrupt()
        worker.join(10000)
        worker.isAlive shouldBe false
        outcome.get()._1.left.toOption.value shouldBe a[CancelledError]
        outcome.get()._2 shouldBe true

        withClue("server never saw the connection close")(serverSawClose.await(10, TimeUnit.SECONDS) shouldBe true)

        client.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (sdkThreads > before && System.nanoTime() < deadline) Thread.sleep(50)
        sdkThreads should be <= before
      }
    }

    "return CancelledError at once, without a request, when the thread is already interrupted" in {
      val called = new AtomicBoolean(false)
      withServer("/") { ex =>
        called.set(true); sendEventStream(ex, mixedTurn)
      } { url =>
        val client = new BedrockClient(config(url))
        Thread.currentThread().interrupt()
        val r    = client.streamComplete(conv, CompletionOptions(), _ => ())
        val flag = Thread.interrupted() // clear the flag so it cannot leak into other tests
        client.close()
        r.left.toOption.value shouldBe a[CancelledError]
        flag shouldBe true
      }
      called.get() shouldBe false
    }
  }
}
