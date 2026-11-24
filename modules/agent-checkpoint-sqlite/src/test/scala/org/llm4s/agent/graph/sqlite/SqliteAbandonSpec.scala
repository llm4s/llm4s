package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture, ScriptedLLMClient, SpecTools }
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.agent.service.*
import org.llm4s.llmconnect.model.*
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import org.scalatest.time.{ Millis, Seconds, Span }

import java.time.Clock
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * `Agent.abandon` (#1750) over one SQLite file, after a crash: the process that ran the turn is gone - its connection
 * closed mid tool batch - and another process, opening the same file, abandons the thread. The finished call's result
 * is read back from the file's pending writes, the unfinished one gets the reason, and a queue entry whose attempt the
 * run was closes `Failed(Abandoned)` from the file alone.
 */
class SqliteAbandonSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private val agentId = AgentId.unsafe("assistant")

  /** The tools' run counts and the gate `slow` releases as it starts, shared by both processes' agents. */
  final private class Tools {
    val goodRan = new AtomicInteger(0)
    val slowRan = new AtomicInteger(0)
    val entered = new Semaphore(0)
    private val good = SpecTools.tool("good") { (_, _) =>
      goodRan.incrementAndGet()
      ToolOutcome.success(ToolContent.Json(ujson.Str("good")))
    }
    private val slow = SpecTools.tool("slow") { (_, _) =>
      slowRan.incrementAndGet()
      entered.release()
      Thread.sleep(60_000) // interrupted by the cancel: the call then has no result
      ToolOutcome.success(ToolContent.Json(ujson.Str("late")))
    }
    def agent(client: ScriptedLLMClient, store: SqliteCheckpointer): Agent =
      Agent
        .builder("assistant", client)
        .withRuntime(new GraphRuntime(store))
        .withTools(SpecTools.set(good, slow))
        .build()
        .value
  }

  private def results(messages: Seq[Message]): Vector[(String, String)] =
    messages.collect { case t: ToolMessage => t.toolCallId.asString -> t.content }.toVector

  "Agent.abandon over SQLite" should "close a turn another process crashed in, from what the file holds" in {
    val file   = SqliteFiles.fresh()
    val tools  = new Tools
    val client = ScriptedLLMClient.of(calling(call("g1", "good"), call("s1", "slow")), CompletionFixture.simple("on"))
    val on     = ThreadId("sqlite-abandon")

    // the process that crashes: cut mid tool batch, its connection closed
    val crashed = SqliteFiles.opened(file, Clock.systemUTC())
    val running = tools.agent(client, crashed).start(on, "go").value
    tools.entered.acquire()
    // the finished sibling's result is durable, as a pending write, before the process dies
    eventually(crashed.latest(on).value.value.pendingWrites should not be empty)
    running.cancel()
    running.await().isLeft shouldBe true
    crashed.close()

    val store = SqliteFiles.opened(file, Clock.systemUTC())
    try {
      val agent = tools.agent(client, store)
      store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Running
      agent.abandon(on, "abandoned: the process died") shouldBe Right(())

      val settled = store.latest(on).value.value
      settled.checkpoint.status shouldBe CheckpointStatus.Failed
      settled.checkpoint.outcome.value.asInstanceOf[RunOutcome.Failed].kind shouldBe Agent.AbandonedKind
      settled.pendingWrites shouldBe empty
      agent.recover(on).left.value shouldBe GraphError.NothingToRecover(on.value)

      agent.run(on, "carry on").value.answer shouldBe Some("on")
      results(client.sent.last) should contain theSameElementsAs Vector(
        "g1" -> "good",
        "s1" -> "abandoned: the process died"
      )
      tools.goodRan.get shouldBe 1
      tools.slowRan.get shouldBe 1
      client.callCount shouldBe 2
    } finally store.close()
  }

  it should "close a dead worker's queue entry Failed(Abandoned), though the thread ran on before a worker took it" in {
    val file   = SqliteFiles.fresh()
    val tools  = new Tools
    val client = ScriptedLLMClient.of(calling(call("s1", "slow")), CompletionFixture.simple("direct answer"))
    val on     = ThreadId("sqlite-abandon-queued")

    // a worker's process that crashes mid tool batch: its attempt recorded and admitted, its lease left to expire
    val crashed = SqliteFiles.opened(file, Clock.systemUTC())
    val id =
      crashed.runQueue
        .submit(Submission(on, agentId, QueuedInput.query("hello"), policy = BusyThreadPolicy.Enqueue))
        .value
        .entryId
    val leased = crashed.runQueue.take(TakeRequest("dead-worker", Set(agentId), 300.millis)).value.value
    val run    = RunId.random()
    crashed.runQueue.recordAttempt(leased.lease, run).value shouldBe a[AttemptStart.Recorded]
    val running = tools.agent(client, crashed).start(on, "hello", RunConfig().withRunId(run)).value
    tools.entered.acquire()
    running.cancel()
    running.await().isLeft shouldBe true
    crashed.close()

    val store = SqliteFiles.opened(file, Clock.systemUTC())
    val agent = tools.agent(client, store)
    agent.abandon(on) shouldBe Right(())
    agent.run(on, "direct").value.answer shouldBe Some("direct answer")
    val worker = AgentWorker(
      store.runQueue,
      store,
      Seq(QueuedAgent(agent)),
      WorkerSettings(pollInterval = 20.millis, sweepEvery = None, drainGrace = 2.seconds),
      RunQueueSettings(lease = LeasePolicy(ttl = 2.seconds, renewEvery = 100.millis))
    ).value
    worker.start()
    try {
      store.runQueue.awaitClosed(None, id, 10.seconds).value.outcome.value shouldBe
        EntryOutcome.Failed(FailureReason.Abandoned)
      store.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
      tools.slowRan.get shouldBe 1
      client.callCount shouldBe 2
    } finally {
      worker.shutdown()
      store.close()
    }
  }
}
