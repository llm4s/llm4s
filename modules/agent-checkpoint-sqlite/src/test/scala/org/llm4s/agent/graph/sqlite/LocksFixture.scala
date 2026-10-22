package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.agent.graph.{ GraphRuntime, GraphSnapshot, InterruptId, StateKey, StateUpdate, ThreadId }
import org.llm4s.agent.graph.toolloop.ApprovalDecision
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, LockKey, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.nio.file.{ Files, Path, StandardCopyOption }
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant

/**
 * The agent of `locks-threads.db`, a SQLite file written by `main` after C2 (#1903) and before #1934 recorded a graph's
 * shape. Agent `assistant` has one tool, `step`, which adds one to key `counter`; an approval middleware asks to review
 * call `c1`. With `locked`, `step` takes lock `a` on every call, so the loop registers its `tool-queue` key. Threads:
 *  - `unlocked-at-rest`: without locks, one completed turn (`c1` approved, answered `first`);
 *  - `unlocked-parked`: without locks, a turn calling `c1` and `c2`, suspended on `c1`'s approval;
 *  - `locked-queued`: with locks, the same turn: `c1` awaits approval and `c2` waits behind it in `tool-queue`.
 */
object LocksFixture:
  val Resource                    = "/fixtures/locks-threads.db"
  val AtRest: ThreadId            = ThreadId("unlocked-at-rest")
  val Parked: ThreadId            = ThreadId("unlocked-parked")
  val Queued: ThreadId            = ThreadId("locked-queued")
  val counter: StateKey[Int, Int] = StateKey.replace[Int]("counter", 0)

  final case class Arg(text: String) derives ReadWriter

  def call(id: String): ToolCall = ToolCall(id, "step", ujson.Obj("text" -> id))

  def completion(message: AssistantMessage): Completion =
    Completion(
      id = "fixture",
      created = Instant.EPOCH,
      content = message.content,
      model = "test-model",
      message = message,
      toolCalls = message.toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )

  def calling(ids: String*): Completion = completion(AssistantMessage(None, ids.map(call)))
  def answer(text: String): Completion  = completion(AssistantMessage(text))

  /** Answers each call with the next completion. */
  final class Scripted(completions: Completion*) extends LLMClient:
    private val index = new AtomicInteger()
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      completions.lift(index.getAndIncrement()).toRight(ValidationError("scripted", "no completion left"))
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    def getContextWindow(): Int     = 128000
    def getReserveCompletion(): Int = 4096

  def agent(client: LLMClient, store: SqliteCheckpointer, locked: Boolean): Agent =
    val plain = AgentToolSpec[Arg](
      "step",
      "Adds one",
      Schema.`object`[Arg]("step").withRequiredField("text", Schema.string("Text"))
    )
    val spec = if locked then plain.withLocks(_ => Set(LockKey("a"))) else plain
    val step = AgentTool(spec, writes = Set(counter)) { (_, context) =>
      context.state.get(counter) match
        case Left(error) => ToolOutcome.Fatal(error)
        case Right(n) =>
          ToolOutcome.Success(ToolContent.Json(ujson.Str((n + 1).toString)), StateUpdate.update(counter, n + 1))
    }
    Agent
      .builder("assistant", client)
      .withSystemPrompt("You are a fixture.")
      .withTools(ToolSet.of(step).fold(e => throw new IllegalStateException(e.message), identity))
      .withMiddleware(new ApprovalMiddleware(r => Option.when(r.call.id == "c1")("review")))
      .withRuntime(GraphRuntime(store))
      .build()
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** Approves every pending approval of a suspended result. */
  def approveAll(parked: AgentResult): Map[InterruptId, ujson.Value] = parked.status match
    case AgentStatus.Suspended(pending, _, _, _, _) => pending.map(p => parked.approve(p._1)).toMap
    case other => throw new IllegalStateException(s"expected Suspended, got $other")

  /** An approval of every interrupt parked in `thread`'s latest checkpoint, read from `store`. */
  def approvals(store: SqliteCheckpointer, thread: ThreadId): Map[InterruptId, ujson.Value] =
    store
      .latest(thread)
      .toOption
      .flatten
      .fold(Vector.empty[GraphSnapshot.ParkedContinuation])(_.checkpoint.snapshot.parked)
      .map(p => InterruptId(p.interruptId) -> upickle.default.writeJs(ApprovalDecision.Approve))
      .toMap

  /** A copy of the fixture file, opened. */
  def opened(): SqliteCheckpointer =
    val db = SqliteFiles.fresh()
    Files.copy(getClass.getResourceAsStream(Resource), db, StandardCopyOption.REPLACE_EXISTING)
    SqliteFiles.opened(db, Clock.systemUTC())

/** Writes `locks-threads.db`; run once, on `main` before #1934, with the target path as its argument. */
object WriteLocksFixture:
  import LocksFixture.*

  def main(args: Array[String]): Unit =
    val target = Path.of(args(0))
    SqliteFiles.delete(target)
    val store = SqliteFiles.opened(target, Clock.systemUTC())

    val restClient = Scripted(calling("c1"), answer("first"))
    val rest       = agent(restClient, store, locked = false)
    val parked     = rest.run(AtRest, "go").fold(e => throw new IllegalStateException(e.message), identity)
    val done       = rest.resume(AtRest, approveAll(parked))
    require(done.exists(_.answer.contains("first")), s"at rest: $done")

    val waiting = agent(Scripted(calling("c1", "c2")), store, locked = false).run(Parked, "go")
    require(waiting.exists(_.status.isInstanceOf[AgentStatus.Suspended]), s"parked: $waiting")

    val queued = agent(Scripted(calling("c1", "c2")), store, locked = true).run(Queued, "go")
    require(queued.exists(_.status.isInstanceOf[AgentStatus.Suspended]), s"queued: $queued")
    val snapshot = store.latest(Queued).toOption.flatten.map(_.checkpoint.snapshot)
    require(snapshot.exists(_.state.contains("tool-queue")), s"no queued calls: $snapshot")
    store.close()
