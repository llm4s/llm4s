package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.*
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.agent.graph.toolloop.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.time.Clock
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.time.Instant

/**
 * Stage 3 slice C1 (#1902, design §4.22) on a durable store: every hook update, and the prior values an output
 * Block restores, survive a SQLite checkpoint, a closed store and `recover` in a new runtime.
 */
class SqliteKernelHooksSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  final case class Args(text: String) derives ReadWriter

  private val agent = AgentId.unsafe("a")
  private val notes = StateKey.appending[String]("notes")
  private val files = StateKey.replace[Map[String, String]]("files", Map.empty)

  private val write = AgentTool(
    AgentToolSpec[Args](
      "write",
      "writes a file",
      Schema.`object`[Args]("args").withRequiredField("text", Schema.string("t"))
    ),
    writes = Set(files)
  ) { (args, context) =>
    val current = context.state.get(files).getOrElse(Map.empty)
    ToolOutcome.Success(
      ToolContent.Json(ujson.Str("ok")),
      StateUpdate.update(files, current + (args.text -> args.text))
    )
  }

  /** Notes each hook in `notes`, and blocks an answer that mentions a secret. */
  private val noting = new AgentMiddleware {
    val id: MiddlewareId                     = MiddlewareId("noting")
    override def writes: Set[StateKey[?, ?]] = Set(notes)
    override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
      Right(BoundaryResult(input, StateUpdate.update(notes, s"before:$input")))
    override def wrapModelCall(request: ModelRequest, context: ModelContext)(
      next: ModelRequest => Result[ModelResponse]
    ): Result[ModelResponse] = next(request).map(_.addUpdate(StateUpdate.update(notes, "model")))
    override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
      if answer.contains("secret") then Left(GuardrailBlocked("secrets", "no"))
      else Right(BoundaryResult(answer, StateUpdate.update(notes, "after")))
  }

  private def completion(message: AssistantMessage): Completion =
    Completion("c", Instant.EPOCH, message.content, "m", message, message.toolCalls.toList, Some(TokenUsage(10, 5, 15)))

  /** Plays `turns`; the call numbered `failOn` fails while `failing` is set. */
  final private class Model(failing: AtomicBoolean, failOn: Int, turns: AssistantMessage*) extends ModelStep {
    private val calls = new AtomicInteger(0)
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      val n = calls.getAndIncrement()
      if failing.get && n == failOn then
        failing.set(false)
        Left(ValidationError("model", "provider down"))
      else turns.lift(n).map(m => Right(completion(m))).getOrElse(Left(ValidationError("model", s"no turn $n")))
    }
  }

  private def loopOf(model: ModelStep): ToolLoop =
    ToolLoop
      .build("hooks", "1", agent, Vector(LoopAgent(agent, model, ToolSet.of(write).value).withMiddleware(Seq(noting))))
      .value

  private def result[O](handle: Result[RunHandle[O]]): RunResult[O] = handle.flatMap(_.await()).value

  "Hook updates" should "survive a SQLite checkpoint and a recover in a new runtime" in {
    val db      = SqliteFiles.fresh()
    val thread  = ThreadId("hooks-recover")
    val failing = new AtomicBoolean(true)
    val calling = AssistantMessage(None, Seq(ToolCall("c1", "write", ujson.Obj("text" -> "one"))))
    // the second model call, after the tool batch, fails once
    val loop  = loopOf(new Model(failing, 1, calling, AssistantMessage("done"), AssistantMessage("done")))
    val first = SqliteFiles.opened(db, Clock.systemUTC())
    result(GraphRuntime(first).start(thread, loop.graph, AgentInput("go"))) shouldBe a[RunResult.Failed]
    first.close()

    val second = SqliteFiles.opened(db, Clock.systemUTC())
    val stored = loop.graph.restore(second.latest(thread).value.value.checkpoint.snapshot).value.state
    stored.get(notes).value shouldBe Vector("before:go", "model")
    stored.get(files).value shouldBe Map("one" -> "one")
    // the open turn's prior values round-trip through the store's codec
    stored.get(LoopKeys.turn).value.priorValues.keySet shouldBe Set("notes", "files")

    result(GraphRuntime(second).recover(thread, loop.graph)) match {
      case RunResult.Completed(state, _, _) =>
        state.get(notes).value shouldBe Vector("before:go", "model", "model", "after")
        state.get(files).value shouldBe Map("one" -> "one")
        state.get(LoopKeys.turn).value.priorValues shouldBe empty
      case other => fail(s"not completed: $other")
    }
    second.close()
  }

  "An output Block" should "restore the turn's keys from prior values read back from SQLite" in {
    val db      = SqliteFiles.fresh()
    val thread  = ThreadId("hooks-block")
    val failing = new AtomicBoolean(true)
    val calling = AssistantMessage(None, Seq(ToolCall("c1", "write", ujson.Obj("text" -> "two"))))
    val loop    = loopOf(new Model(failing, 1, calling, AssistantMessage("a secret"), AssistantMessage("a secret")))
    val first   = SqliteFiles.opened(db, Clock.systemUTC())
    result(GraphRuntime(first).start(thread, loop.graph, AgentInput("go"))) shouldBe a[RunResult.Failed]
    first.close()

    val second = SqliteFiles.opened(db, Clock.systemUTC())
    result(GraphRuntime(second).recover(thread, loop.graph)) match {
      case RunResult.Failed(state, error) =>
        error shouldBe a[GuardrailBlocked]
        state.isSet(notes) shouldBe false
        state.isSet(files) shouldBe false
        state.get(LoopKeys.usage).value.requestCount shouldBe 2
      case other => fail(s"not blocked: $other")
    }
    val closing = loop.graph.restore(second.latest(thread).value.value.checkpoint.snapshot).value.state
    closing.isSet(files) shouldBe false
    second.close()
  }
}
