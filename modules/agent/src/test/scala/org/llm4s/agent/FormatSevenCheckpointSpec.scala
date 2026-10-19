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
  RunOutcome,
  ThreadId
}
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.agent.graph.toolloop.ApprovalDecision
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * A thread written by a real format-7 build (#2045), restored and continued by this one, which writes format 8.
 *
 * `fixtures/format-7-one-approval.json` was written by 7623bd442 (the Phase 4 spec on `main`, the last format-7
 * build) with the program below, run as `sbt "agent/Test/runMain org.llm4s.agent.WriteFormatSevenFixture <target>"`.
 * It holds what that build's `InMemoryCheckpointer` stored for thread `format-7-approval` after one turn in which
 * the model called `deploy` (`c1` with `one`) behind an `ApprovalMiddleware`, so the run suspended: the latest
 * checkpoint (`Checkpoint.toJson`, format 7), its pending writes and events, and the approval's interrupt id and the
 * idempotency key the old build gave the call.
 *
 * {{{
 * object WriteFormatSevenFixture:
 *   def main(args: Array[String]): Unit =
 *     val thread = ThreadId("format-7-approval")
 *     val store  = new InMemoryCheckpointer()
 *     val deploy = SpecTools.tool("deploy")((_, _) =>
 *       ToolOutcome.Success(ToolContent.Json(ujson.Str("never run by the old build"))))
 *     val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "one")))
 *     val agent = Agent
 *       .builder("assistant", client)
 *       .withTools(SpecTools.set(deploy))
 *       .withMiddleware(new ApprovalMiddleware(request => Some(s"review ${request.call.id}")))
 *       .withRuntime(new GraphRuntime(store))
 *       .build()
 *       .fold(e => sys.error(e.message), identity)
 *     val parked = agent.run(thread, "deploy once").fold(e => sys.error(e.message), identity)
 *     val approvals = parked.status match
 *       case AgentStatus.Suspended(approvals, _, _, _) => approvals
 *       case other                                     => sys.error(s"expected Suspended, got $other")
 *     require(approvals.size == 1, s"one approval expected: $approvals")
 *     val stored = store.latest(thread).fold(e => sys.error(e.message), identity).get
 *     require(stored.checkpoint.formatVersion == 7 && Checkpoint.CurrentFormat == 7, "not a format-7 build")
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
 *     Files.writeString(Path.of(args(0)), ujson.write(fixture, indent = 2) + "\n"): Unit
 * }}}
 */
class FormatSevenCheckpointSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val fixture: ujson.Value =
    Using.resource(Source.fromResource("fixtures/format-7-one-approval.json"))(source => ujson.read(source.mkString))

  private val thread   = ThreadId(fixture("threadId").str)
  private val approval = fixture("approvals")(0)

  /** A store holding what the format-7 build stored for the thread. */
  private def seeded(): InMemoryCheckpointer =
    val store      = new InMemoryCheckpointer()
    val checkpoint = Checkpoint.fromJson(fixture("checkpoint")).value
    val writes     = fixture("pendingWrites").arr.toVector.map(upickle.default.read[PendingWrite](_))
    val events = fixture("events").arr.toVector.map(upickle.default.read[EventRecord](_)).map { r =>
      EventDraft(r.runId, r.checkpointId, r.taskId, r.nodeId, r.timestamp, r.event)
    }
    val claim = store.claim(thread, ClaimRequest(RunId("seed"), None, 1.minute)).value
    store.commit(thread, Commit(claim.token, Some(checkpoint.withParent(None)), writes, events)).value
    store.release(thread, claim.token).value
    store

  "A format-7 thread" should "decode as the current format: a top-level thread with no outcome" in {
    fixture("checkpoint")("formatVersion").num shouldBe 7
    val checkpoint = Checkpoint.fromJson(fixture("checkpoint")).value
    checkpoint.formatVersion shouldBe Checkpoint.CurrentFormat
    checkpoint.origin shouldBe None
    checkpoint.outcome shouldBe None
  }

  it should "resume with the old build's key, list no children, settle with an outcome and take another turn" in {
    val seen = new AtomicReference[Option[IdempotencyKey]](None)
    val deploy = SpecTools.tool("deploy") { (_, context) =>
      seen.set(Some(context.idempotencyKey))
      ToolOutcome.Success(ToolContent.Json(ujson.Str("deployed")))
    }
    val store  = seeded()
    val client = ScriptedLLMClient.of(CompletionFixture.simple("shipped"), CompletionFixture.simple("still shipped"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(deploy))
        .withMiddleware(new ApprovalMiddleware(request => Some(s"review ${request.call.id}")))
        .withRuntime(GraphRuntime(store))
    )

    agent.children(thread).value shouldBe empty
    val done = agent
      .resume(
        thread,
        Map(
          InterruptId(approval("interruptId").str) -> upickle.default
            .writeJs[ApprovalDecision](ApprovalDecision.Approve)
        )
      )
      .value

    done.answer shouldBe Some("shipped")
    seen.get shouldBe Some(IdempotencyKey(approval("idempotencyKey").str))
    val settled = store.latest(thread).value.get.checkpoint
    settled.formatVersion shouldBe Checkpoint.CurrentFormat
    settled.outcome shouldBe Some(RunOutcome.Answered)
    settled.origin shouldBe None
    agent.run(thread, "and again?").value.answer shouldBe Some("still shipped")
    agent.forget(thread).value
  }
}
