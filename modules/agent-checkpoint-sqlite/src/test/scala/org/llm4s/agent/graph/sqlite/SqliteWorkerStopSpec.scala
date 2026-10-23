package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture }
import org.llm4s.agent.graph.*
import org.llm4s.agent.service.*
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Clock
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Cancel and interrupt from another process (Stage 5 W3, design 4.57) over one SQLite file: a worker runs on one
 * connection, and a second connection - another process's - submits, cancels and interrupts, so the worker learns of
 * each only from the file.
 */
class SqliteWorkerStopSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val agentId = AgentId.unsafe("assistant")
  private val lease   = RunQueueSettings(lease = LeasePolicy(ttl = 2.seconds, renewEvery = 100.millis))
  private val fast    = WorkerSettings(pollInterval = 20.millis, sweepEvery = None, drainGrace = 2.seconds)

  /** A model whose first call blocks until it is interrupted, releasing `entered`; the rest answer at once. */
  final private class FirstBlocks(entered: Semaphore) extends LLMClient {
    private val count = new AtomicInteger(0)
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      if (count.incrementAndGet() == 1) {
        entered.release()
        CancelledError.catchInterrupt(Thread.sleep(60_000)) match {
          case Left(e) =>
            Thread.currentThread().interrupt()
            Left(CancelledError("model call", Some(e)))
          case Right(_) => Left(ValidationError("blocking", "was never interrupted"))
        }
      } else Right(CompletionFixture.simple(s"answer ${count.get}"))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
  }

  private def running(test: (SqliteCheckpointer, Semaphore) => Unit): Unit = {
    val file    = SqliteFiles.fresh()
    val worker  = SqliteFiles.opened(file, Clock.systemUTC())
    val other   = SqliteFiles.opened(file, Clock.systemUTC())
    val entered = new Semaphore(0)
    val agent = Agent.builder("assistant", new FirstBlocks(entered)).withRuntime(new GraphRuntime(worker)).build().value
    val w     = AgentWorker(worker.runQueue, worker, Seq(QueuedAgent(agent)), fast, lease).value
    w.start()
    try test(other, entered)
    finally {
      w.shutdown()
      other.close()
      worker.close()
    }
  }

  private def submit(store: SqliteCheckpointer, on: ThreadId, policy: BusyThreadPolicy): EntryId =
    store.runQueue.submit(Submission(on, agentId, QueuedInput.query("q"), policy = policy)).value.entryId

  "A worker over SQLite" should "stop and settle a run cancelled from another process, then run the next entry" in {
    running { (other, entered) =>
      val on     = ThreadId("sqlite-stop-cancel")
      val first  = submit(other, on, BusyThreadPolicy.Enqueue)
      val second = submit(other, on, BusyThreadPolicy.Enqueue)
      entered.acquire()
      val reason = CancelReason.Requested(Some("elsewhere"))
      other.runQueue.cancel(None, CancelTarget.Entry(first), reason).value
      other.runQueue.awaitClosed(None, first, 10.seconds).value.outcome shouldBe Some(EntryOutcome.Cancelled(reason))
      other.runQueue.awaitClosed(None, second, 10.seconds).value.outcome.value shouldBe a[EntryOutcome.Done]
      other.history(on, None, 100).value.map(_.status) should contain(CheckpointStatus.Failed)
      other.latest(on).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
    }
  }

  it should "stop a run for an Interrupt entry submitted from another process, and run that entry next" in {
    running { (other, entered) =>
      val on     = ThreadId("sqlite-stop-interrupt")
      val first  = submit(other, on, BusyThreadPolicy.Enqueue)
      val queued = submit(other, on, BusyThreadPolicy.Enqueue)
      entered.acquire()
      val next = submit(other, on, BusyThreadPolicy.Interrupt)
      other.runQueue.awaitClosed(None, first, 10.seconds).value.outcome shouldBe Some(
        EntryOutcome.Interrupted(Some(next))
      )
      val ran = other.runQueue.awaitClosed(None, next, 10.seconds).value
      ran.outcome.value shouldBe a[EntryOutcome.Done]
      val after = other.runQueue.awaitClosed(None, queued, 10.seconds).value
      after.outcome.value shouldBe a[EntryOutcome.Done]
      after.closedAt.value.isBefore(ran.closedAt.value) shouldBe false
    }
  }
}
