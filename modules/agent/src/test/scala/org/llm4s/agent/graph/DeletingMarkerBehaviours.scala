package org.llm4s.agent.graph

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.{ AnyFlatSpec, AnyFlatSpecLike }
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A cascade delete marks its root deleting before the first descendant goes (design 4.41, #2074), on each store: a
 * cascade a failure stopped after a child was deleted leaves the root refusing every admission with
 * [[GraphError.ThreadDeleting]] - in this runtime and in one opened again on the same storage, as after a crash - and
 * a second `deleteThread` finishes it. Each store's spec gives `storage`: a function that opens the same storage again.
 */
trait DeletingMarkerBehaviours extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues {

  /** A new storage: each call of the function it returns opens that same storage again. */
  protected def storage(): () => Checkpointer

  private val log     = StateKey.appending[String]("log")
  private val worker  = AgentId.unsafe("worker")
  private val failing = new AtomicBoolean(false)

  /** `act` logs its input; while `failing` is set, `fail` fails it, leaving the thread `Running`. */
  private val graph: CompiledGraph[String, Vector[String]] = {
    val b = GraphBuilder("deleting", "v1")
    val act = b.node[String]("act", writes = Set(log)) { (input, _, _) =>
      if input == "fail" && failing.get then NodeResult.Fail(ValidationError("act", "down"))
      else continue(Command.empty.update(log, input))
    }
    b.compile(act)(_.get(log)).value
  }

  private def childOf(parent: ThreadId, n: Int): ThreadId = ThreadId.child(parent, worker, IdempotencyKey(s"call-$n"))

  private def startChild(runtime: GraphRuntime, parent: ThreadId, n: Int): ThreadId =
    val id     = childOf(parent, n)
    val origin = RunOrigin(parent, IdempotencyKey(s"call-$n"), worker, java.time.Instant.now(), 1)
    runtime.startChild(id, graph, "go", origin, RunConfig()).awaited.value: Unit
    id

  "a cascade a failure stopped after its first child delete" should
    "leave the root refusing start, recover, resume, fork and updateState, until deleteThread finishes it" in {
      val open      = storage()
      val store     = open()
      val parent    = ThreadId("deleting-parent")
      val stopAfter = new AtomicBoolean(true)
      // the second child's delete fails, as a crash after the first child delete would stop the cascade
      def flaky(to: Checkpointer): Checkpointer = new ForwardingCheckpointer(to) {
        override def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) =
          if stopAfter.get && threadId == childOf(parent, 2) then Left(ValidationError("store", "down"))
          else to.deleteThread(threadId, token, fence)
      }
      val runtime = GraphRuntime(flaky(store))
      failing.set(true)
      try runtime.start(parent, graph, "fail").awaited.value.failed
      finally failing.set(false)
      // the parent is Running: without the marker, recover would replay its task
      store.latest(parent).value.value.checkpoint.status shouldBe CheckpointStatus.Running
      val first  = startChild(runtime, parent, 1)
      val second = startChild(runtime, parent, 2)

      runtime.deleteThread(parent).isLeft shouldBe true
      store.latest(first).value shouldBe None
      store.latest(second).value shouldBe defined
      val marked = store.latest(parent).value.value.checkpoint
      marked.deleting shouldBe true

      val refused = GraphError.ThreadDeleting(parent.value)
      // a runtime opened again on the same storage - a restart - refuses it alike
      val restarted = GraphRuntime(flaky(open()))
      Vector(runtime, restarted).foreach { rt =>
        rt.start(parent, graph, "again").left.value shouldBe refused
        rt.recover(parent, graph).left.value shouldBe refused
        rt.resume(parent, graph, Map(InterruptId("0.0") -> ujson.Str("yes"))).left.value shouldBe refused
        rt.fork(parent, marked.id, ThreadId("deleting-fork")).left.value shouldBe refused
        rt.updateState(parent, graph, marked.id, StateUpdate.update(log, "x")).left.value shouldBe refused
      }
      store.latest(ThreadId("deleting-fork")).value shouldBe None
      store.latest(parent).value.value.checkpoint shouldBe marked

      stopAfter.set(false)
      restarted.deleteThread(parent).value
      Vector(parent, first, second).map(store.latest(_).value) shouldBe Vector(None, None, None)
      // the id is free again: a new thread under it is not deleting
      restarted.start(parent, graph, "new").awaited.value.completed
    }

  "a delete of a thread with no children" should "write no marker, deleting it at once" in {
    val store   = storage()()
    val parent  = ThreadId("deleting-leaf")
    val commits = new java.util.concurrent.atomic.AtomicInteger(0)
    val counting = new ForwardingCheckpointer(store) {
      override def commit(threadId: ThreadId, commit: Commit) =
        commits.incrementAndGet()
        store.commit(threadId, commit)
    }
    val runtime = GraphRuntime(counting)
    runtime.start(parent, graph, "go").awaited.value.completed
    commits.set(0)
    runtime.deleteThread(parent).value
    commits.get shouldBe 0
    store.latest(parent).value shouldBe None
  }
}

/** [[DeletingMarkerBehaviours]] on the in-memory store. */
class DeletingMarkerSpec extends AnyFlatSpec with DeletingMarkerBehaviours {
  protected def storage(): () => Checkpointer = {
    val store = InMemoryCheckpointer()
    () => store
  }
}
