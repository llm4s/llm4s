package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.agent.testkit.ManualClock
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.util.Using

/**
 * A run in another process is killed part-way - `kill -9`, so it neither commits nor releases anything more - and
 * this process, over the same file, replays the dead run's events and recovers it, without re-running the task it
 * had completed. Before the kill, the live run in the other process holds the thread against this one.
 */
class SqliteCrossProcessSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val patience = 60.seconds

  /** Kills the process - `SIGKILL`, so it runs no shutdown hook - and waits for it to be gone. */
  final private case class Killed(process: Process) extends AutoCloseable {
    def close(): Unit = {
      process.destroyForcibly()
      process.waitFor(patience.toSeconds, TimeUnit.SECONDS): Unit
    }
  }

  private def child(db: Path, thread: ThreadId, log: Path): Process = {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    new ProcessBuilder(
      java,
      "-cp",
      System.getProperty("java.class.path"),
      CrashingRunMain.getClass.getName.stripSuffix("$"),
      db.toString,
      thread.value
    ).redirectErrorStream(true).redirectOutput(log.toFile).start()
  }

  private def eventually(what: String, process: Process, log: Path)(condition: => Boolean): Unit = {
    val deadline = System.nanoTime() + patience.toNanos
    while (!condition && System.nanoTime() < deadline) {
      if (!process.isAlive) fail(s"the child process exited before $what:\n${Files.readString(log)}")
      Thread.sleep(20)
    }
    if (!condition) fail(s"timed out waiting for $what:\n${Files.readString(log)}")
  }

  "A run killed in another process" should "be held while it lives, then recovered here from the same file" in {
    val db     = SqliteFiles.fresh()
    val log    = Files.createTempFile("llm4s-sqlite-child", ".log")
    val thread = ThreadId("cross-process")
    // this process's clock: the child's claim lasts an hour by its own, and this store judges it by this one
    val clock   = ManualClock(Instant.now())
    val store   = SqliteFiles.opened(db, clock)
    val workers = Workers()
    val runtime = GraphRuntime(store)
    val process = child(db, thread, log)
    Using.resource(Killed(process)) { _ =>
      // `a` completed in the child and its pending write is durable; `b` is still running there
      eventually("the child's first task to commit", process, log) {
        store.latest(thread).value.exists(_.pendingWrites.exists(_.nodeId == "worker"))
      }
      runtime.recover(thread, workers.graph).left.value shouldBe
        GraphError.ThreadBusy(thread.value, store.latest(thread).value.map(_.checkpoint.id), Some("child"))
      runtime.start(thread, workers.graph, Vector("c")).left.value shouldBe a[GraphError.IncompleteRun]
      runtime.deleteThread(thread).left.value shouldBe a[GraphError.ThreadBusy]
    }
    process.isAlive shouldBe false

    // the dead run's log replays from the file: begun, one task completed, never ended
    val replayed = store.eventsAfter(thread, 0L, 1000).value
    replayed.map(_.runId).distinct shouldBe Vector("child")
    replayed.map(_.seq) shouldBe (1L to replayed.size.toLong).toVector
    replayed.head.event shouldBe a[RunEvent.RunStarted]
    replayed.count(e => e.event == RunEvent.TaskCompleted && e.nodeId.contains("worker")) shouldBe 1
    replayed.map(_.event).collect { case e @ (RunEvent.RunCompleted | RunEvent.RunFailed(_)) => e } shouldBe empty

    // its claim is still in the file, so the thread stays refused until the claim expires by this store's clock
    runtime.recover(thread, workers.graph).left.value shouldBe a[GraphError.ThreadBusy]
    // the child renewed by its own clock until it died, so its claim lasts until `ttl` after its last renewal:
    // this store's clock must pass that, not merely `ttl` after this store opened
    clock.set(Instant.now().plusNanos(CrashingRunMain.ClaimTtl.toNanos).plusSeconds(1))
    val recovered =
      runtime.recover(thread, workers.graph, RunConfig().withRunId(RunId("parent"))).value.await().value
    recovered match {
      case RunResult.Completed(_, output, _) => output shouldBe Vector("A", "B")
      case other                             => fail(s"recovery did not complete: $other")
    }
    // `a` ran only in the dead process: its durable result was reused; `b`, which never finished there, ran here
    workers.callsOf("a") shouldBe 0
    workers.callsOf("b") shouldBe 1

    val all = store.eventsAfter(thread, 0L, 1000).value
    all.map(_.seq) shouldBe (1L to all.size.toLong).toVector
    all.take(replayed.size).map(_.seq) shouldBe replayed.map(_.seq)
    all.drop(replayed.size).map(_.runId).distinct shouldBe Vector("parent")
    all.drop(replayed.size).head.event shouldBe a[RunEvent.RunRecovered]
    val latest = store.latest(thread).value.value.checkpoint
    latest.status shouldBe CheckpointStatus.Completed
    latest.runId shouldBe "parent"
    store.close()

    // and a third store, as after another restart, sees the finished thread
    val reopened = SqliteFiles.opened(db, clock)
    (reopened.latest(thread).value.value.checkpoint == latest) shouldBe true
    reopened.close()
  }
}
