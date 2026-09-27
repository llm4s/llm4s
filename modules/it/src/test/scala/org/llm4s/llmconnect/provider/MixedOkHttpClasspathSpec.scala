package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.{ Channel, ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer }
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http2._
import io.netty.util.ReferenceCountUtil
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.{ SimpleSpanProcessor, SpanExporter }
import org.llm4s.it.tags.Local
import org.llm4s.llmconnect.config.{ AnthropicConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.{ OpenTelemetryTracing, TraceEvent }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ ConcurrentLinkedQueue, TimeUnit }
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * Guards the mixed OkHttp classpath recorded above `lazy val it` in `build.sbt` (#1132).
 *
 * `anthropic-java` and `openai-java` bring `com.squareup.okhttp3:okhttp:4.x`; OpenTelemetry's
 * OTLP exporters bring `com.squareup.okhttp3:okhttp-jvm:5.x`. The artifact names differ, so
 * neither evicts the other and both jars supply package `okhttp3`: whichever is first on the
 * classpath wins every class they share. That is what a user combining `llm4s-anthropic` or
 * `llm4s-openai` with `llm4s-trace-opentelemetry` gets, and `modules/it` is the one project that
 * puts all three on one classpath.
 *
 * Each case drives a real client end to end - through its OkHttp transport - against an
 * in-process server, so an SDK or OpenTelemetry bump that starts calling an OkHttp API the
 * winning jar lacks fails here as a `NoSuchMethodError` / `NoClassDefFoundError` rather than
 * in a user's application. Nothing external is needed, hence the `Local` tier.
 */
@Local
class MixedOkHttpClasspathSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = ModelRegistryService.default().value
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  /** Which jar supplies `okhttp3.OkHttpClient`, reported so a failure shows the mix it ran on. */
  private def okHttpOrigin: String = {
    val cls = Class.forName("okhttp3.OkHttpClient")
    val version =
      scala.util.Try(Class.forName("okhttp3.OkHttp").getField("VERSION").get(null)).toOption.fold("?")(_.toString)
    s"okhttp3.OkHttpClient $version from ${Option(cls.getProtectionDomain.getCodeSource).map(_.getLocation).orNull}"
  }

  private def bracket[R, A](acquire: => R)(release: R => Unit)(use: R => A): A =
    Using.resource(acquire)(use)(using (r: R) => release(r))

  private def withHttp(path: String, contentType: String)(write: java.io.OutputStream => Unit)(
    test: (String, ConcurrentLinkedQueue[String]) => Any
  ): Unit = {
    val seen = new ConcurrentLinkedQueue[String]()
    bracket(HttpServer.create(new InetSocketAddress("localhost", 0), 0))(_.stop(0)) { server =>
      server.createContext(
        path,
        (exchange: HttpExchange) => {
          seen.add(s"${exchange.getRequestMethod} ${exchange.getRequestURI.getPath}")
          exchange.getRequestBody.readAllBytes()
          exchange.getResponseHeaders.add("Content-Type", contentType)
          exchange.sendResponseHeaders(200, 0L)
          val os = exchange.getResponseBody
          write(os)
          os.close()
        }
      )
      server.start()
      test(s"http://localhost:${server.getAddress.getPort}", seen)
      ()
    }
  }

  private def withJson(path: String, body: String)(test: (String, ConcurrentLinkedQueue[String]) => Any): Unit =
    withHttp(path, "application/json")(_.write(body.getBytes(StandardCharsets.UTF_8)))(test)

  private def withSse(path: String, events: Seq[String])(test: String => Any): Unit =
    withHttp(path, "text/event-stream") { os =>
      events.foreach { ev =>
        os.write((ev + "\n\n").getBytes(StandardCharsets.UTF_8))
        os.flush()
      }
    }((base, _) => test(base))

  /** A minimal h2c gRPC server that answers every call with an empty message and grpc-status 0. */
  private def withGrpc(test: (Int, ConcurrentLinkedQueue[String]) => Any): Unit = {
    val seen = new ConcurrentLinkedQueue[String]()
    class Handler extends ChannelInboundHandlerAdapter {
      private def respond(ctx: ChannelHandlerContext, stream: Http2FrameStream): Unit = {
        ctx.write(
          new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200").add("content-type", "application/grpc"))
            .stream(stream)
        )
        ctx.write(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(Array[Byte](0, 0, 0, 0, 0)), false).stream(stream))
        ctx.writeAndFlush(
          new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().add("grpc-status", "0"), true).stream(stream)
        )
        ()
      }
      override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = msg match {
        case h: Http2HeadersFrame =>
          seen.add(s"${h.headers().method()} ${h.headers().path()}")
          if (h.isEndStream) respond(ctx, h.stream())
        case d: Http2DataFrame =>
          val end    = d.isEndStream
          val stream = d.stream()
          d.release()
          if (end) respond(ctx, stream)
        case other =>
          ReferenceCountUtil.release(other)
          ()
      }
    }
    bracket(new NioEventLoopGroup(1))(_.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync()) { group =>
      val bind: () => Channel = () =>
        new ServerBootstrap()
          .group(group)
          .channel(classOf[NioServerSocketChannel])
          .childHandler(new ChannelInitializer[SocketChannel] {
            override def initChannel(c: SocketChannel): Unit = {
              c.pipeline().addLast(Http2FrameCodecBuilder.forServer().build(), new Handler)
              ()
            }
          })
          .bind("localhost", 0)
          .sync()
          .channel()
      bracket(bind())(_.close().sync()) { channel =>
        test(channel.localAddress().asInstanceOf[InetSocketAddress].getPort, seen)
        ()
      }
    }
  }

  /** Exports one span through `exporter` and returns the export's own result. */
  private def exportOneSpan(exporter: SpanExporter): CompletableResultCode = {
    val results = new ConcurrentLinkedQueue[CompletableResultCode]()
    val capturing = new SpanExporter {
      override def `export`(spans: java.util.Collection[SpanData]): CompletableResultCode = {
        val r = exporter.`export`(spans)
        results.add(r)
        r
      }
      override def flush(): CompletableResultCode    = exporter.flush()
      override def shutdown(): CompletableResultCode = exporter.shutdown()
    }
    val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(capturing)).build()
    provider.get("okhttp-classpath").spanBuilder("probe-span").startSpan().end()
    val result = results.asScala.head
    result.join(10, TimeUnit.SECONDS)
    provider.shutdown().join(10, TimeUnit.SECONDS)
    result
  }

  "AnthropicClient" should "complete over OkHttp on the mixed classpath" in {
    info(okHttpOrigin)
    val body =
      """{"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-sonnet-latest",
        |"content":[{"type":"text","text":"hi from fake anthropic"}],"stop_reason":"end_turn",
        |"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":4}}""".stripMargin
    withJson("/v1/messages", body) { (base, seen) =>
      val client = AnthropicConfig.fromValues("claude-3-5-sonnet-latest", "k", base).flatMap(AnthropicClient(_)).value
      val result = client.complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
      withClue(okHttpOrigin) {
        result.map(_.content) shouldBe Right("hi from fake anthropic")
        seen.asScala.toList shouldBe List("POST /v1/messages")
      }
    }
  }

  it should "stream over OkHttp on the mixed classpath" in {
    val events = Seq(
      """event: message_start
        |data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-sonnet-latest","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":3,"output_tokens":1}}}""".stripMargin,
      """event: content_block_start
        |data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""".stripMargin,
      """event: content_block_delta
        |data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi "}}""".stripMargin,
      """event: content_block_delta
        |data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"stream"}}""".stripMargin,
      """event: content_block_stop
        |data: {"type":"content_block_stop","index":0}""".stripMargin,
      """event: message_delta
        |data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}""".stripMargin,
      """event: message_stop
        |data: {"type":"message_stop"}""".stripMargin
    )
    withSse("/v1/messages", events) { base =>
      val client = AnthropicConfig.fromValues("claude-3-5-sonnet-latest", "k", base).flatMap(AnthropicClient(_)).value
      val result = client.streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
      withClue(okHttpOrigin)(result.map(_.content) shouldBe Right("hi stream"))
    }
  }

  "OpenAIClient" should "complete over OkHttp on the mixed classpath" in {
    val body =
      """{"id":"chatcmpl-1","object":"chat.completion","created":1700000000,"model":"gpt-4o-mini",
        |"choices":[{"index":0,"message":{"role":"assistant","content":"hi from fake openai","refusal":null},
        |"logprobs":null,"finish_reason":"stop"}],
        |"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}""".stripMargin
    withJson("/v1/chat/completions", body) { (base, seen) =>
      val client = OpenAIConfig.fromValues("gpt-4o-mini", "k", None, s"$base/v1").flatMap(OpenAIClient(_)).value
      val result = client.complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
      withClue(okHttpOrigin) {
        result.map(_.content) shouldBe Right("hi from fake openai")
        seen.asScala.toList shouldBe List("POST /v1/chat/completions")
      }
    }
  }

  it should "stream over OkHttp on the mixed classpath" in {
    val events = Seq(
      """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"hi "},"finish_reason":null}]}""",
      """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"stream"},"finish_reason":null}]}""",
      """data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
      "data: [DONE]"
    )
    withSse("/v1/chat/completions", events) { base =>
      val client = OpenAIConfig.fromValues("gpt-4o-mini", "k", None, s"$base/v1").flatMap(OpenAIClient(_)).value
      val result = client.streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
      withClue(okHttpOrigin)(result.map(_.content) shouldBe Right("hi stream"))
    }
  }

  "The OTLP gRPC span exporter" should "export over OkHttp on the mixed classpath" in {
    withGrpc { (port, seen) =>
      val result = exportOneSpan(OtlpGrpcSpanExporter.builder().setEndpoint(s"http://localhost:$port").build())
      withClue(s"$okHttpOrigin; failure=${Option(result.getFailureThrowable)}") {
        result.isSuccess shouldBe true
        seen.asScala.toList should contain("POST /opentelemetry.proto.collector.trace.v1.TraceService/Export")
      }
    }
  }

  "The OTLP HTTP span exporter" should "export over OkHttp on the mixed classpath" in {
    withJson("/v1/traces", "") { (base, seen) =>
      val result = exportOneSpan(OtlpHttpSpanExporter.builder().setEndpoint(s"$base/v1/traces").build())
      withClue(s"$okHttpOrigin; failure=${Option(result.getFailureThrowable)}") {
        result.isSuccess shouldBe true
        seen.asScala.toList shouldBe List("POST /v1/traces")
      }
    }
  }

  "OpenTelemetryTracing" should "flush a span to a gRPC collector on shutdown" in {
    withGrpc { (port, seen) =>
      val tracing = new OpenTelemetryTracing("okhttp-classpath", s"http://localhost:$port", Map.empty)
      tracing.traceEvent(TraceEvent.CustomEvent("probe", ujson.Obj("k" -> "v"))) shouldBe Right(())
      tracing.shutdown()
      withClue(okHttpOrigin) {
        seen.asScala.toList should contain("POST /opentelemetry.proto.collector.trace.v1.TraceService/Export")
      }
    }
  }
}
