package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.agent.testkit.ManualClock
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.*

/**
 * Restart and resume preserve checkpointed work and event replay (design §6, Stage 2): a run stops part-way, its
 * store is closed, and a new store and a new `GraphRuntime` over the same file replay its events and `recover` it
 * without re-running the tasks that had completed.
 */
class SqliteRestartSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def completed[O](result: RunResult[O]): O = result match {
    case RunResult.Completed(_, output, _) => output
    case other                             => fail(s"run did not complete: $other")
  }

  private def seqsOf(store: Checkpointer, thread: ThreadId): Vector[Long] =
    store.eventsAfter(thread, 0L, 1000).value.map(_.seq)

  Seq(Durability.Sync, Durability.Async, Durability.OnExit).foreach { durability =>
    s"A run that failed part-way ($durability)" should
      "be recovered after a restart without re-running the siblings that completed" in {
        val db       = SqliteFiles.fresh()
        val thread   = ThreadId(s"failed-$durability")
        val failingC = new AtomicBoolean(true)
        val before   = Workers(item => if item == "c" && failingC.get then Left("c failed") else Right(()))
        val first    = SqliteFiles.opened(db, java.time.Clock.systemUTC())
        val failed   = GraphRuntime(first).start(thread, before.graph, Vector("a", "b", "c"), durability = durability)
        failed.value.await().value shouldBe a[RunResult.Failed]
        val replayedBefore = first.eventsAfter(thread, 0L, 1000).value
        first.close()

        // a new process: a new store over the file, a new runtime, the same graph
        failingC.set(false)
        val after    = Workers()
        val second   = SqliteFiles.opened(db, java.time.Clock.systemUTC())
        val replayed = second.eventsAfter(thread, 0L, 1000).value
        replayed.map(_.seq) shouldBe replayedBefore.map(_.seq)
        replayed.map(_.event) shouldBe replayedBefore.map(_.event)
        replayed.count(e => e.event == RunEvent.TaskCompleted && e.nodeId.contains("worker")) shouldBe 2
        replayed.count(_.event.isInstanceOf[RunEvent.RunFailed]) shouldBe 1
        val stored = second.latest(thread).value.value
        stored.checkpoint.status shouldBe CheckpointStatus.Running
        stored.pendingWrites.count(_.nodeId == "worker") shouldBe 2

        completed(GraphRuntime(second).recover(thread, after.graph).value.await().value) shouldBe
          Vector("A", "B", "C")
        after.callsOf("a") shouldBe 0
        after.callsOf("b") shouldBe 0
        after.callsOf("c") shouldBe 1
        // numbering continues where the dead run's log stopped, with no gap and no number reused
        seqsOf(second, thread) shouldBe (1L to seqsOf(second, thread).size.toLong).toVector
        seqsOf(second, thread).take(replayed.size) shouldBe replayed.map(_.seq)
        second.close()
      }
  }

  "A completed thread" should "take its next input on the state it had before the restart" in {
    val db      = SqliteFiles.fresh()
    val thread  = ThreadId("multi-turn")
    val workers = Workers()
    val first   = SqliteFiles.opened(db, java.time.Clock.systemUTC())
    completed(GraphRuntime(first).start(thread, workers.graph, Vector("a")).value.await().value) shouldBe Vector("A")
    first.close()
    val second = SqliteFiles.opened(db, java.time.Clock.systemUTC())
    completed(GraphRuntime(second).start(thread, workers.graph, Vector("b")).value.await().value) shouldBe
      Vector("A", "B")
    second.close()
  }

  "A run whose store stops under it" should
    "keep its claim until it expires, then be recovered through another store without re-running completed tasks" in {
      val db      = SqliteFiles.fresh()
      val thread  = ThreadId("stopped")
      val clock   = ManualClock(Instant.parse("2026-10-10T09:00:00Z"))
      val entered = new CountDownLatch(1)
      val gate    = new CountDownLatch(1)
      val gated   = new AtomicBoolean(true)
      val workers = Workers { item =>
        if item == "b" && gated.getAndSet(false) then
          entered.countDown()
          gate.await(30, TimeUnit.SECONDS): Unit
        Right(())
      }
      val dying   = SqliteFiles.opened(db, clock)
      val claims  = ClaimPolicy(ttl = 30.seconds, renewEvery = 10.seconds)
      val running = GraphRuntime(dying, claims = claims).start(thread, workers.graph, Vector("a", "b")).value
      entered.await(30, TimeUnit.SECONDS) shouldBe true
      // `a`'s result is durable before the "process" stops
      val deadline = System.nanoTime() + 30.seconds.toNanos
      while (
        !dying.latest(thread).value.exists(_.pendingWrites.exists(_.nodeId == "worker")) &&
        System.nanoTime() < deadline
      ) Thread.sleep(5)

      // the process stops: its store goes away, so it can neither commit nor release again
      dying.close()
      gate.countDown()
      running.await().value match {
        case RunResult.Failed(_, GraphError.CheckpointWriteFailed(_, _, _)) => succeed
        case other => fail(s"the stopped run did not fail on its store: $other")
      }

      val survivor = SqliteFiles.opened(db, clock)
      val runtime  = GraphRuntime(survivor)
      runtime.recover(thread, workers.graph).left.value shouldBe a[GraphError.ThreadBusy]
      clock.advance(claims.ttl)
      completed(runtime.recover(thread, workers.graph).value.await().value) shouldBe Vector("A", "B")
      workers.callsOf("a") shouldBe 1
      workers.callsOf("b") shouldBe 2
      survivor.close()
    }
}
