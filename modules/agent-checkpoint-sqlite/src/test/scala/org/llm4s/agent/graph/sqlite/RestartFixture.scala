package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.error.ValidationError

import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * A graph that fans its items out to one worker task each, behind a dynamic join, and collects their results in
 * task order. What a worker does for an item is the caller's: `work` returns `Left` to fail the task. The same
 * graph - same name, version and structure - is built by every process and every restart, as a deployed graph is.
 */
final class Workers(work: String => Either[String, Unit] = _ => Right(())):
  val calls   = new ConcurrentHashMap[String, AtomicInteger]()
  val results = StateKey.appending[String]("results")

  def callsOf(item: String): Int = Option(calls.get(item)).fold(0)(_.get)

  val graph: CompiledGraph[Vector[String], Vector[String]] =
    val b = GraphBuilder("sqlite-restart", "v1")
    val worker = b.node[String]("worker", writes = Set(results)) { (item, _, _) =>
      calls.computeIfAbsent(item, _ => new AtomicInteger()).incrementAndGet()
      work(item) match
        case Left(problem) => NodeResult.Fail(ValidationError("worker", problem))
        case Right(())     => NodeResult.Continue(Command.empty.update(results, item.toUpperCase))
    }
    val collect = b.node[Unit]("collect")((_, _, _) => NodeResult.Continue(Command.empty))
    val join    = b.dynamicJoin("workers", collect)
    val plan = b.node[Vector[String]]("plan") { (items, _, _) =>
      NodeResult.Continue(Command.empty.fanOut(join, worker, items))
    }
    b.compile(plan)(_.get(results)).fold(e => throw new IllegalStateException(e.message), identity)

/**
 * The process the cross-process spec kills: it opens the store in the file `args(0)`, starts a run `child` on thread
 * `args(1)` over items `a` and `b`, and never finishes it - `a` completes, `b` blocks - renewing its claim every
 * 200 ms until it is killed. Its claim lasts an hour, so it is the killing, not the clock, that ends it.
 */
object CrashingRunMain:
  val ClaimTtl: FiniteDuration = 1.hour

  def main(args: Array[String]): Unit =
    val store   = SqliteFiles.opened(Path.of(args(0)), Clock.systemUTC())
    val runtime = GraphRuntime(store, claims = ClaimPolicy(ttl = ClaimTtl, renewEvery = 200.millis))
    val workers = Workers(item => Right(if item == "b" then Thread.sleep(Long.MaxValue)))
    runtime
      .start(ThreadId(args(1)), workers.graph, Vector("a", "b"), RunConfig().withRunId(RunId("child")))
      .fold(e => throw new IllegalStateException(e.message), _.await()): Unit
