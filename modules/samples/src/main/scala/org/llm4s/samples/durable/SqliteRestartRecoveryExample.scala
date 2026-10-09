package org.llm4s.samples.durable

import org.llm4s.agent.graph._
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.error.{ LLMError, ProcessingError }

import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }
import scala.concurrent.duration._
import scala.util.Using

/**
 * Restart and recovery with the SQLite checkpointer: a run stops part-way, and a new store and a new `GraphRuntime`
 * over the same database file replay its events and finish it, without re-running the tasks it had completed.
 *
 * The graph fetches three reports in parallel, one task each, and joins them. The first "process" starts the run;
 * two fetches complete and are committed, and while the third is still working the process dies - here its store is
 * closed under it, so, exactly as after `kill -9`, it can neither commit nor release its claim again. The second
 * "process" opens the file, replays the dead run's event log, is refused while the dead run's claim is still live,
 * and once the claim has expired recovers the run: the two completed fetches are reused from the file, and only the
 * third runs again. No API key is needed.
 *
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.durable.SqliteRestartRecoveryExample"            # a temporary file
 *   sbt "samples/runMain org.llm4s.samples.durable.SqliteRestartRecoveryExample /tmp/runs.db"  # a new file you keep
 *   sbt "samples/runMain org.llm4s.samples.durable.SqliteRestartRecoveryExample /tmp/runs.db --overwrite"
 * }}}
 *
 * A path that names an existing file is refused unless `--overwrite` is given, which deletes the file (and its
 * `-wal` and `-shm` files) first. The temporary file, when no path is given, is removed afterwards.
 *
 * `SqliteCrossProcessSpec` in `llm4s-agent-checkpoint-sqlite` does the same with a real second JVM that it kills.
 */
object SqliteRestartRecoveryExample {

  private val thread  = ThreadId("report-42")
  private val reports = Vector("sales", "costs", "forecast")
  // short, so the example does not wait long for the dead process's claim to expire
  private val claims = ClaimPolicy(ttl = 3.seconds, renewEvery = 1.second)

  /** Counts each fetch, and holds the first `forecast` fetch until the first process has died. */
  final private class Fetches {
    val calls                   = new ConcurrentHashMap[String, AtomicInteger]()
    val forecastStarted         = new CountDownLatch(1)
    val processDied             = new CountDownLatch(1)
    def callsOf(r: String): Int = Option(calls.get(r)).fold(0)(_.get)
    private val results         = StateKey.appending[String]("results")

    val graph: CompiledGraph[Vector[String], Vector[String]] = {
      val b = GraphBuilder("report-fetch", "v1")
      val fetch = b.node[String]("fetch", writes = Set(results)) { (report, _, _) =>
        val n = calls.computeIfAbsent(report, _ => new AtomicInteger()).incrementAndGet()
        if (report == "forecast" && n == 1) {
          forecastStarted.countDown()
          processDied.await(30, TimeUnit.SECONDS): Unit
        }
        NodeResult.Continue(Command.empty.update(results, s"$report report"))
      }
      val combine = b.node[Unit]("combine")((_, _, _) => NodeResult.Continue(Command.empty))
      val join    = b.dynamicJoin("fetches", combine)
      val plan = b.node[Vector[String]]("plan") { (items, _, _) =>
        NodeResult.Continue(Command.empty.fanOut(join, fetch, items))
      }
      b.compile(plan)(_.get(results)).fold(e => sys.error(e.message), identity)
    }
  }

  def main(args: Array[String]): Unit =
    database(args.toVector) match {
      case Left(problem) => println(problem)
      case Right(db) =>
        val fetches = new Fetches
        // whatever happens, the held fetch is released, so a failure never leaves it waiting out its 30 s
        val result = Using.resource(Release(fetches.processDied))(_ => demo(db.path, fetches))
        result.left.foreach(e => println(s"Failed: ${e.message}"))
        db.cleanUp()
    }

  /** The demonstration proper, on the file at `db`; every store it opens is closed, whatever the outcome. */
  private def demo(db: Path, fetches: Fetches): Either[LLMError, Unit] = {
    println(s"Database: $db\n")
    println("--- process 1: start the run ---")
    for {
      _ <- SqliteCheckpointer.open(db).flatMap(first => Using.resource(first)(processOne(_, fetches)))
      _ = println("--- process 2: open the same file ---")
      _ <- SqliteCheckpointer.open(db).flatMap(second => Using.resource(second)(processTwo(_, fetches)))
    } yield ()
  }

  /** Starts the run, waits until two fetches are committed, then dies: its store is closed under the run. */
  private def processOne(first: SqliteCheckpointer, fetches: Fetches): Either[LLMError, Unit] =
    for {
      handle <- GraphRuntime(first, claims = claims).start(thread, fetches.graph, reports, run("process-1"))
      _ <- waitFor("the forecast fetch to start")(
        fetches.forecastStarted.await(patience.toMillis, TimeUnit.MILLISECONDS)
      )
      _ <- waitFor("two fetches to be committed")(awaitCompletedFetches(first, 2))
      _ = println("sales and costs are committed; forecast is still running")
      _ = println("--- process 1 dies (its store goes away mid-run) ---\n")
      _ = first.close()
      _ = fetches.processDied.countDown()
      outcome <- handle.await()
      _ = println(s"process 1's run ends without recording anything more: ${describe(outcome)}\n")
    } yield ()

  /** Replays the dead run's events, then recovers the run once its claim has expired. */
  private def processTwo(second: SqliteCheckpointer, fetches: Fetches): Either[LLMError, Unit] =
    for {
      log <- second.eventsAfter(thread, 0L, 100)
      _ = println("Replayed event log:")
      _ = log.foreach(e => println(f"  ${e.seq}%3d  ${e.runId}%-10s ${e.nodeId.getOrElse("")}%-8s ${e.event}"))
      handle <- recoverWhenFree(GraphRuntime(second, claims = claims), fetches, System.nanoTime() + patience.toNanos)
      done   <- handle.await()
      _ = println(s"\nRecovered: ${describe(done)}")
      _ = println(
        s"Fetch calls - sales: ${fetches.callsOf("sales")}, costs: ${fetches.callsOf("costs")}, " +
          s"forecast: ${fetches.callsOf("forecast")} (once in the dead run, once here)"
      )
    } yield ()

  /**
   * The database to use: a new file in a temporary directory, removed afterwards, when no path is given; else the
   * path given, kept afterwards. An existing file there is refused - this example starts a fresh run, and would
   * otherwise find the previous one - unless `--overwrite` is given too, which deletes it (with its `-wal` and
   * `-shm` files) first.
   */
  private def database(args: Vector[String]): Either[String, Database] = {
    val overwrite = args.contains("--overwrite")
    args.filterNot(_ == "--overwrite") match {
      case Vector() =>
        val dir = Files.createTempDirectory("llm4s-runs")
        Right(Database(dir.resolve("runs.db"), temporaryDirectory = Some(dir)))
      case Vector(file) =>
        val path = Path.of(file)
        if (Files.exists(path) && !overwrite)
          Left(s"$path already exists; pass --overwrite to delete it and start afresh, or name a new file")
        else {
          if (overwrite) deleteDatabase(path)
          Right(Database(path, temporaryDirectory = None))
        }
      case _ => Left("usage: SqliteRestartRecoveryExample [database-file [--overwrite]]")
    }
  }

  /** The file in use, and the temporary directory holding it, if it is one this example made. */
  final private case class Database(path: Path, temporaryDirectory: Option[Path]) {
    def cleanUp(): Unit = temporaryDirectory.foreach { dir =>
      deleteDatabase(path)
      Files.deleteIfExists(dir): Unit
    }
  }

  /** A SQLite database file and the files SQLite keeps beside it. */
  private def deleteDatabase(path: Path): Unit =
    Vector("", "-wal", "-shm", "-journal").foreach(suffix => Files.deleteIfExists(Path.of(s"$path$suffix")): Unit)

  final private case class Release(latch: CountDownLatch) extends AutoCloseable {
    def close(): Unit = latch.countDown()
  }

  private val patience = 30.seconds

  private def waitFor(what: String)(done: Boolean): Either[LLMError, Unit] =
    Either.cond(done, (), ProcessingError("sqlite-restart-example", s"timed out waiting for $what"))

  private def run(id: String): RunConfig = RunConfig().withRunId(RunId(id))

  /** Waits until `n` fetches have their results committed as pending writes; whether they were, in time. */
  private def awaitCompletedFetches(store: Checkpointer, n: Int): Boolean = {
    val deadline = System.nanoTime() + patience.toNanos
    def done     = store.latest(thread).toOption.flatten.exists(_.pendingWrites.count(_.nodeId == "fetch") >= n)
    while (!done && System.nanoTime() < deadline) Thread.sleep(10)
    done
  }

  /** `recover`, retried while the dead process's claim is still live, until `deadline`: `recover` never waits. */
  @annotation.tailrec
  private def recoverWhenFree(
    runtime: GraphRuntime,
    fetches: Fetches,
    deadline: Long,
    announced: Boolean = false
  ): Either[LLMError, RunHandle[Vector[String]]] =
    runtime.recover(thread, fetches.graph, run("process-2")) match {
      case busy @ Left(GraphError.ThreadBusy(_, _, _)) if System.nanoTime() >= deadline => busy
      case Left(GraphError.ThreadBusy(_, _, holder)) =>
        if (!announced)
          println(
            s"recover refused: ${holder.getOrElse("a run")} still holds the thread; retrying until its claim expires"
          )
        Thread.sleep(500)
        recoverWhenFree(runtime, fetches, deadline, announced = true)
      case other => other
    }

  private def describe[O](result: RunResult[O]): String = result match {
    case RunResult.Completed(_, output, _) => s"completed with $output"
    case RunResult.Failed(_, error)        => s"failed: ${error.message}"
    case RunResult.Suspended(_, _, _)      => "suspended"
  }
}
