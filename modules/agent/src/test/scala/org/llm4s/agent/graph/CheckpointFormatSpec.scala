package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class CheckpointFormatSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val renameField: ujson.Value => Result[ujson.Value] = json => Right(ujson.Obj("name" -> json("label")))
  private val wrap: ujson.Value => Result[ujson.Value]        = json => Right(ujson.Arr(json))

  "SchemaVersion" should "migrate one step at a time up to the current version" in {
    val v3 = SchemaVersion(3)(1 -> renameField, 2 -> wrap)
    v3.upgrade(1, ujson.Obj("label" -> "x")).value shouldBe ujson.Arr(ujson.Obj("name" -> "x"))
    v3.upgrade(2, ujson.Obj("name" -> "y")).value shouldBe ujson.Arr(ujson.Obj("name" -> "y"))
    v3.upgrade(3, ujson.Str("as-is")).value shouldBe ujson.Str("as-is")
  }

  it should "refuse newer, invalid and unmigratable versions" in {
    val v3 = SchemaVersion(3)(2 -> wrap)
    v3.upgrade(4, ujson.Null).left.value.message should include("version 4 cannot be read by a codec at version 3")
    v3.upgrade(0, ujson.Null).left.value.message should include("version 0 cannot be read")
    v3.upgrade(1, ujson.Null).left.value.message should include("no migration from version 1 to 2")
  }

  "A restore" should "migrate state values and pending inputs written by older codecs" in {
    final case class Item(name: String) derives upickle.default.ReadWriter
    val items = StateKey
      .appending[Item]("items")
      .withStateVersion(SchemaVersion(2)(1 -> { json =>
        Right(ujson.Arr.from(json.arr.map(old => ujson.Obj("name" -> old("label")))))
      }))
    val b = GraphBuilder("migrating", "v1")
    val sink = b.node[Item]("sink", writes = Set(items), inputVersion = SchemaVersion(2)(1 -> renameField)) {
      (item, _, _) => continue(Command.empty.update(items, item))
    }
    val graph = b.compile(sink)(_.get(items)).value

    val written = GraphSnapshot(
      graph.id,
      graph.version,
      graph.fingerprint,
      superstep = 3,
      state = Map("items" -> VersionedJson(1, ujson.Arr(ujson.Obj("label" -> "old")))),
      frontier = Vector(
        GraphSnapshot
          .PendingTask("3.0", "sink", VersionedJson(1, ujson.Obj("label" -> "pending")), None, None, None, None)
      ),
      staticJoins = Vector.empty,
      dynamicJoins = Vector.empty,
      parked = Vector.empty,
      paused = false
    )
    drive(graph, graph.restore(written).value).completed._2 shouldBe Vector(Item("old"), Item("pending"))

    val resnapshot = graph.snapshot(graph.restore(written).value).value
    resnapshot.state("items").version shouldBe 2
    resnapshot.frontier.head.input shouldBe VersionedJson(2, ujson.Obj("name" -> "pending"))
  }

  "Checkpoint" should "round-trip through JSON" in {
    val b     = GraphBuilder("g", "v1")
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty))
    val graph = b.compile(start)(_ => Right(())).value
    val checkpoint = Checkpoint(
      Checkpoint.CurrentFormat,
      "run-1/1",
      Some("run-0/4"),
      "thread",
      "run-1",
      CheckpointStatus.Running,
      Instant.parse("2026-10-02T12:00:00Z"),
      graph.snapshot(graph.start(())).value
    )
    Checkpoint.fromJson(Checkpoint.toJson(checkpoint)).value shouldBe checkpoint
  }

  it should "migrate a format-1 checkpoint, written before suspension existed" in {
    val b     = GraphBuilder("g", "v1")
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty))
    val graph = b.compile(start)(_ => Right(())).value
    val formatOne = ujson.Obj(
      "formatVersion" -> 1,
      "id"            -> "run-1/1",
      "parent"        -> upickle.default.writeJs(Option.empty[String]),
      "threadId"      -> "thread",
      "runId"         -> "run-1",
      "status"        -> upickle.default.writeJs[CheckpointStatus](CheckpointStatus.Running),
      "createdAt"     -> "2026-10-02T12:00:00Z",
      "snapshot" -> ujson.Obj(
        "graphId"      -> graph.id,
        "graphVersion" -> graph.version,
        "fingerprint"  -> graph.fingerprint,
        "superstep"    -> 0,
        "state"        -> ujson.Obj(),
        "frontier" -> ujson.Arr(
          ujson.Obj(
            "taskId"     -> "0.0",
            "nodeId"     -> "start",
            "input"      -> upickle.default.writeJs(VersionedJson(1, ujson.Null)),
            "joinId"     -> upickle.default.writeJs(Option.empty[String]),
            "fanOutTask" -> upickle.default.writeJs(Option.empty[String])
          )
        ),
        "staticJoins"  -> ujson.Arr(),
        "dynamicJoins" -> ujson.Arr()
      )
    )
    val migrated = Checkpoint.fromJson(formatOne).value
    migrated.formatVersion shouldBe Checkpoint.CurrentFormat
    migrated.tenantId shouldBe None
    migrated.snapshot.parked shouldBe empty
    migrated.snapshot.paused shouldBe false
    drive(graph, graph.restore(migrated.snapshot).value).completed
  }

  it should "migrate a format-2 checkpoint, written before tenants existed" in {
    val b     = GraphBuilder("g", "v1")
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty))
    val graph = b.compile(start)(_ => Right(())).value
    val current = Checkpoint(
      2,
      "run-1/1",
      None,
      "thread",
      "run-1",
      CheckpointStatus.Running,
      Instant.parse("2026-10-02T12:00:00Z"),
      graph.snapshot(graph.start(())).value
    )
    val formatTwo = ujson.copy(Checkpoint.toJson(current))
    formatTwo.obj.remove("tenantId")
    val migrated = Checkpoint.fromJson(formatTwo).value
    migrated.formatVersion shouldBe Checkpoint.CurrentFormat
    migrated.tenantId shouldBe None
    migrated shouldBe current.copy(formatVersion = Checkpoint.CurrentFormat)
  }

  it should "migrate a format-3 checkpoint, written before the Failed status existed" in {
    val b     = GraphBuilder("g", "v1")
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty))
    val graph = b.compile(start)(_ => Right(())).value
    val current = Checkpoint(
      3,
      "run-1/1",
      None,
      "thread",
      "run-1",
      CheckpointStatus.Completed,
      Instant.parse("2026-10-02T12:00:00Z"),
      graph.snapshot(graph.start(())).value
    )
    val migrated = Checkpoint.fromJson(Checkpoint.toJson(current)).value
    migrated shouldBe current.copy(formatVersion = Checkpoint.CurrentFormat)
  }

  it should "migrate a format-4 checkpoint, written before static breakpoints existed" in {
    val b     = GraphBuilder("g", "v1")
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty))
    val graph = b.compile(start)(_ => Right(())).value
    val current = Checkpoint(
      4,
      "run-1/1",
      None,
      "thread",
      "run-1",
      CheckpointStatus.Running,
      Instant.parse("2026-10-02T12:00:00Z"),
      graph.snapshot(graph.start(())).value
    )
    // a format-4 pending task has none of the breakpoint fields: they read as not passed and not replayed
    val written = Checkpoint.toJson(current)
    written("snapshot")("frontier")(0).obj.keySet should contain noneOf ("passedBefore", "replay")
    val migrated = Checkpoint.fromJson(written).value
    migrated shouldBe current.copy(formatVersion = Checkpoint.CurrentFormat)
    migrated.snapshot.frontier.head.passedBefore shouldBe false
    migrated.snapshot.frontier.head.replay shouldBe None
    Checkpoint.CurrentFormat shouldBe 5
  }

  it should "round-trip the terminal Failed status" in {
    val b     = GraphBuilder("g", "v1")
    val start = b.node[Unit]("start")((_, _, _) => continue(Command.empty))
    val graph = b.compile(start)(_ => Right(())).value
    val failed = Checkpoint(
      Checkpoint.CurrentFormat,
      "run-1/2",
      Some("run-1/1"),
      "thread",
      "run-1",
      CheckpointStatus.Failed,
      Instant.parse("2026-10-02T12:00:00Z"),
      graph.snapshot(graph.start(())).value
    )
    Checkpoint.toJson(failed)("status") shouldBe upickle.default.writeJs[CheckpointStatus](CheckpointStatus.Failed)
    Checkpoint.fromJson(Checkpoint.toJson(failed)).value shouldBe failed
  }

  it should "refuse a format it does not know" in {
    val newer = ujson.Obj("formatVersion" -> 6, "id" -> "x")
    Checkpoint.fromJson(newer).left.value shouldBe GraphError.UnsupportedCheckpointFormat(6, Checkpoint.CurrentFormat)
    Checkpoint.fromJson(ujson.Obj("id" -> "x")).left.value shouldBe
      GraphError.UnsupportedCheckpointFormat(0, Checkpoint.CurrentFormat)
  }

  "A pending write" should "encode a command as data and rebind it to the graph" in {
    val b      = GraphBuilder("writes", "v1")
    val log    = StateKey.appending[String]("log")
    val count  = StateKey.replace[Int]("count", 0)
    val worker = b.node[String]("worker")((_, _, _) => continue(Command.empty))
    val unit   = b.node[Unit]("unit")((_, _, _) => continue(Command.empty))
    val join   = b.dynamicJoin("j", unit)
    val start  = b.node[Unit]("start", writes = Set(log, count))((_, _, _) => continue(Command.empty))
    val graph  = b.compile(start)(_ => Right(())).value

    val command = Command.empty
      .update(log, "a")
      .remove(count)
      .update(count, 2)
      .goto(unit)
      .send(worker, "w")
      .fanOut(join, worker, Vector("x", "y"))
    val task  = Task(TaskId("0.0"), start.id, (), None)
    val write = graph.encodeWrite("cp", task, TaskResult.Done(command)).value
    upickle.default.read[PendingWrite](upickle.default.write(write)) shouldBe write
    graph.decodeWrite(write).value match {
      case TaskResult.Done(decoded) =>
        decoded.routes shouldBe command.routes
        decoded.update.operations shouldBe command.update.operations
      case other => fail(other.toString)
    }
  }

  it should "report keys, nodes, joins and payloads the graph does not know" in {
    val b                            = GraphBuilder("writes", "v1")
    val start                        = b.node[Unit]("start")((_, _, _) => continue(Command.empty))
    val graph                        = b.compile(start)(_ => Right(())).value
    def problem(write: PendingWrite) = graph.decodeWrite(write).left.value.message
    val base                         = PendingWrite("cp", "0.0", "start", Vector.empty, Vector.empty)

    problem(base.copy(operations = Vector(EncodedOperation.Remove("ghost")))) should include(
      "unknown state key 'ghost'"
    )
    problem(base.copy(routes = Vector(EncodedRoute.Goto("ghost")))) should include("unknown node 'ghost'")
    problem(base.copy(routes = Vector(EncodedRoute.FanOut("ghost", "start", Vector.empty)))) should
      include("unknown dynamic join 'ghost'")
    problem(base.copy(routes = Vector(EncodedRoute.Send("start", VersionedJson(1, ujson.Arr(1)))))) should
      include("input for 'start' does not decode")
  }

  it should "report an update its key cannot decode" in {
    val b     = GraphBuilder("writes", "v1")
    val count = StateKey.replace[Int]("count", 0)
    val start = b.node[Unit]("start", writes = Set(count))((_, _, _) => continue(Command.empty))
    val graph = b.compile(start)(_ => Right(())).value
    val write = PendingWrite(
      "cp",
      "0.0",
      "start",
      Vector(EncodedOperation.Update("count", VersionedJson(1, ujson.Str("nope")))),
      Vector.empty
    )
    graph.decodeWrite(write).left.value.message should include("update to 'count' does not decode")
  }

  "EventRecord" should "round-trip through JSON" in {
    val record = EventRecord(
      "thread",
      7L,
      "run",
      Some("run/1"),
      Some("1.0"),
      Some("worker"),
      Instant.parse("2026-10-02T12:00:00Z"),
      RunEvent.Custom("progress", 1, ujson.Obj("done" -> 3))
    )
    upickle.default.read[EventRecord](upickle.default.write(record)) shouldBe record
  }
}
