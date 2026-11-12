package org.llm4s.spring

import org.llm4s.error.ServiceError
import org.llm4s.javaapi.{ JLlmClient, JLlmClientTestFactory, LlmException }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.{ Bean, Configuration }
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

import java.io.IOException
import java.time.{ Duration, Instant }
import java.util.concurrent.{ CopyOnWriteArrayList, Flow }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong }
import java.util.function.Consumer
import scala.jdk.CollectionConverters._

object Llm4sStreamingWebSpec {

  val Pieces: Seq[String] = Seq("Hel", "lo", "!")

  /** A model that streams [[Pieces]], or fails when the query is `fail`. */
  val streaming: LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      streamComplete(c, o, _ => ())
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      if (c.messages.lastOption.exists(_.content == "fail")) Left(ServiceError(503, "scripted", "the model is down"))
      else {
        Pieces.foreach(p => f(StreamedChunk(id = "c", content = Some(p))))
        Right(Completion("id", Instant.EPOCH, Pieces.mkString, "m", AssistantMessage(Pieces.mkString)))
      }
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  @Configuration
  class StreamingClientConfig {
    @Bean
    def llm4sClient(): JLlmClient = JLlmClientTestFactory.create(streaming)
  }

  /** An emitter whose sends fail, as one whose client has gone, and whose end callbacks the spec can run. */
  final class GoneEmitter extends SseEmitter {
    val ends: CopyOnWriteArrayList[Runnable]                  = new CopyOnWriteArrayList[Runnable]()
    @volatile var errorCallback: Consumer[Throwable]          = _
    override def send(o: Object): Unit                        = throw new IOException("client gone")
    override def onCompletion(callback: Runnable): Unit       = ends.add(callback)
    override def onTimeout(callback: Runnable): Unit          = ends.add(callback)
    override def onError(callback: Consumer[Throwable]): Unit = errorCallback = callback
  }
}

/**
 * The streaming templates in a web application (#2279): the guide's WebFlux controller (`ChatStreamController`, returning
 * `ReactiveLLM4STemplate`'s `Flux`) and Spring MVC controller (`ChatSseController`, returning `SseLLM4STemplate`'s
 * `SseEmitter`), each over the auto-configured bean, stream the model's pieces to the HTTP client as server-sent events.
 */
class Llm4sStreamingWebSpec extends AnyFlatSpec with Matchers {
  import Llm4sStreamingWebSpec._

  private val runner = new ApplicationContextRunner()
    .withConfiguration(
      AutoConfigurations.of(
        classOf[Llm4sAutoConfiguration],
        classOf[Llm4sReactiveAutoConfiguration],
        classOf[Llm4sSseAutoConfiguration]
      )
    )
    .withUserConfiguration(classOf[StreamingClientConfig])

  "The guide's WebFlux controller" should "stream the reply's pieces to the client as server-sent events" in {
    runner.run { ctx =>
      val web = WebTestClient
        .bindToController(new ChatStreamController(ctx.getBean(classOf[ReactiveLLM4STemplate])))
        .configureClient()
        .responseTimeout(Duration.ofSeconds(10))
        .build()
      val result = web
        .get()
        .uri("/chat/stream?q=hi")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .exchange()
        .expectStatus()
        .isOk
        .returnResult(classOf[String])
      result.getResponseHeaders.getContentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM) shouldBe true
      result.getResponseBody.collectList().block(Duration.ofSeconds(10)).asScala shouldBe Pieces
    }
  }

  "ReactiveLLM4STemplate" should "stream a conversation's reply too" in {
    runner.run { ctx =>
      val reply = ctx.getBean(classOf[ReactiveLLM4STemplate]).stream(Conversation(Seq(UserMessage("hi"))))
      reply.collectList().block(Duration.ofSeconds(10)).asScala shouldBe Pieces
    }
  }

  "The guide's Spring MVC controller" should "stream the reply's pieces to the client as server-sent events" in {
    runner.run { ctx =>
      val mvc = MockMvcBuilders.standaloneSetup(new ChatSseController(ctx.getBean(classOf[SseLLM4STemplate]))).build()
      val started = mvc.perform(get("/chat/sse").param("q", "hi")).andExpect(request().asyncStarted()).andReturn()
      started.getAsyncResult(10000)
      started.getResponse.getContentType should startWith(MediaType.TEXT_EVENT_STREAM_VALUE)
      started.getResponse.getContentAsString shouldBe Pieces.map(p => s"data:$p\n\n").mkString
    }
  }

  it should "end the stream with the provider's error when the call fails" in {
    runner.run { ctx =>
      val mvc = MockMvcBuilders.standaloneSetup(new ChatSseController(ctx.getBean(classOf[SseLLM4STemplate]))).build()
      val started = mvc.perform(get("/chat/sse").param("q", "fail")).andExpect(request().asyncStarted()).andReturn()
      started.getAsyncResult(10000) match {
        case e: LlmException => e.getMessage should include("the model is down")
        case other           => fail(s"expected an LlmException, got $other")
      }
    }
  }

  "SseLLM4STemplate" should "stream a conversation's reply too" in {
    runner.run { ctx =>
      val template = ctx.getBean(classOf[SseLLM4STemplate])
      val mvc = MockMvcBuilders
        .standaloneSetup(new ChatSseController(template) {
          override def stream(question: String): SseEmitter = template.stream(Conversation(Seq(UserMessage(question))))
        })
        .build()
      val started = mvc.perform(get("/chat/sse").param("q", "hi")).andExpect(request().asyncStarted()).andReturn()
      started.getAsyncResult(10000)
      started.getResponse.getContentAsString shouldBe Pieces.map(p => s"data:$p\n\n").mkString
    }
  }

  it should "cancel the provider call when a send fails or the emitter ends first" in {
    val emitter   = new GoneEmitter
    val forward   = new SseLLM4STemplate.Forward(emitter)
    val cancelled = new AtomicBoolean(false)
    val requested = new AtomicLong(0)
    forward.onSubscribe(new Flow.Subscription {
      override def request(n: Long): Unit = requested.set(n)
      override def cancel(): Unit         = cancelled.set(true)
    })
    requested.get() shouldBe Long.MaxValue
    forward.onNext("Hel")
    cancelled.get() shouldBe true

    emitter.ends should have size 2
    emitter.ends.asScala.foreach { end =>
      cancelled.set(false)
      end.run()
      cancelled.get() shouldBe true
    }
    cancelled.set(false)
    emitter.errorCallback.accept(new IOException("reset"))
    cancelled.get() shouldBe true
  }
}
