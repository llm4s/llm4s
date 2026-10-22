package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.*
import org.llm4s.error.ValidationError
import org.llm4s.it.tags.Docker
import org.scalatest.{ BeforeAndAfterAll, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentHashMap, CopyOnWriteArrayList, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * A run in another process is killed part-way - `kill -9`, so it neither commits nor releases anything more - and
 * this process, over the same schema, replays the dead run's events and recovers it, without re-running the task it
 * completed, once its claim has expired by the database's clock: no clock here is moved. While it lives, the run
 * holds the thread against this process, and a subscription here receives its events as they are committed. The
 * PostgreSQL counterpart of `SqliteCrossProcessSpec` (design 4.50).
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]]. The child JVM reads the same `POSTGRES_*` variables.
 */
@Docker
class PostgresCrossProcessSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterAll {

  private val patience = 60.seconds
  private val schema   = PgCheckpointerDb.freshSchema()

  final private case class Killed(process: Process) extends AutoCloseable {
    def close(): Unit = {
      process.destroyForcibly()
      process.waitFor(patience.toSeconds, TimeUnit.SECONDS): Unit
    }
  }

  private def child(thread: ThreadId, log: Path): Process = {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    new ProcessBuilder(
      java,
      "-cp",
      System.getProperty("java.class.path"),
      PostgresCrashingRunMain.getClass.getName.stripSuffix("$"),
      schema,
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

  "A run killed in another process" should
    "be held while it lives, its events reaching a subscriber here, then recovered here once its claim expires" in {
      PgCheckpointerDb.require()
      val log     = Files.createTempFile("llm4s-pg-child", ".log")
      val thread  = ThreadId("cross-process")
      val store   = PgCheckpointerDb.store(PostgresCheckpointerConfig(schema = schema))
      val workers = PgWorkers()
      val runtime = GraphRuntime(store, watch = WatchPolicy(pollInterval = 20.millis))
      val seen    = new CopyOnWriteArrayList[EventRecord]()
      val subscription = runtime
        .subscribe(thread) {
          case StreamEvent.Durable(record) => seen.add(record): Unit
          case _                           => ()
        }
        .value
      def seenSeqs: Vector[Long] = seen.asScala.toVector.map(_.seq)
      val process                = child(thread, log)
      Using.resource(Killed(process)) { _ =>
        eventually("the child's first task to commit", process, log) {
          store.latest(thread).value.exists(_.pendingWrites.exists(_.nodeId == "worker"))
        }
        runtime.recover(thread, workers.graph).left.value shouldBe
          GraphError.ThreadBusy(
            thread.value,
            store.latest(thread).value.map(_.checkpoint.id),
            GraphError.BusyHolder.Run(RunId("child"))
          )
        runtime.deleteThread(thread).left.value shouldBe a[GraphError.ThreadBusy]
        eventually("the child's events to reach the subscriber", process, log) {
          seenSeqs == store.eventsAfter(thread, 0L, 1000).value.map(_.seq)
        }
        seen.asScala.map(_.runId).toSet shouldBe Set("child")
      }
      process.isAlive shouldBe false

      val replayed = store.eventsAfter(thread, 0L, 1000).value
      replayed.map(_.seq) shouldBe (1L to replayed.size.toLong).toVector
      replayed.head.event shouldBe a[RunEvent.RunStarted]
      replayed.count(e => e.event == RunEvent.TaskCompleted && e.nodeId.contains("worker")) shouldBe 1

      // the dead run's claim lasts until its ttl after its last renewal, by the database's clock: wait it out
      val deadline = System.nanoTime() + patience.toNanos
      def recovered: Either[org.llm4s.error.LLMError, RunHandle[Vector[String]]] =
        runtime.recover(thread, workers.graph, RunConfig().withRunId(RunId("parent")))
      val handle = Iterator
        .continually(recovered)
        .map { r =>
          if (r.left.exists(_.isInstanceOf[GraphError.ThreadBusy]) && System.nanoTime() < deadline) {
            Thread.sleep(100)
            None
          } else Some(r)
        }
        .collectFirst { case Some(r) => r }
        .get
        .value
      handle.await().value match {
        case RunResult.Completed(_, output, _) => output shouldBe Vector("A", "B")
        case other                             => fail(s"recovery did not complete: $other")
      }
      // `a` ran only in the dead process: its durable result was reused; `b` ran here
      workers.callsOf("a") shouldBe 0
      workers.callsOf("b") shouldBe 1

      val all = store.eventsAfter(thread, 0L, 1000).value
      all.map(_.seq) shouldBe (1L to all.size.toLong).toVector
      all.drop(replayed.size).head.event shouldBe a[RunEvent.RunRecovered]
      val seenDeadline = System.nanoTime() + patience.toNanos
      while (seenSeqs != all.map(_.seq) && System.nanoTime() < seenDeadline) Thread.sleep(20)
      seenSeqs shouldBe all.map(_.seq)
      subscription.cancel()
      store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
      store.close()
    }

  override def afterAll(): Unit = {
    if (PgCheckpointerDb.enabled) PgCheckpointerDb.dropSchema(schema)
    super.afterAll()
  }
}

/**
 * A graph that fans its items out to one worker task each and collects their results in task order; `work` decides
 * what a worker does. Built the same by every process, as a deployed graph is.
 */
final class PgWorkers(work: String => Either[String, Unit] = _ => Right(())) {
  val calls   = new ConcurrentHashMap[String, AtomicInteger]()
  val results = StateKey.appending[String]("results")

  def callsOf(item: String): Int = Option(calls.get(item)).fold(0)(_.get)

  val graph: CompiledGraph[Vector[String], Vector[String]] = {
    val b = GraphBuilder("postgres-restart", "v1")
    val worker = b.node[String]("worker", writes = Set(results)) { (item, _, _) =>
      calls.computeIfAbsent(item, _ => new AtomicInteger()).incrementAndGet()
      work(item) match {
        case Left(problem) => NodeResult.Fail(ValidationError("worker", problem))
        case Right(())     => NodeResult.Continue(Command.empty.update(results, item.toUpperCase))
      }
    }
    val collect = b.node[Unit]("collect")((_, _, _) => NodeResult.Continue(Command.empty))
    val join    = b.dynamicJoin("workers", collect)
    val plan = b.node[Vector[String]]("plan") { (items, _, _) =>
      NodeResult.Continue(Command.empty.fanOut(join, worker, items))
    }
    b.compile(plan)(_.get(results)).fold(e => throw new IllegalStateException(e.message), identity)
  }
}

/**
 * The process the cross-process spec kills: it opens the store in schema `args(0)`, starts a run `child` on thread
 * `args(1)` over items `a` and `b`, and never finishes it - `a` completes, `b` blocks - renewing its 3 second claim
 * every 200 ms until it is killed.
 */
object PostgresCrashingRunMain {
  def main(args: Array[String]): Unit = {
    val store   = PgCheckpointerDb.store(PostgresCheckpointerConfig(schema = args(0)))
    val runtime = GraphRuntime(store, claims = ClaimPolicy(ttl = 3.seconds, renewEvery = 200.millis))
    val workers = new PgWorkers(item => Right(if (item == "b") Thread.sleep(Long.MaxValue)))
    runtime
      .start(ThreadId(args(1)), workers.graph, Vector("a", "b"), RunConfig().withRunId(RunId("child")))
      .fold(e => throw new IllegalStateException(e.message), _.await()): Unit
  }
}
