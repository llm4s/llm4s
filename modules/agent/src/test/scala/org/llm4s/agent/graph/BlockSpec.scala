package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A node that returns `NodeResult.Block` ends the run as a finished failure (design 4.13): its update commits with
 * the superstep, the thread's closing checkpoint is `Failed`, the caller gets the node's own error, the thread is
 * usable afterwards and `recover` has nothing to continue.
 */
class BlockSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("thread-1")
  private val log    = StateKey.appending[String]("log")
  private val ran    = StateKey.appending[String]("ran")

  private def refusal(input: String): ValidationError = ValidationError("guard", s"$input is not allowed")

  /** `guard` blocks an input that starts with "bad", after logging it; otherwise it logs and routes to `next`. */
  private val guarded: CompiledGraph[String, Vector[String]] = {
    val b    = GraphBuilder("guarded", "v1")
    val next = b.node[Unit]("next", writes = Set(ran))((_, _, _) => continue(Command.empty.update(ran, "next ran")))
    val guard = b.node[String]("guard", writes = Set(log)) { (input, _, _) =>
      if input.startsWith("bad") then NodeResult.Block(StateUpdate.update(log, s"blocked:$input"), refusal(input))
      else continue(Command.empty.update(log, s"ok:$input").goto(next))
    }
    b.compile(guard)(_.get(log)).value
  }

  /** `plan` sends three payloads to one `worker`; the one that says "stop" blocks, the others log. */
  private val siblings: CompiledGraph[Unit, Vector[String]] = {
    val b    = GraphBuilder("siblings", "v1")
    val tail = b.node[Unit]("tail", writes = Set(ran))((_, _, _) => continue(Command.empty.update(ran, "tail ran")))
    val worker = b.node[String]("worker", writes = Set(log)) { (item, _, _) =>
      if item == "stop" then NodeResult.Block(StateUpdate.update(log, "worker:stop"), refusal(item))
      else continue(Command.empty.update(log, s"worker:$item").goto(tail))
    }
    val plan = b.node[Unit]("plan") { (_, _, _) =>
      continue(Command.empty.send(worker, "a").send(worker, "stop").send(worker, "c"))
    }
    b.compile(plan)(_.get(log)).value
  }

  private def kinds(records: Vector[EventRecord]): Vector[String] =
    records.map(r => r.event.toString.takeWhile(_ != '(') + r.nodeId.fold("")(n => s"@$n"))

  "a blocked task" should "end the run with its own error, not one wrapped in NodeFailed, and commit its update" in {
    val (state, error) = runInMemory(guarded, "bad-1").failed

    error shouldBe refusal("bad-1")
    state.get(log).value shouldBe Vector("blocked:bad-1")
    state.get(ran).value shouldBe empty
  }

  it should "close the thread's checkpoint as Failed, with a RunFailed event and no further superstep" in {
    val store = InMemoryCheckpointer()
    GraphRuntime(store).start(thread, guarded, "bad-1").awaited.value.failed

    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Failed
    val events = store.eventsAfter(thread, 0L, 100).value
    kinds(events) shouldBe Vector("RunStarted", "TaskFailed@guard", "RunFailed")
    events.last.event shouldBe RunEvent.RunFailed(refusal("bad-1").message)
  }

  it should "leave the thread usable: start applies a new input over the committed state" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.start(thread, guarded, "bad-1").awaited.value.failed

    val (state, output) = runtime.start(thread, guarded, "good-2").awaited.value.completed

    output shouldBe Vector("blocked:bad-1", "ok:good-2")
    state.get(ran).value shouldBe Vector("next ran")
    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
  }

  it should "refuse recover and resume, leaving the thread as it was" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.start(thread, guarded, "bad-1").awaited.value.failed
    val before = store.latest(thread).value

    runtime.recover(thread, guarded).left.value shouldBe GraphError.NothingToRecover(thread.value)
    runtime
      .resume(thread, guarded, Map(InterruptId("1.0") -> ujson.Null))
      .left
      .value shouldBe GraphError.NotSuspended(thread.value)
    store.latest(thread).value shouldBe before
  }

  it should "commit the siblings' results with its own update, in frontier order, and schedule nothing after" in {
    val store          = InMemoryCheckpointer()
    val (state, error) = GraphRuntime(store).start(thread, siblings, ()).awaited.value.failed

    error shouldBe refusal("stop")
    state.get(log).value shouldBe Vector("worker:a", "worker:stop", "worker:c")
    // `tail` was routed to by the two siblings that completed, and does not run: the run ended with the superstep
    state.get(ran).value shouldBe empty
    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Failed
  }

  it should "take the first blocked task's error when several block" in {
    val b      = GraphBuilder("many", "v1")
    val worker = b.node[String]("worker")((item, _, _) => NodeResult.Block(StateUpdate.empty, refusal(item)))
    val plan = b.node[Unit]("plan") { (_, _, _) =>
      continue(Command.empty.send(worker, "first").send(worker, "second"))
    }
    val many = b.compile(plan)(_ => Right(())).value

    runInMemory(many, ()).failed._2 shouldBe refusal("first")
  }

  it should "fail the run, leaving it Running, when its update writes a key the node does not declare" in {
    val b = GraphBuilder("undeclared", "v1")
    val guard = b.node[String]("guard", writes = Set(log)) { (_, _, _) =>
      NodeResult.Block(StateUpdate.update(ran, "sneaky"), refusal("x"))
    }
    val broken = b.compile(guard)(_.get(log)).value
    val store  = InMemoryCheckpointer()

    val (_, error) = GraphRuntime(store).start(thread, broken, "x").awaited.value.failed

    error shouldBe a[GraphError.UndeclaredWrite]
    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Running
  }

  it should "end an in-memory run the same way when the graph is stepped without a runtime" in {
    val (state, error) = drive(guarded, guarded.start("bad-1")).failed

    error shouldBe refusal("bad-1")
    state.get(log).value shouldBe Vector("blocked:bad-1")
    state.get(ran).value shouldBe empty
  }

  it should "be run again by recover when the process dies before the closing commit" in {
    // a store that goes down as the run closes: the Failed checkpoint is never committed
    val underlying = InMemoryCheckpointer()
    val closing = new Checkpointer {
      def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
        if commit.checkpoint.exists(_.status == CheckpointStatus.Failed) then Left(ValidationError("store", "down"))
        else underlying.commit(threadId, commit)
      def latest(threadId: ThreadId) = underlying.latest(threadId)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) =
        underlying.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long) = underlying.compactEvents(threadId, beforeSeq)
      def deleteThread(threadId: ThreadId)                   = underlying.deleteThread(threadId)
    }

    val (_, error) = GraphRuntime(closing).start(thread, guarded, "bad-1").awaited.value.failed
    error shouldBe a[GraphError.CheckpointWriteFailed]
    underlying.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Running

    // the blocked task wrote nothing, so recovery runs the guard again, which blocks again
    val (state, again) = GraphRuntime(underlying).recover(thread, guarded).awaited.value.failed
    again shouldBe refusal("bad-1")
    state.get(log).value shouldBe Vector("blocked:bad-1")
    underlying.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Failed
  }

  "a Failed checkpoint" should "round-trip through JSON and be written in the current format" in {
    val store = InMemoryCheckpointer()
    GraphRuntime(store).start(thread, guarded, "bad-1").awaited.value.failed
    val checkpoint = store.latest(thread).value.value.checkpoint

    checkpoint.formatVersion shouldBe Checkpoint.CurrentFormat
    Checkpoint.CurrentFormat shouldBe 4
    Checkpoint.fromJson(Checkpoint.toJson(checkpoint)).value shouldBe checkpoint
  }
}
