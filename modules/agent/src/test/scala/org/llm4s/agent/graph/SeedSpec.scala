package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `GraphRuntime.seed` creates a thread over given state with no run (design 4.13): a completed thread that `start`
 * continues like any other, which is how a conversation held as data becomes a thread.
 */
class SeedSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("thread-1")
  private val notes  = StateKey.appending[String]("notes")

  /** `echo` records its input after whatever the thread already holds; the output is every note. */
  private val graph: CompiledGraph[String, Vector[String]] = {
    val b    = GraphBuilder("seedable", "v1")
    val echo = b.node[String]("echo", writes = Set(notes))((text, _, _) => continue(Command.empty.update(notes, text)))
    b.compile(echo)(_.get(notes)).value
  }

  private def seedNotes(values: String*): StateUpdate =
    values.foldLeft(StateUpdate.empty)((update, v) => update.combine(StateUpdate.update(notes, v)))

  "seed" should "create a completed thread over the given state, which start continues" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)

    runtime.seed(thread, graph, seedNotes("a", "b")).value

    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Completed
    runtime.start(thread, graph, "c").awaited.value.completed._2 shouldBe Vector("a", "b", "c")
  }

  it should "record one ThreadSeeded event and no run" in {
    val store = InMemoryCheckpointer()
    GraphRuntime(store).seed(thread, graph, seedNotes("a")).value

    val events = store.eventsAfter(thread, 0L, 10).value
    events.map(_.event) shouldBe Vector(RunEvent.ThreadSeeded)
    events.head.seq shouldBe 1L
  }

  it should "seed an empty state, as the graph's initial state" in {
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime.seed(thread, graph, StateUpdate.empty).value
    runtime.start(thread, graph, "only").awaited.value.completed._2 shouldBe Vector("only")
  }

  it should "refuse a thread that already has a checkpoint, leaving it unchanged" in {
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.start(thread, graph, "first").awaited.value.completed
    val before = store.latest(thread).value

    runtime.seed(thread, graph, seedNotes("x")).left.value shouldBe GraphError.ThreadExists(thread.value)

    store.latest(thread).value shouldBe before
  }

  it should "refuse a thread twice, the second seed seeing the first" in {
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime.seed(thread, graph, seedNotes("a")).value
    runtime.seed(thread, graph, seedNotes("b")).left.value shouldBe GraphError.ThreadExists(thread.value)
    runtime.start(thread, graph, "c").awaited.value.completed._2 shouldBe Vector("a", "c")
  }

  it should "release the thread once seeded, so a seed that failed does not hold it" in {
    val runtime = GraphRuntime(InMemoryCheckpointer())
    val foreign = StateKey.appending[String]("not-in-this-graph")

    runtime.seed(thread, graph, StateUpdate.update(foreign, "x")).left.value shouldBe a[GraphError.UnknownStateKey]

    runtime.seed(thread, graph, seedNotes("ok")).value
  }

  it should "belong to the tenant that seeded it" in {
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime.seed(thread, graph, seedNotes("a"), RunConfig().withTenantId(TenantId("acme"))).value

    runtime.start(thread, graph, "x").left.value shouldBe GraphError.TenantMismatch(thread.value, None)
    runtime
      .start(thread, graph, "x", RunConfig().withTenantId(TenantId("acme")))
      .awaited
      .value
      .completed
      ._2 shouldBe Vector("a", "x")
  }
}
