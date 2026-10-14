package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.error.ValidationError

import java.nio.file.{ Files, Path, StandardCopyOption }
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The graph of `evolving-graph.db`, a SQLite file written by `main` before #1934 recorded a graph's shape in its
 * checkpoints. Graph `evolving` at version `v1`: `plan` logs its input, then suspends at `approve` for an input
 * starting `ask`, else goes to `work`; `approve` logs the answer and goes to `work`; `work` logs, notes, and fails
 * while `broken` is set; a static edge runs `done` after `work`. The file holds three threads:
 *  - `at-rest`: one completed run of `hello`;
 *  - `suspended`: a run of `ask-me`, parked at `approve`;
 *  - `running`: a run of `crash`, whose `work` failed, so its latest checkpoint is `Running` with `work` pending.
 *
 * The variants change the shape: `tally` adds a key `done` counts in, `audit` adds a node nothing reaches,
 * `withoutNotes` removes the `notes` key every thread has written.
 */
object EvolvingGraphFixture:
  val Resource           = "/fixtures/evolving-graph.db"
  val AtRest: ThreadId   = ThreadId("at-rest")
  val Waiting: ThreadId  = ThreadId("suspended")
  val Crashed: ThreadId  = ThreadId("running")
  val log                = StateKey.appending[String]("log")
  val notes              = StateKey.appending[String]("notes")
  val tally              = StateKey.replace[Int]("tally", 0)

  final case class Evolving(graph: CompiledGraph[String, Vector[String]], approve: ResumeRef[String, String])

  def graph(
    broken: AtomicBoolean = AtomicBoolean(false),
    tallied: Boolean = false,
    audit: Boolean = false,
    withoutNotes: Boolean = false
  ): Evolving =
    val b       = GraphBuilder("evolving", "v1")
    val done    = b.declare[Unit]("done")
    val work    = b.declare[Unit]("work")
    val approve = b.declareResume[String, String]("approve")
    val kept    = if withoutNotes then Set.empty[StateKey[?, ?]] else Set[StateKey[?, ?]](notes)
    b.implement(done, writes = if tallied then Set(log, tally) else Set(log)) { (_, state, _) =>
      val logged = Command.empty.update(log, "done")
      if tallied then
        state.get(tally).fold(NodeResult.Fail(_), n => NodeResult.Continue(logged.update(tally, n + 1)))
      else NodeResult.Continue(logged)
    }
    b.implement(work, writes = kept + log) { (_, _, _) =>
      if broken.get then NodeResult.Fail(ValidationError("work", "broken"))
      else
        val logged = Command.empty.update(log, "work")
        NodeResult.Continue(if withoutNotes then logged else logged.update(notes, "noted"))
    }
    b.implement(approve.node, writes = Set(log)) { (resumed, _, _) =>
      NodeResult.Continue(Command.empty.update(log, s"approved:${resumed.answer}").goto(work))
    }
    b.edge(work, done)
    val plan = b.node[String]("plan", writes = Set(log)) { (in, _, _) =>
      if in.startsWith("ask") then NodeResult.Suspend(StateUpdate.update(log, s"plan:$in"), in, approve)
      else NodeResult.Continue(Command.empty.update(log, s"plan:$in").goto(work))
    }
    if audit then b.node[Unit]("audit", writes = Set(log))((_, _, _) => NodeResult.Continue(Command.empty))
    val compiled = b.compile(plan)(_.get(log)).fold(e => throw new IllegalStateException(e.message), identity)
    Evolving(compiled, approve)

  /** A copy of the fixture file, opened. */
  def opened(): SqliteCheckpointer =
    val db = SqliteFiles.fresh()
    Files.copy(getClass.getResourceAsStream(Resource), db, StandardCopyOption.REPLACE_EXISTING)
    SqliteFiles.opened(db, Clock.systemUTC())

/** Writes `evolving-graph.db`; run once, on `main` before #1934, with the target path as its argument. */
object WriteEvolvingGraphFixture:
  import EvolvingGraphFixture.*

  def main(args: Array[String]): Unit =
    val target = Path.of(args(0))
    SqliteFiles.delete(target)
    val store   = SqliteFiles.opened(target, Clock.systemUTC())
    val runtime = GraphRuntime(store)
    val broken  = AtomicBoolean(false)
    val fixture = graph(broken).graph
    def ran(thread: ThreadId, input: String) = runtime.start(thread, fixture, input).flatMap(_.await())
    val first                                = ran(AtRest, "hello")
    require(first.exists(_.isInstanceOf[RunResult.Completed[?]]), s"at-rest: $first")
    val second = ran(Waiting, "ask-me")
    require(second.exists(_.isInstanceOf[RunResult.Suspended]), s"suspended: $second")
    broken.set(true)
    val third = ran(Crashed, "crash")
    require(third.exists(_.isInstanceOf[RunResult.Failed]), s"running: $third")
    require(store.latest(Crashed).exists(_.exists(_.checkpoint.status == CheckpointStatus.Running)), "not running")
    store.close()
