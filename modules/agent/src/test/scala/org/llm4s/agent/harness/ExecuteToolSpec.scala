package org.llm4s.agent.harness

import org.llm4s.agent.{ Agent, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.graph.tool.{ LockKey, Operation, ToolResource, ToolSet }
import org.llm4s.agent.graph.middleware.{ PermissionMiddleware, PermissionRule }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Stage 3 slice F2 (#1906, design §4.33): the `execute` tool over a `SandboxBackend`. */
class ExecuteToolSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  /** A runner that records each run and answers from `reply`, locking `sandbox` for every directory. */
  final private class Recording(reply: String => Result[ExecResult]) extends SandboxRunner:
    val runs                                = new CopyOnWriteArrayList[(String, String, FiniteDuration)]()
    def locks(workdir: VPath): Set[LockKey] = Set(LockKey("sandbox"))
    def run(
      command: String,
      workdir: VPath,
      timeout: FiniteDuration,
      maxOutputBytes: Int,
      ctx: FileContext
    ): Result[ExecResult] =
      runs.add((command, workdir.value, timeout)): Unit
      reply(command)

  private def ok(command: String): Result[ExecResult] = Right(ExecResult(0, s"ran $command", ""))

  private def calls(calls: (String, ujson.Obj)*): Completion =
    CompletionFixture.withMessage(
      AssistantMessage(None, calls.zipWithIndex.map { case ((_, args), i) => ToolCall(s"x$i", "execute", args) })
    )

  private def results(runner: Recording, batches: Seq[Completion], middleware: Option[PermissionMiddleware] = None) = {
    val client  = ScriptedLLMClient.of((batches :+ CompletionFixture.simple("done"))*)
    val tools   = ToolSet.of(ExecuteTool(SandboxBackend(runner))).value
    val builder = Agent.builder("runner", client).withTools(tools)
    val agent   = built(middleware.fold(builder)(builder.withMiddleware(_)))
    agent.run("go").map(_.messages.collect { case t: ToolMessage => t.content }.toVector)
  }

  "The execute tool" should "declare the command as an Execute resource, be destructive and open-world, and lock the backend's keys" in {
    val tool = ExecuteTool(SandboxBackend(new Recording(ok)))
    tool.spec.name shouldBe "execute"
    tool.spec.resourcesOf(ExecuteTool.Args("ls -la")) shouldBe Vector(ToolResource(Operation.Execute, "ls -la"))
    tool.spec.hints.destructive shouldBe true
    tool.spec.hints.openWorld shouldBe true
    tool.spec.hints.readOnly shouldBe false
    tool.spec.hints.idempotent shouldBe false
    tool.spec.locksOf.value(ExecuteTool.Args("ls", Some("/src"))) shouldBe Set(LockKey("sandbox"))
    // an invalid directory holds the root lock until the validation refuses it
    tool.spec.locksOf.value(ExecuteTool.Args("ls", Some("../x"))) shouldBe Set(LockKey(""))
    tool.spec.timeout shouldBe Some(11.minutes)
  }

  it should "run a command in its directory and give the model the exit code and both streams" in {
    val runner = new Recording(_ => Right(ExecResult(2, "out", "err")))
    val out = results(
      runner,
      Seq(calls("a" -> ujson.Obj("command" -> "make test", "workdir" -> "/src", "timeout_seconds" -> 5)))
    ).value
    out shouldBe Vector("exit code: 2\nstdout:\nout\nstderr:\nerr")
    runner.runs.asScala.toVector shouldBe Vector(("make test", "/src", 5.seconds))
  }

  it should "default the directory to / and the timeout to the default, and say when output was cut" in {
    val runner = new Recording(_ => Right(ExecResult(0, "x", "", truncated = true)))
    val out    = results(runner, Seq(calls("a" -> ujson.Obj("command" -> "ls")))).value
    out.head should include("(empty)")
    out.head should include("output cut")
    runner.runs.asScala.toVector shouldBe Vector(("ls", "/", 60.seconds))
  }

  it should "run a batch's commands one after another, in call order, when their locks conflict" in {
    val runner = new Recording(ok)
    val out = results(
      runner,
      Seq(calls("a" -> ujson.Obj("command" -> "first"), "b" -> ujson.Obj("command" -> "second")))
    ).value
    out shouldBe Vector(
      "exit code: 0\nstdout:\nran first\nstderr:\n(empty)",
      "exit code: 0\nstdout:\nran second\nstderr:\n(empty)"
    )
    runner.runs.asScala.map(_._1).toVector shouldBe Vector("first", "second")
  }

  it should "refuse an empty command, a timeout over the maximum and an invalid directory, running nothing" in {
    val runner = new Recording(ok)
    val out = results(
      runner,
      Seq(
        calls("a" -> ujson.Obj("command" -> "  ")),
        calls("a" -> ujson.Obj("command" -> "ls", "timeout_seconds" -> 100000)),
        calls("a" -> ujson.Obj("command" -> "ls", "workdir" -> "relative"))
      )
    ).value
    out.size shouldBe 3
    out.foreach(_ should not include "exit code")
    runner.runs.size shouldBe 0
  }

  it should "give the model a sandbox's refusal, and fail the run on a failure whose outcome is unknown" in {
    val refusing = new Recording(c => Left(BackendRefused("execute", c, "not on the allowlist")))
    results(refusing, Seq(calls("a" -> ujson.Obj("command" -> "curl x")))).value.head should include(
      "not on the allowlist"
    )
    val failing = new Recording(_ => Left(ProcessingError("workspace.execute", "connection lost")))
    results(failing, Seq(calls("a" -> ujson.Obj("command" -> "ls")))).left.value.message should include(
      "connection lost"
    )
  }

  it should "be matched by a permission rule on its command" in {
    val runner = new Recording(ok)
    val rules = new PermissionMiddleware(
      Seq(PermissionRule.deny(Operation.Execute, "rm *"), PermissionRule.allow(Operation.Execute, "*"))
    )
    val out = results(
      runner,
      Seq(calls("a" -> ujson.Obj("command" -> "rm -rf /"), "b" -> ujson.Obj("command" -> "ls"))),
      Some(rules)
    ).value
    out.head should include("Denied: execute rm -rf /")
    runner.runs.asScala.map(_._1).toVector shouldBe Vector("ls")
  }

  "ExecuteTool" should "refuse timeouts that are not positive or a default over the maximum" in {
    val backend = SandboxBackend(new Recording(ok))
    an[IllegalArgumentException] should be thrownBy ExecuteTool(backend, defaultTimeout = Duration.Zero)
    an[IllegalArgumentException] should be thrownBy ExecuteTool(backend, 2.minutes, 1.minute)
  }
}
