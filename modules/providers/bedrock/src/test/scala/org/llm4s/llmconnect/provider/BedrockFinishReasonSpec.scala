package org.llm4s.llmconnect.provider

import org.llm4s.error.ContentFilteredError
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.sendJsonResponse
import org.llm4s.types.ProviderModelTypes.ProviderId
import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer
import scala.util.Using

/**
 * RFC 0009 PR 2 for `BedrockClient`: Converse's `stopReason` is mapped on `complete` and on a stream, where before
 * the client reported it raw as `Other`; a guardrail or filter stop is a `ContentFilteredError` on both paths, with
 * the usage the call was billed.
 */
class BedrockFinishReasonSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val conv = Conversation(Seq(UserMessage("Hello")))

  private def reply(stopReason: String): String =
    ujson
      .Obj(
        "output" -> ujson.Obj(
          "message" -> ujson.Obj("role" -> "assistant", "content" -> ujson.Arr(ujson.Obj("text" -> "Hel")))
        ),
        "stopReason" -> stopReason,
        "usage"      -> ujson.Obj("inputTokens" -> 9, "outputTokens" -> 3, "totalTokens" -> 12)
      )
      .render()

  private def frames(stopReason: String): Seq[Array[Byte]] = Seq(
    eventFrame("messageStart", """{"role":"assistant"}"""),
    textDelta("Hel"),
    eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
    eventFrame("messageStop", ujson.Obj("stopReason" -> stopReason).render()),
    eventFrame(
      "metadata",
      """{"usage":{"inputTokens":9,"outputTokens":3,"totalTokens":12},"metrics":{"latencyMs":1}}"""
    )
  )

  private def complete(stopReason: String) =
    withResult(sendJsonResponse(_, 200, reply(stopReason)))(_.complete(conv))

  private def stream(stopReason: String) = {
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = withResult(sendEventStream(_, frames(stopReason)))(_.streamComplete(conv)(chunks += _))
    (result, chunks.toList)
  }

  // One server and one client for the suite: the server answers with whatever `respond` holds. A server and a
  // client per call cost ~1 s each, because closing a client that has streamed shuts its Netty event loop down
  // gracefully, and that waits out a quiet period (#3216).
  private val respond                                       = new AtomicReference[HttpExchange => Unit](_ => ())
  @volatile private var server: Option[HttpServer]          = None
  @volatile private var sharedClient: Option[BedrockClient] = None

  override def beforeAll(): Unit = {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/", exchange => respond.get()(exchange))
    s.start()
    server = Some(s)
  }

  override def afterAll(): Unit =
    Using.resource(new AutoCloseable { override def close(): Unit = server.foreach(_.stop(0)) })(_ =>
      sharedClient.foreach(_.close())
    )

  private def client: BedrockClient = synchronized {
    sharedClient.getOrElse {
      val port = server.getOrElse(fail("server not started")).getAddress.getPort
      val c    = new BedrockClient(config(s"http://127.0.0.1:$port"))
      sharedClient = Some(c)
      c
    }
  }

  private def withResult[A](answer: HttpExchange => Unit)(call: BedrockClient => A): A = {
    respond.set(answer)
    call(client)
  }

  private val mapped = Seq(
    "end_turn"                      -> FinishReason.Stop,
    "stop_sequence"                 -> FinishReason.Stop,
    "max_tokens"                    -> FinishReason.Length,
    "model_context_window_exceeded" -> FinishReason.Length,
    "brand_new"                     -> FinishReason.Other("brand_new")
  )

  "BedrockClient" should {

    "map the stopReason on complete and on a stream" in {
      mapped.foreach { (raw, reason) =>
        withClue(raw) {
          complete(raw).map(_.finishReason) shouldBe Right(reason)
          val (result, chunks) = stream(raw)
          result.map(_.finishReason) shouldBe Right(reason)
          chunks.flatMap(_.finishReason) shouldBe List(reason)
        }
      }
    }

    "fail a guardrail or filter stop with a ContentFilteredError carrying the usage, on both paths" in {
      Seq("guardrail_intervened", "content_filtered").foreach { raw =>
        withClue(raw) {
          val (streamed, chunks) = stream(raw)
          Seq(complete(raw), streamed).foreach {
            case Left(e: ContentFilteredError) =>
              e.provider shouldBe ProviderId("bedrock")
              e.reason shouldBe raw
              e.message should include("after 3 characters")
              e.usage.map(_.totalTokens) shouldBe Some(12)
            case other => fail(s"expected a ContentFilteredError, got $other")
          }
          chunks.flatMap(_.contentDelta).mkString shouldBe "Hel" // delivered before the stop, stays delivered
          chunks.flatMap(_.finishReason) shouldBe empty
        }
      }
    }
  }
}
