package org.llm4s.trace

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ FileTracingConfig, TracingSettings }
import org.llm4s.llmconnect.model.{ AssistantMessage, TokenUsage, UsageSummary, UserMessage }
import org.llm4s.trace.spi.TracingBackends
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.time.Instant
import java.util.concurrent.{ Executors, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The file tracing mode (#2948): each placed trace appended to `<dir>/<trace-id>.jsonl`, every unplaced event to one
 * shared `unplaced-...jsonl` per tracing instance, each line an OTLP/JSON `ExportTraceServiceRequest`.
 */
class FileTracingSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  private val submitter = TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
  private val t0        = Instant.parse("2026-10-21T10:00:00Z")
  private val Secret    = "sk-abcdefghijklmnopqrstuvwxyz0123456789ABCD"

  private def withDir(body: Path => Unit): Unit =
    val dir = Files.createTempDirectory("llm4s-file-tracing")
    try body(dir)
    finally
      Files.walk(dir).iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p))

  private def withTracing(captureContent: Boolean = false)(body: (OpenTelemetryTracing, Path) => Unit): Unit =
    withDir { dir =>
      val tracing = OpenTelemetryTracing
        .toFiles(
          FileTracingConfig(dir.resolve("traces")).withServiceName("file-spec").withCaptureContent(captureContent)
        )
        .value
      try body(tracing, dir.resolve("traces"))
      finally tracing.close()
    }

  private def files(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else Files.list(dir).iterator().asScala.toVector.sortBy(_.getFileName.toString)

  private def names(dir: Path): Set[String] = files(dir).map(_.getFileName.toString).toSet

  private def lines(file: Path): Vector[ujson.Value] =
    val text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    text should endWith("\n")
    text.split("\n").toVector.map(ujson.read(_))

  private def spans(line: ujson.Value): Vector[ujson.Value] =
    line("resourceSpans").arr.toVector.flatMap(_("scopeSpans").arr.toVector.flatMap(_("spans").arr.toVector))

  private def attribute(span: ujson.Value, key: String): Option[ujson.Value] =
    span.obj.get("attributes").flatMap(_.arr.find(_("key").str == key)).map(_("value"))

  private def agentEvent(name: String, payload: ujson.Obj, span: TraceSpan, at: Instant): TraceEvent.CustomEvent =
    TraceEvent.CustomEvent(
      name,
      ujson.Obj("threadId" -> "thread-1", "runId" -> "run-1", "name" -> name, "version" -> 1, "payload" -> payload),
      at,
      Some(span)
    )

  private def placed(at: TraceContext, parent: Option[TraceContext]): TraceEvent.CustomEvent =
    TraceEvent.CustomEvent("graph.task_completed", ujson.Obj(), t0.plusSeconds(1), Some(TraceSpan(at, parent, t0)))

  "The file backend" should {

    "be discovered for the file mode through its services entry" in {
      val backends = TracingBackends.discover(getClass.getClassLoader)
      backends.find(TracingMode.fromString("file")).value.map(_.getClass) shouldBe Some(classOf[FileTracingBackend])
      backends.failures shouldBe empty
      new FileTracingBackend().mode shouldBe FileTracingConfig.Mode
    }

    "be what Tracing.fromSettings builds for file, writing to the configured directory" in withDir { dir =>
      val target  = dir.resolve("nested").resolve("traces")
      val tracing = Tracing.fromSettings(TracingSettings(FileTracingConfig.Mode, Map("dir" -> target.toString))).value
      try
        tracing shouldBe an[OpenTelemetryTracing]
        Files.isDirectory(target) shouldBe true
        tracing.traceEvent(placed(TraceContext.rootFor("run-x"), None)) shouldBe Right(())
        names(target) shouldBe Set(s"${TraceContext.rootFor("run-x").traceId}.jsonl")
      finally tracing.close()
    }

    "return a Left, not throw, when the directory is a file" in withDir { dir =>
      val file = Files.write(dir.resolve("not-a-dir"), Array[Byte](1))
      val viaBackend =
        new FileTracingBackend().create(TracingSettings(FileTracingConfig.Mode, Map("dir" -> file.toString)))
      viaBackend.left.value shouldBe a[ConfigurationError]
      viaBackend.left.value.message should include("not-a-dir")
      OpenTelemetryTracing.toFiles(FileTracingConfig(file)).left.value shouldBe a[ConfigurationError]
    }

    "return a Left when the directory cannot be created under a file" in withDir { dir =>
      val file = Files.write(dir.resolve("blocker"), Array[Byte](1))
      OpenTelemetryTracing.toFiles(FileTracingConfig(file.resolve("traces"))).left.value shouldBe a[ConfigurationError]
    }
  }

  "File tracing" should {

    "write a run's span tree to one file named by its trace id, as OTLP JSON lines" in withTracing() { (tracing, dir) =>
      val parentRun = submitter.spanFor("run:parent")
      val toolCall  = parentRun.spanFor("tool:call-1")
      val modelCall = parentRun.spanFor("event:3")
      val childRun  = toolCall.spanFor("run:child")

      tracing.traceEvent(
        agentEvent(
          "agent.model_call_completed",
          ujson.Obj(
            "agent"     -> "lead",
            "model"     -> "gpt-x",
            "attempts"  -> 1,
            "toolCalls" -> 1,
            "usage"     -> ujson.Obj("promptTokens" -> 12, "completionTokens" -> 3, "totalTokens" -> 15)
          ),
          TraceSpan(modelCall, Some(parentRun), t0.plusSeconds(1)),
          t0.plusSeconds(1)
        )
      )
      tracing.traceEvent(
        TraceEvent.AgentRunEnded(
          "thread-1/~task/researcher/abc",
          "child-run",
          "researcher",
          TraceEvent.RunStatus.Completed,
          Seq(UserMessage("q"), AssistantMessage("a")),
          UsageSummary().add(ModelName("gpt-x"), TokenUsage(5, 2, 7), None),
          t0.plusSeconds(4),
          parentThreadId = Some("thread-1"),
          parentRunId = Some("parent-run"),
          span = Some(TraceSpan(childRun, Some(toolCall), t0.plusSeconds(2)))
        )
      )
      tracing.traceEvent(
        agentEvent(
          "agent.tool_executed",
          ujson.Obj("agent" -> "lead", "toolCallId" -> "call-1", "tool" -> "task", "duration" -> 3000),
          TraceSpan(toolCall, Some(parentRun), t0.plusSeconds(2)),
          t0.plusSeconds(5)
        )
      )
      tracing.traceEvent(
        TraceEvent.AgentRunEnded(
          "thread-1",
          "parent-run",
          "lead",
          TraceEvent.RunStatus.Failed,
          Seq(UserMessage("q")),
          UsageSummary(),
          t0.plusSeconds(6),
          span = Some(TraceSpan(parentRun, Some(submitter), t0))
        )
      )

      // written as each span ends, before close: nothing waits in a buffer
      names(dir) shouldBe Set(s"${submitter.traceId}.jsonl")
      val written = lines(dir.resolve(s"${submitter.traceId}.jsonl"))
      written should have size 4

      written.foreach { line =>
        val resource = line("resourceSpans")(0)("resource")
        attribute(resource, "service.name").map(_("stringValue").str) shouldBe Some("file-spec")
        line("resourceSpans")(0)("scopeSpans")(0)("scope")("name").str shouldBe "org.llm4s"
      }
      val byName = written.flatMap(spans).map(s => s("name").str -> s).toMap
      byName.keySet shouldBe Set("chat gpt-x", "invoke_agent researcher", "execute_tool task", "invoke_agent lead")
      byName.values.map(_("traceId").str).toSet shouldBe Set(submitter.traceId)

      val lead = byName("invoke_agent lead")
      lead("spanId").str shouldBe parentRun.spanId
      lead("parentSpanId").str shouldBe submitter.spanId
      lead("status")("code").num shouldBe 2 // STATUS_CODE_ERROR, an integer as OTLP/JSON writes enums
      lead("startTimeUnixNano").str shouldBe TimeUnit.SECONDS.toNanos(t0.getEpochSecond).toString

      val chat = byName("chat gpt-x")
      chat("parentSpanId").str shouldBe parentRun.spanId
      chat("kind").num shouldBe 3 // SPAN_KIND_CLIENT
      attribute(chat, "gen_ai.operation.name").map(_("stringValue").str) shouldBe Some("chat")
      attribute(chat, "gen_ai.request.model").map(_("stringValue").str) shouldBe Some("gpt-x")
      attribute(chat, "gen_ai.usage.input_tokens").map(_("intValue").str) shouldBe Some("12")

      byName("execute_tool task")("spanId").str shouldBe toolCall.spanId
      byName("execute_tool task")("parentSpanId").str shouldBe parentRun.spanId
      byName("invoke_agent researcher")("spanId").str shouldBe childRun.spanId
      byName("invoke_agent researcher")("parentSpanId").str shouldBe toolCall.spanId
    }

    "give two traces two files" in withTracing() { (tracing, dir) =>
      val a = TraceContext.rootFor("run-a")
      val b = TraceContext.rootFor("run-b")
      tracing.traceEvent(placed(a, None))
      tracing.traceEvent(placed(b, None))
      tracing.traceEvent(placed(a.spanFor("x"), Some(a)))

      names(dir) shouldBe Set(s"${a.traceId}.jsonl", s"${b.traceId}.jsonl")
      lines(dir.resolve(s"${a.traceId}.jsonl")) should have size 2
      lines(dir.resolve(s"${b.traceId}.jsonl")) should have size 1
    }

    "write every unplaced event of one tracing to one shared file, each span keeping its own trace id" in withTracing() {
      (tracing, dir) =>
        tracing.traceEvent(TraceEvent.CustomEvent("x", ujson.Obj()))
        tracing.traceEvent(TraceEvent.ToolExecuted("t", "in", "out", 3.millis, success = true))
        tracing.traceEvent(TraceEvent.CustomEvent("y", ujson.Obj()))

        val written = files(dir)
        written should have size 1
        val name = written.head.getFileName.toString
        (name should fullyMatch).regex("unplaced-\\d{8}T\\d{6}Z-[0-9a-f]{8}\\.jsonl")
        val spanList = lines(written.head).flatMap(spans)
        spanList.map(_("name").str) shouldBe Vector("x", "execute_tool t", "y")
        spanList.map(_("traceId").str).distinct should have size 3
    }

    "write placed traces to their own files and unplaced events to the shared one, when mixed" in withTracing() {
      (tracing, dir) =>
        val a = TraceContext.rootFor("run-a")
        tracing.traceEvent(TraceEvent.CustomEvent("x", ujson.Obj()))
        tracing.traceEvent(placed(a, None))
        tracing.traceEvent(TraceEvent.CustomEvent("y", ujson.Obj()))
        tracing.traceEvent(placed(a.spanFor("z"), Some(a)))

        val all = names(dir)
        all should have size 2
        all should contain(s"${a.traceId}.jsonl")
        val unplaced = all.find(_.startsWith("unplaced-")).value
        lines(dir.resolve(unplaced)).flatMap(spans).map(_("name").str) shouldBe Vector("x", "y")
        lines(dir.resolve(s"${a.traceId}.jsonl")) should have size 2
    }

    "give two tracing instances two unplaced files" in withDir { dir =>
      val first  = OpenTelemetryTracing.toFiles(FileTracingConfig(dir)).value
      val second = OpenTelemetryTracing.toFiles(FileTracingConfig(dir)).value
      try
        first.traceEvent(TraceEvent.CustomEvent("x", ujson.Obj()))
        second.traceEvent(TraceEvent.CustomEvent("x", ujson.Obj()))
        names(dir).count(_.startsWith("unplaced-")) shouldBe 2
      finally
        first.close()
        second.close()
    }

    "leave message content out by default" in withTracing() { (tracing, dir) =>
      tracing.traceEvent(TraceEvent.ToolExecuted("t", s"look up $Secret", "Paris", 3.millis, success = true))
      val text = files(dir).map(p => new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).mkString
      text should include("execute_tool t")
      (text should not).include("look up")
      (text should not).include("Paris")
    }

    "record message content, redacted, with captureContent" in withTracing(captureContent = true) { (tracing, dir) =>
      tracing.traceEvent(TraceEvent.ToolExecuted("t", s"look up $Secret", "Paris", 3.millis, success = true))
      val span = lines(files(dir).loneElement).flatMap(spans).loneElement
      attribute(span, "tool.input").map(_("stringValue").str).value should include("look up")
      attribute(span, "tool.output").map(_("stringValue").str) shouldBe Some("Paris")
      (files(dir).map(p => new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).mkString should not)
        .include(Secret)
    }

    "write whole lines under concurrent exports" in withTracing() { (tracing, dir) =>
      val root    = TraceContext.rootFor("run-concurrent")
      val threads = 8
      val each    = 50
      val pool    = Executors.newFixedThreadPool(threads)
      try
        val futures = (0 until threads).map { t =>
          pool.submit(new Runnable {
            def run(): Unit =
              (0 until each).foreach { i =>
                tracing.traceEvent(placed(root.spanFor(s"span-$t-$i"), Some(root)))
                tracing.traceEvent(TraceEvent.CustomEvent(s"unplaced-$t-$i", ujson.Obj()))
              }
          })
        }
        futures.foreach(_.get(60, TimeUnit.SECONDS))
      finally pool.shutdown()

      val traceLines = lines(dir.resolve(s"${root.traceId}.jsonl"))
      traceLines should have size (threads * each).toLong
      traceLines.flatMap(spans).map(_("spanId").str).distinct should have size (threads * each).toLong
      val unplaced = files(dir).filter(_.getFileName.toString.startsWith("unplaced-")).loneElement
      lines(unplaced) should have size (threads * each).toLong
    }
  }

  extension [A](as: Vector[A])
    private def loneElement: A =
      as should have size 1
      as.head
