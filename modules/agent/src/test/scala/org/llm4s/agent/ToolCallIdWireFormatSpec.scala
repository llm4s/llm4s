package org.llm4s.agent

import org.llm4s.agent.AgentFixture.*
import org.llm4s.agent.SpecTools.*
import org.llm4s.agent.events.{ ToolCallResult, ToolCallStarted, ToolExecuted }
import org.llm4s.agent.graph.{
  Checkpoint,
  ClaimRequest,
  Commit,
  EventDraft,
  EventRecord,
  GraphRuntime,
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

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * Tool call ids and idempotency keys are newtypes (#2815), stored as the plain JSON strings they were before.
 *
 * `fixtures/format-9-tool-calls.json` was written by 6d4d11cd7, the last build in which `ToolCall.id`,
 * `ToolMessage.toolCallId` and the event payloads' ids were `String`s, with the program below, run as
 * `sbt "agent/Test/runMain org.llm4s.agent.WriteToolCallIdFixture <target>"`. The model calls `echo` (`c1`), which
 * runs, then `deploy` (`c2`), which an `ApprovalMiddleware` parks: the checkpoint holds both calls, the tool result
 * and the parked approval with its idempotency key; the events hold a `ToolExecuted` for each call.
 *
 * {{{
 * object WriteToolCallIdFixture:
 *   def main(args: Array[String]): Unit =
 *     val thread = ThreadId("tool-call-ids")
 *     val store  = new InMemoryCheckpointer()
 *     val client = ScriptedLLMClient.of(calling(call("c1", "echo", "hi")), calling(call("c2", "deploy", "prod")))
 *     val agent  = built(agent(client, store))           // the builder `agent` below
 *     agent.run(thread, "echo then deploy")              // suspends on the approval
 *     val stored = store.latest(thread).get
 *     val events = store.eventsAfter(thread, 0L, 10000)
 *     write(args(0), ujson.Obj(
 *       "threadId"      -> thread.value,
 *       "checkpoint"    -> Checkpoint.toJson(stored.checkpoint),
 *       "pendingWrites" -> ujson.Arr.from(stored.pendingWrites.map(upickle.default.writeJs(_))),
 *       "events"        -> ujson.Arr.from(events.map(upickle.default.writeJs(_)))))
 * }}}
 */
class ToolCallIdWireFormatSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val fixture: ujson.Value =
    Using.resource(Source.fromResource("fixtures/format-9-tool-calls.json"))(source => ujson.read(source.mkString))

  private val thread = ThreadId(fixture("threadId").str)

  private def agent(client: org.llm4s.llmconnect.LLMClient, store: InMemoryCheckpointer, deploy: => ToolOutcome) =
    val echo = SpecTools.tool("echo")((text, _) => ToolOutcome.success(ToolContent.Json(ujson.Str(text.text))))
    val dep  = SpecTools.tool("deploy")((_, _) => deploy)
    built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(echo, dep))
        .withMiddleware(
          new ApprovalMiddleware(request => Option.when(request.call.name == "deploy")(s"review ${request.call.id}"))
        )
        .withRuntime(new GraphRuntime(store))
    )

  private def toolExecuted(events: Seq[ujson.Value]): Seq[ujson.Value] =
    events.map(_("event")).collect {
      case e: ujson.Obj if e.value.get("name").contains(ujson.Str("agent.tool_executed")) => e("payload")
    }

  private def exactly(json: ujson.Value)(roundTrip: ujson.Value => ujson.Value) =
    ujson.write(roundTrip(json)) shouldBe ujson.write(json)

  "A stored checkpoint with tool calls" should "read and write back byte-identical" in {
    exactly(fixture("checkpoint"))(json => Checkpoint.toJson(Checkpoint.fromJson(json).value))
    fixture("pendingWrites").arr.foreach(exactly(_)(json => upickle.default.writeJs(upickle.default.read[PendingWrite](json))))
  }

  "Its durable events" should "read and write back byte-identical, the tool_executed payloads too" in {
    val events = fixture("events").arr.toVector
    events.foreach(exactly(_)(json => upickle.default.writeJs(upickle.default.read[EventRecord](json))))
    val payloads = toolExecuted(events)
    payloads.map(_("toolCallId").str) shouldBe Seq("c1", "c2")
    payloads.foreach(exactly(_)(json => upickle.default.writeJs(upickle.default.read[ToolExecuted](json))))
  }

  "A run today" should "write the same messages, parked approval and event ids as the old build" in {
    val store  = new InMemoryCheckpointer()
    val client = ScriptedLLMClient.of(calling(call("c1", "echo", "hi")), calling(call("c2", "deploy", "prod")))
    agent(client, store, ToolOutcome.success(ToolContent.Json(ujson.Str("deployed"))))
      .run(thread, "echo then deploy")
      .value
    val now      = Checkpoint.toJson(store.latest(thread).value.get.checkpoint)("snapshot")
    val stored   = fixture("checkpoint")("snapshot")
    def keep(s: ujson.Value) = (ujson.write(s("state")("messages")), ujson.write(s("parked")))
    keep(now) shouldBe keep(stored)
    val events = store.eventsAfter(thread, 0L, 10000).value.map(upickle.default.writeJs(_))
    def ids(payloads: Seq[ujson.Value]) = payloads.map(p => (p("toolCallId"), p("idempotencyKey")))
    ids(toolExecuted(events)) shouldBe ids(toolExecuted(fixture("events").arr.toVector))
  }

  "The parked approval" should "resume with the key the old build stored" in {
    val seeded = new InMemoryCheckpointer()
    val writes = fixture("pendingWrites").arr.toVector.map(upickle.default.read[PendingWrite](_))
    val events = fixture("events").arr.toVector.map(upickle.default.read[EventRecord](_)).map { r =>
      EventDraft(r.runId, r.checkpointId, r.taskId, r.nodeId, r.timestamp, r.event)
    }
    val claim = seeded.claim(thread, ClaimRequest(RunId("seed"), None, 1.minute)).value
    val point = Checkpoint.fromJson(fixture("checkpoint")).value.withParent(None)
    seeded.commit(thread, Commit(claim.token, Some(point), writes, events)).value
    seeded.release(thread, claim.token).value

    val seen = new AtomicReference[Option[ujson.Value]](None)
    val resumed = SpecTools.tool("deploy") { (_, context) =>
      seen.set(Some(upickle.default.writeJs(context.idempotencyKey)))
      ToolOutcome.success(ToolContent.Json(ujson.Str("deployed")))
    }
    val echo = SpecTools.tool("echo")((text, _) => ToolOutcome.success(ToolContent.Json(ujson.Str(text.text))))
    val run = built(
      Agent
        .builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("shipped")))
        .withTools(SpecTools.set(echo, resumed))
        .withMiddleware(new ApprovalMiddleware(request => Option.when(request.call.name == "deploy")("review")))
        .withRuntime(new GraphRuntime(seeded))
    )
    val parked = fixture("checkpoint")("snapshot")("parked")(0)
    run
      .resume(
        thread,
        Map(InterruptId(parked("interruptId").str) -> upickle.default.writeJs[ApprovalDecision](ApprovalDecision.Approve))
      )
      .value
      .answer shouldBe Some("shipped")
    seen.get shouldBe Some(parked("question")("value")("idempotencyKey"))
  }

  "The live tool events" should "keep their JSON" in {
    exactly(ujson.read("""{"toolCallId":"c1","tool":"echo","arguments":{"text":"hi"}}"""))(json =>
      upickle.default.writeJs(upickle.default.read[ToolCallStarted](json))
    )
    exactly(ujson.read("""{"toolCallId":"c1","content":"hi","isError":false}"""))(json =>
      upickle.default.writeJs(upickle.default.read[ToolCallResult](json))
    )
  }
}
