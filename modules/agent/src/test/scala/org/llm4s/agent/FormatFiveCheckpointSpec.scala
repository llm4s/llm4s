package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{
  Checkpoint,
  ClaimRequest,
  Commit,
  EventDraft,
  EventRecord,
  GraphRuntime,
  IdempotencyKey,
  InMemoryCheckpointer,
  InterruptId,
  PendingWrite,
  RunId,
  ThreadId
}
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.agent.graph.toolloop.ApprovalDecision
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using
import scala.jdk.CollectionConverters.*

/**
 * A thread written by a real format-5 build (#1821), resumed by this one.
 *
 * `fixtures/format-5-two-approvals.json` was written by ed5e6f82e - #1739, the last commit before #1742 bumped the
 * checkpoint format from 5 to 6 - with the program below, run in a worktree of that commit as
 * `sbt "agent/Test/runMain org.llm4s.agent.WriteFormatFiveApprovalsFixture <target>"`. It holds what that build's
 * `InMemoryCheckpointer` stored for thread `format-5-approvals` after one turn in which the model called `deploy`
 * twice (`c1` with `one`, `c2` with `two`) behind an `ApprovalMiddleware`, so the run suspended with both calls
 * pending: the latest checkpoint (`Checkpoint.toJson`, format 5), its pending writes and the thread's events, plus
 * each approval's interrupt id and the idempotency key the old build gave its call, as its `AgentResult` reported
 * them.
 *
 * {{{
 * object WriteFormatFiveApprovalsFixture:
 *   def main(args: Array[String]): Unit =
 *     val thread = ThreadId("format-5-approvals")
 *     val store  = new InMemoryCheckpointer()
 *     val deploy = SpecTools.tool("deploy")((_, _) => ToolOutcome.Success(ujson.Str("never run by the old build")))
 *     val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "one"), call("c2", "deploy", "two")))
 *     val agent = Agent
 *       .builder("assistant", client)
 *       .withTools(ToolSet.of(deploy).fold(e => sys.error(e.message), identity))
 *       .withMiddleware(new ApprovalMiddleware(request => Some(s"review ${request.call.id}")))
 *       .withRuntime(new GraphRuntime(store))
 *       .build()
 *       .fold(e => sys.error(e.message), identity)
 *     val parked = agent.run(thread, "deploy twice").fold(e => sys.error(e.message), identity)
 *     val approvals = parked.status match
 *       case AgentStatus.Suspended(approvals, _) => approvals
 *       case other                               => sys.error(s"expected Suspended, got $other")
 *     require(approvals.size == 2, s"two approvals expected: $approvals")
 *     val stored = store.latest(thread).fold(e => sys.error(e.message), identity).get
 *     require(stored.checkpoint.formatVersion == 5 && Checkpoint.CurrentFormat == 5, "not a format-5 build")
 *     val events = store.eventsAfter(thread, 0L, 10000).fold(e => sys.error(e.message), identity)
 *     val fixture = ujson.Obj(
 *       "threadId"      -> thread.value,
 *       "checkpoint"    -> Checkpoint.toJson(stored.checkpoint),
 *       "pendingWrites" -> ujson.Arr.from(stored.pendingWrites.map(upickle.default.writeJs(_))),
 *       "events"        -> ujson.Arr.from(events.map(upickle.default.writeJs(_))),
 *       "approvals" -> ujson.Arr.from(approvals.map { (id, request) =>
 *         ujson.Obj(
 *           "interruptId"    -> id.value,
 *           "callId"         -> request.call.id,
 *           "idempotencyKey" -> request.idempotencyKey.value
 *         )
 *       })
 *     )
 *     Files.writeString(Path.of(args(0)), ujson.write(fixture, indent = 2) + "\n")
 * }}}
 *
 * The format-5 store kept only a thread's latest checkpoint, so the spec commits it as the first of a fresh store's
 * history (its `parent` cleared) under a claim of that store, with the old pending writes and events.
 */
class FormatFiveCheckpointSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val fixture: ujson.Value =
    Using.resource(Source.fromResource("fixtures/format-5-two-approvals.json"))(source => ujson.read(source.mkString))

  private val thread = ThreadId(fixture("threadId").str)

  /** `(interrupt id, call id, key)` of each approval, as the format-5 build reported them. */
  private val approvals: Vector[(InterruptId, String, IdempotencyKey)] =
    fixture("approvals").arr.toVector.map { a =>
      (InterruptId(a("interruptId").str), a("callId").str, IdempotencyKey(a("idempotencyKey").str))
    }

  /** A store holding what the format-5 build stored for the thread. */
  private def seeded(): InMemoryCheckpointer =
    val store      = new InMemoryCheckpointer()
    val checkpoint = Checkpoint.fromJson(fixture("checkpoint")).value
    val writes     = fixture("pendingWrites").arr.toVector.map(upickle.default.read[PendingWrite](_))
    val events = fixture("events").arr.toVector.map(upickle.default.read[EventRecord](_)).map { r =>
      EventDraft(r.runId, r.checkpointId, r.taskId, r.nodeId, r.timestamp, r.event)
    }
    val claim = store.claim(thread, ClaimRequest(RunId("seed"), None, 1.minute)).value
    store
      .commit(thread, Commit(claim.token, Some(checkpoint.withParent(None)), writes, events))
      .value
    store.release(thread, claim.token).value
    store

  "A format-5 thread suspended on two approvals" should "decode as the current format, both calls still parked" in {
    fixture("checkpoint")("formatVersion").num shouldBe 5
    val checkpoint = Checkpoint.fromJson(fixture("checkpoint")).value
    checkpoint.formatVersion shouldBe Checkpoint.CurrentFormat
    checkpoint.snapshot.parked.map(_.interruptId) shouldBe approvals.map(_._1.value)
    approvals.map(_._2) shouldBe Vector("c1", "c2")
  }

  it should "resume, running both tools with the idempotency keys the format-5 build wrote" in {
    val seen = new ConcurrentHashMap[String, IdempotencyKey]()
    val deploy = SpecTools.tool("deploy") { (a, context) =>
      seen.put(a.text, context.idempotencyKey)
      ToolOutcome.Success(ToolContent.Json(ujson.Str(s"deployed ${a.text}")))
    }
    val client = ScriptedLLMClient.of(CompletionFixture.simple("both shipped"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(deploy))
        .withMiddleware(new ApprovalMiddleware(request => Some(s"review ${request.call.id}")))
        .withRuntime(GraphRuntime(seeded()))
    )
    val answers = approvals.map((id, _, _) => id -> upickle.default.writeJs[ApprovalDecision](ApprovalDecision.Approve))

    val done = agent.resume(thread, answers.toMap).value

    done.answer shouldBe Some("both shipped")
    val byCall = approvals.map((_, callId, key) => callId -> key).toMap
    seen.asScala.toMap shouldBe Map("one" -> byCall("c1"), "two" -> byCall("c2"))
  }
}
