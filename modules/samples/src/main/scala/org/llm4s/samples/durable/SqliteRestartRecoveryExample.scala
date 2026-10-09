package org.llm4s.samples.durable

import org.llm4s.agent.graph._
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.error.LLMError

import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, TimeUnit }
import scala.concurrent.duration._

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
 *   sbt "samples/runMain org.llm4s.samples.durable.SqliteRestartRecoveryExample /tmp/runs.db"  # a file you keep
 * }}}
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

  def main(args: Array[String]): Unit = {
    val db = args.headOption.map(Path.of(_)).getOrElse(Files.createTempFile("llm4s-runs", ".db"))
    // a fresh run on a fresh thread each time
    Files.deleteIfExists(db): Unit
    val fetches = new Fetches

    println(s"Database: $db\n")
    println("--- process 1: start the run ---")
    val result = for {
      first  <- SqliteCheckpointer.open(db)
      handle <- GraphRuntime(first, claims = claims).start(thread, fetches.graph, reports, run("process-1"))
      _ = fetches.forecastStarted.await(30, TimeUnit.SECONDS)
      _ = awaitCompletedFetches(first, 2)
      _ = println("sales and costs are committed; forecast is still running")
      _ = println("--- process 1 dies (its store goes away mid-run) ---\n")
      _ = first.close()
      _ = fetches.processDied.countDown()
      outcome <- handle.await()
      _ = println(s"process 1's run ends without recording anything more: ${describe(outcome)}\n")

      _ = println("--- process 2: open the same file ---")
      second <- SqliteCheckpointer.open(db)
      log    <- second.eventsAfter(thread, 0L, 100)
      _       = println("Replayed event log:")
      _       = log.foreach(e => println(f"  ${e.seq}%3d  ${e.runId}%-10s ${e.nodeId.getOrElse("")}%-8s ${e.event}"))
      runtime = GraphRuntime(second, claims = claims)
      handle2 <- recoverWhenFree(runtime, fetches)
      done    <- handle2.await()
      _ = println(s"\nRecovered: ${describe(done)}")
      _ = println(
        s"Fetch calls - sales: ${fetches.callsOf("sales")}, costs: ${fetches.callsOf("costs")}, " +
          s"forecast: ${fetches.callsOf("forecast")} (once in the dead run, once here)"
      )
      _ = second.close()
    } yield ()
    result.left.foreach(e => println(s"Failed: ${e.message}"))
    if (args.isEmpty) Files.deleteIfExists(db): Unit
  }

  private def run(id: String): RunConfig = RunConfig().withRunId(RunId(id))

  /** Waits until `n` fetches have their results committed as pending writes. */
  private def awaitCompletedFetches(store: Checkpointer, n: Int): Unit = {
    val deadline = System.nanoTime() + 30.seconds.toNanos
    def done     = store.latest(thread).toOption.flatten.exists(_.pendingWrites.count(_.nodeId == "fetch") >= n)
    while (!done && System.nanoTime() < deadline) Thread.sleep(10)
  }

  /** `recover`, retried while the dead process's claim is still live: `recover` never waits by itself. */
  @annotation.tailrec
  private def recoverWhenFree(
    runtime: GraphRuntime,
    fetches: Fetches,
    announced: Boolean = false
  ): Either[LLMError, RunHandle[Vector[String]]] =
    runtime.recover(thread, fetches.graph, run("process-2")) match {
      case Left(GraphError.ThreadBusy(_, _, holder)) =>
        if (!announced)
          println(
            s"recover refused: ${holder.getOrElse("a run")} still holds the thread; retrying until its claim expires"
          )
        Thread.sleep(500)
        recoverWhenFree(runtime, fetches, announced = true)
      case other => other
    }

  private def describe[O](result: RunResult[O]): String = result match {
    case RunResult.Completed(_, output, _) => s"completed with $output"
    case RunResult.Failed(_, error)        => s"failed: ${error.message}"
    case RunResult.Suspended(_, _, _)      => "suspended"
  }
}
