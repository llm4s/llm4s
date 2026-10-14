package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which changes to a graph's shape keep a checkpoint restorable (design §4.29): judged against what the restored run
 * can still observe - at rest only its state, in flight also its frontier, joins and pending writes.
 */
class GraphEvolutionSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with LoneElement {

  private val log   = StateKey.appending[String]("log")
  private val notes = StateKey.appending[String]("notes")
  private val extra = StateKey.replace[Int]("extra", 7)

  /** One change each from the base graph; every field `false` is the base. */
  final private case class Variant(
    version: String = "v1",
    addKey: Boolean = false,
    dropNotes: Boolean = false,
    addLoose: Boolean = false,
    edgeToLoose: Boolean = false,
    joinOverStored: Boolean = false,
    joinOverAdded: Boolean = false,
    dropSpare: Boolean = false,
    retargetWorkers: Boolean = false,
    addDynamic: Boolean = false,
    newEntry: Boolean = false,
    manyKeys: Int = 0
  )

  /**
   * The base: `start` logs its input and then, by input, suspends at `approve` (`ask`), fans out to `worker`s under
   * dynamic join `workers` -> `b` (`fan`), blocks (`block`), or goes to `a`. `approve` logs the answer and goes to
   * `a`; `a` logs and notes; a static edge runs `b` after `a`; `b` logs, and the value of `extra` when the graph has
   * it. `worker` logs and notes, and fails while `broken` is set. `spare` is never reached.
   */
  private def graph(v: Variant = Variant(), broken: AtomicBoolean = AtomicBoolean(false)) = {
    val b       = GraphBuilder("evolving", v.version)
    val noted   = if v.dropNotes then Set.empty[StateKey[?, ?]] else Set[StateKey[?, ?]](notes)
    val approve = b.declareResume[String, String]("approve")
    val nodeA   = b.declare[Unit]("a")
    val nodeB = b.node[Unit]("b", writes = Set(log)) { (_, state, _) =>
      if v.addKey then NodeResult.fromResult(state.get(extra).map(n => Command.empty.update(log, s"b:extra=$n")))
      else continue(Command.empty.update(log, "b"))
    }
    if v.addKey then b.stateKey(extra)
    (1 to v.manyKeys).foreach(i => b.stateKey(StateKey.replace[Int](s"k$i", 0)))
    b.implement(nodeA, writes = noted + log) { (_, _, _) =>
      val logged = Command.empty.update(log, "a")
      continue(if v.dropNotes then logged else logged.update(notes, "a"))
    }
    b.implement(approve.node, writes = Set(log)) { (resumed, _, _) =>
      continue(Command.empty.update(log, s"approved:${resumed.answer}").goto(nodeA))
    }
    b.edge(nodeA, nodeB)
    val worker = b.node[String]("worker", writes = noted + log) { (item, _, _) =>
      if broken.get && item == "y" then NodeResult.Fail(ValidationError("worker", "broken"))
      else
        val logged = Command.empty.update(log, s"w:$item")
        continue(if v.dropNotes then logged else logged.update(notes, item))
    }
    if !v.dropSpare then b.node[Unit]("spare")((_, _, _) => continue(Command.empty))
    val workers = b.dynamicJoin("workers", if v.retargetWorkers then nodeA else nodeB)
    if v.addDynamic then b.dynamicJoin("more", nodeB)
    if v.addLoose || v.edgeToLoose || v.joinOverAdded then
      val loose = b.node[Unit]("loose", writes = Set(log))((_, _, _) => continue(Command.empty.update(log, "loose")))
      b.edge(loose, nodeB) // an added node's edge into a stored node: allowed
      if v.edgeToLoose then b.edge(nodeA, loose)
      if v.joinOverAdded then b.staticJoin("added-only", Set(loose), nodeB)
    if v.joinOverStored then b.staticJoin("pair", Set(nodeA, worker), nodeB)
    val start = b.node[String]("start", writes = Set(log)) { (in, _, _) =>
      val logged = StateUpdate.update(log, s"start:$in")
      in match {
        case "ask"   => NodeResult.Suspend(logged, in, approve)
        case "fan"   => continue(Command(logged, Nil).fanOut(workers, worker, Vector("x", "y")))
        case "block" => NodeResult.Block(logged, ValidationError("start", "blocked"))
        case _       => continue(Command(logged, Nil).goto(nodeA))
      }
    }
    val entry =
      if v.newEntry then
        b.node[String]("start2", writes = Set(log))((in, _, _) => continue(Command.empty.update(log, in)))
      else start
    b.compile(entry)(_.get(log)).value
  }

  private val thread = ThreadId("evolving")

  private def runtime() = {
    val store = InMemoryCheckpointer()
    store -> GraphRuntime(store)
  }

  private def latest(store: Checkpointer): StoredCheckpoint = store.latest(thread).value.value

  /** The checkpoint of a run of `input` on the base graph, in a fresh store: completed, suspended or failed. */
  private def written(input: String, broken: Boolean = false): (InMemoryCheckpointer, GraphRuntime) = {
    val (store, rt) = runtime()
    rt.start(thread, graph(broken = AtomicBoolean(broken)), input).flatMap(awaitResult)
    store -> rt
  }

  private def atRest: GraphSnapshot = latest(written("go")._1).checkpoint.snapshot

  /** The base graph's execution after its first superstep: `a` pending, nothing noted yet. */
  private def inFlight: GraphSnapshot = {
    val g = graph()
    g.step(thread, g.start("go"), RunConfig()) match {
      case Step.Next(next) => g.snapshot(next).value
      case other           => fail(other.toString)
    }
  }

  private def problems(result: org.llm4s.types.Result[Execution]): List[String] =
    result.left.value match {
      case GraphError.RestoreRejected(_, found) => found
      case other                                => fail(s"expected RestoreRejected, got $other")
    }

  "A snapshot" should "carry its graph's shape" in {
    val g = graph()
    inFlight.shape shouldBe Some(g.shape)
    g.shape shouldBe GraphShape(
      entry = "start",
      nodes = Vector("a", "approve", "b", "spare", "start", "worker"),
      edges = Map("a" -> Vector("b")),
      staticJoins = Vector.empty,
      dynamicJoins = Map("workers" -> "b"),
      resumes = Vector("approve"),
      keys = Vector("log", "notes")
    )
    graph(Variant(joinOverStored = true)).shape.staticJoins shouldBe Vector(
      GraphShape.StaticJoin("pair", Vector("a", "worker"), "b")
    )
  }

  it should "round-trip its shape through JSON, and read one written without a shape as None" in {
    val snapshot = inFlight
    upickle.default.read[GraphSnapshot](upickle.default.write(snapshot)) shouldBe snapshot
    val json = upickle.default.writeJs(snapshot)
    json.obj.remove("shape")
    upickle.default.read[GraphSnapshot](json).shape shouldBe None
  }

  "A key added" should "restore a snapshot at rest, the key reading its initial value" in {
    val (store, rt) = written("go")
    val (_, output) = rt.start(thread, graph(Variant(addKey = true)), "again").awaited.value.completed
    output shouldBe Vector("start:go", "a", "b", "start:again", "a", "b:extra=7")
    latest(store).checkpoint.snapshot.shape.value.keys should contain("extra")
  }

  it should "restore a snapshot in flight, its frontier running against the added key" in {
    val g = graph(Variant(addKey = true))
    drive(g, g.restore(inFlight).value).completed._2 shouldBe Vector("start:go", "a", "b:extra=7")
  }

  it should "restore a suspended thread, which resumes" in {
    val (_, rt) = written("ask")
    val g       = graph(Variant(addKey = true))
    rt.resume(thread, g, Map(InterruptId("0.0") -> ujson.Str("yes"))).awaited.value.completed._2 shouldBe Vector(
      "start:ask",
      "approved:yes",
      "a",
      "b:extra=7"
    )
  }

  it should "restore a running thread with pending writes, which recovers" in {
    val (store, rt) = written("fan", broken = true)
    latest(store).checkpoint.status shouldBe CheckpointStatus.Running
    latest(store).pendingWrites should have size 1
    rt.recover(thread, graph(Variant(addKey = true))).awaited.value.completed._2 shouldBe
      Vector("start:fan", "w:x", "w:y", "b:extra=7")
  }

  "A key removed" should "restore when the snapshot holds no value for it" in {
    val g = graph(Variant(dropNotes = true))
    inFlight.state.keySet should not contain "notes"
    drive(g, g.restore(inFlight).value).completed._2 shouldBe Vector("start:go", "a", "b")
    val (_, rt) = written("ask")
    rt.resume(thread, g, Map(InterruptId("0.0") -> ujson.Str("ok"))).awaited.value.completed
  }

  it should "be refused when the snapshot holds a value for it, at rest or in flight" in {
    atRest.state.keySet should contain("notes")
    problems(graph(Variant(dropNotes = true)).restore(atRest)) shouldBe List(
      "state key 'notes' was removed from this graph, and the snapshot holds a value for it"
    )
    val (_, rt) = written("go")
    rt.start(thread, graph(Variant(dropNotes = true)), "again")
      .awaited
      .left
      .value shouldBe a[GraphError.RestoreRejected]
  }

  it should "be refused when a pending write updates it" in {
    val (store, rt) = written("fan", broken = true)
    latest(store).checkpoint.snapshot.state.keySet should not contain "notes"
    val refused = rt.recover(thread, graph(Variant(dropNotes = true))).awaited.left.value
    refused shouldBe a[GraphError.RestoreRejected]
    refused.message should include("unknown state key 'notes'")
  }

  "A node added" should "restore when no stored node reaches it, in flight" in {
    val g = graph(Variant(addLoose = true))
    drive(g, g.restore(inFlight).value).completed._2 shouldBe Vector("start:go", "a", "b")
  }

  it should "be refused in flight when a stored node gains an edge to it, and restore at rest" in {
    val g = graph(Variant(edgeToLoose = true))
    problems(g.restore(inFlight)) shouldBe List("the out-edges of node 'a' changed: b, then b, loose")
    g.restore(atRest).value
  }

  "A node removed" should "be refused at rest and in flight" in {
    val g = graph(Variant(dropSpare = true))
    problems(g.restore(inFlight)) shouldBe List("node 'spare' was removed from this graph")
    problems(g.restore(atRest)) shouldBe List("node 'spare' was removed from this graph")
  }

  "A static join" should "be refused in flight when added over a stored node, and restore at rest" in {
    val g = graph(Variant(joinOverStored = true))
    problems(g.restore(inFlight)) shouldBe List("static join 'pair', over stored nodes, was added")
    g.restore(atRest).value
  }

  it should "be refused in flight when removed or changed" in {
    val g = graph(Variant(joinOverStored = true))
    val s = g.step(thread, g.start("go"), RunConfig()) match {
      case Step.Next(next) => g.snapshot(next).value
      case other           => fail(other.toString)
    }
    problems(graph().restore(s)) shouldBe List("static join 'pair', over stored nodes, was removed")
    val changed =
      s.copy(shape = s.shape.map(sh => sh.copy(staticJoins = Vector(GraphShape.StaticJoin("pair", Vector("a"), "b")))))
    problems(g.restore(changed)) shouldBe List("static join 'pair' changed: a -> b, then a, worker -> b")
  }

  it should "restore in flight when added over added nodes only" in {
    graph(Variant(joinOverAdded = true)).restore(inFlight).value
  }

  "A dynamic join" should "be refused in flight when retargeted, and restore at rest" in {
    val g = graph(Variant(retargetWorkers = true))
    problems(g.restore(inFlight)) shouldBe List("dynamic join 'workers' changed its target: b, then a")
    g.restore(atRest).value
  }

  it should "restore in flight when added" in {
    graph(Variant(addDynamic = true)).restore(inFlight).value
  }

  "A changed entry node" should "restore in flight and at rest" in {
    val g = graph(Variant(newEntry = true))
    drive(g, g.restore(inFlight).value).completed._2 shouldBe Vector("start:go", "a", "b")
    g.restore(atRest).value
  }

  "A changed version" should "restore a snapshot at rest" in {
    graph(Variant(version = "v2")).restore(atRest).value.state.get(log).value shouldBe Vector("start:go", "a", "b")
  }

  it should "be refused in flight" in {
    problems(graph(Variant(version = "v2")).restore(inFlight)) shouldBe List(
      "snapshot is of version 'v1', this graph is version 'v2'; a run in flight restores only into its own version"
    )
  }

  it should "let a thread whose run was blocked start again, its frontier never run" in {
    val (store, rt) = written("block")
    latest(store).checkpoint.status shouldBe CheckpointStatus.Failed
    rt.start(thread, graph(Variant(version = "v2")), "go").awaited.value.completed._2 shouldBe
      Vector("start:block", "start:go", "a", "b")
  }

  it should "let a completed thread start again at a new version" in {
    val (_, rt) = written("go")
    rt.start(thread, graph(Variant(version = "v2")), "again").awaited.value.completed
  }

  "A snapshot written before shapes were recorded" should "restore into its own shape" in {
    graph().restore(inFlight.copy(shape = None)).value
    graph().restore(atRest.copy(shape = None)).value
  }

  it should "restore into the graph with keys added, at rest and in flight" in {
    val g = graph(Variant(addKey = true, manyKeys = 3))
    drive(g, g.restore(inFlight.copy(shape = None)).value).completed._2 shouldBe Vector("start:go", "a", "b:extra=7")
    g.restore(atRest.copy(shape = None)).value
  }

  it should "be refused when the graph changed by more than added keys" in {
    val legacy = "the snapshot was written before graph shapes were recorded, " +
      "and this graph differs from its graph by more than added keys"
    problems(graph(Variant(addLoose = true)).restore(inFlight.copy(shape = None))) shouldBe List(legacy)
    problems(graph(Variant(dropNotes = true)).restore(inFlight.copy(shape = None))) shouldBe List(legacy)
    (problems(graph(Variant(version = "v2")).restore(atRest.copy(shape = None))) should contain).allOf(
      legacy,
      "snapshot is of version 'v1', this graph is version 'v2'; a snapshot without a shape restores only into its own version"
    )
  }

  it should "search at most 16 keys the snapshot holds no value for" in {
    // `notes` holds no value in flight either
    graph(Variant(manyKeys = 15)).restore(inFlight.copy(shape = None)).value
    problems(graph(Variant(manyKeys = 16)).restore(inFlight.copy(shape = None))).loneElement should include(
      "more than added keys"
    )
  }

  it should "carry a shape once its thread runs again" in {
    val store = InMemoryCheckpointer()
    // reads every checkpoint as main wrote it: without a shape
    val legacy = new ForwardingCheckpointer(store) {
      override def latest(threadId: ThreadId) =
        store
          .latest(threadId)
          .map(_.map(s => s.copy(checkpoint = s.checkpoint.withSnapshot(s.checkpoint.snapshot.copy(shape = None)))))
    }
    GraphRuntime(store).start(thread, graph(), "go").awaited.value.completed
    GraphRuntime(legacy).start(thread, graph(Variant(addKey = true)), "again").awaited.value.completed
    latest(store).checkpoint.snapshot.shape shouldBe Some(graph(Variant(addKey = true)).shape)
  }
}
