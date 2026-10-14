package org.llm4s.agent.graph

import org.llm4s.agent.graph.tool.ToolContext
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Task keys, effect keys, effect records and the liveness that releases them (design §4.24). */
class EffectKeySpec extends AnyFlatSpec with Matchers with EitherValues {

  private def context(
    thread: String = "t",
    task: String = "3.1",
    superstep: Int = 3,
    run: String = "r",
    checkpoint: String = "r/3",
    token: Option[Long] = Some(7L)
  ): RunContext =
    new RunContext(
      RunConfig(),
      RunPosition(
        ThreadId(thread),
        RunId(run),
        checkpoint,
        TaskId(task),
        NodeId("n"),
        superstep,
        token.map(FencingToken(_))
      ),
      NodeEventSink.none
    )

  "RunContext.taskKey" should "be the same in every run of a task, whatever its run, checkpoint, token or superstep" in {
    val key = context().taskKey
    (key.value should fullyMatch).regex("[0-9a-f]{64}")
    // recover writes a new checkpoint and claim; updateState moves the superstep on, keeping the task id
    context(run = "other", checkpoint = "other/9", token = Some(99L), superstep = 4).taskKey shouldBe key
    context(thread = "u").taskKey should not be key
    context(task = "3.2").taskKey should not be key
  }

  "RunContext.effectKey" should "tell apart effects by operation and by what they touch, parts never running together" in {
    val c   = context()
    val key = c.effectKey("file.edit", "/a")
    c.effectKey("file.edit", "/a") shouldBe key
    key.operation shouldBe "file.edit"
    (key.value should fullyMatch).regex("[0-9a-f]{64}")
    Set(
      key,
      c.effectKey("file.write", "/a"),
      c.effectKey("file.edit", "/b"),
      c.effectKey("file.edit"),
      c.effectKey("file.edit", "/a", ""),
      c.effectKey("memory.consolidate", "a,b", "c"),
      c.effectKey("memory.consolidate", "a", "b,c"),
      c.effectKey("memory.consolidate", "ab")
    ) should have size 8
    context(task = "3.2").effectKey("file.edit", "/a") should not be key
  }

  "ToolContext.effectKey" should "derive from the call's key and the executing thread, not from the task" in {
    val call = IdempotencyKey("call-key")
    val here = ToolContext(context(), ToolCallId("call_0"), call, ThreadState.empty(Map.empty))
    val again =
      ToolContext(context(task = "5.0", superstep = 5), ToolCallId("call_0"), call, ThreadState.empty(Map.empty))
    // a re-run after approval is another task with the call's key: the same effect
    again.effectKey("file.write", "/a") shouldBe here.effectKey("file.write", "/a")
    // a fork re-running the call keeps its key, but is a new effect in the fork
    val forked = ToolContext(context(thread = "fork"), ToolCallId("call_0"), call, ThreadState.empty(Map.empty))
    forked.effectKey("file.write", "/a") should not be here.effectKey("file.write", "/a")
    here.effectKey("file.write", "/a") should not be context().effectKey("file.write", "/a")
  }

  "EffectRecord" should "round-trip through its JSON, and refuse a newer format or an unknown support class" in {
    val key = context().effectKey("op", "x")
    val records = Vector(
      new EffectRecord(key, TaskId("3.1"), EffectBody.Begun(EffectIntent.Unverifiable)),
      new EffectRecord(key, TaskId("3.1"), EffectBody.Completed(EffectIntent.Transactional, ujson.Obj("n" -> 1))),
      new EffectRecord(
        key,
        TaskId("3.1"),
        EffectBody.Omitted(EffectIntent.Verifiable(StateCheck.absent, StateCheck.of("x"), ujson.Num(1)), 99L)
      )
    )
    records.foreach { record =>
      val back = EffectRecord.fromJson(ujson.read(ujson.write(EffectRecord.toJson(record)))).value
      back shouldBe record
      back.key shouldBe key
      back.taskId shouldBe TaskId("3.1")
    }
    records(0).completed shouldBe false
    records(1).completed shouldBe true

    val newer = EffectRecord.toJson(records(0))
    newer("format") = EffectRecord.CurrentFormat + 1
    EffectRecord.fromJson(newer).left.value.message should include("newer")
    val unknown = EffectRecord.toJson(records(0))
    unknown("intent") = ujson.Obj("support" -> "telepathic")
    EffectRecord.fromJson(unknown).left.value.message should include("telepathic")
    EffectRecord.fromJson(ujson.Obj()).isLeft shouldBe true
  }

  "StateCheck" should "digest content, telling absent from empty" in {
    StateCheck.of("a") shouldBe StateCheck.of("a".getBytes("UTF-8"))
    StateCheck.of("a") should not be StateCheck.of("b")
    StateCheck.of("") should not be StateCheck.absent
  }

  private def snapshot(superstep: Int, frontier: Vector[GraphSnapshot.PendingTask], parked: Vector[String]) =
    GraphSnapshot(
      "g",
      "v",
      "f",
      superstep,
      Map.empty,
      frontier,
      Vector.empty,
      Vector.empty,
      parked.map(o => GraphSnapshot.ParkedContinuation(s"i-$o", "n", VersionedJson(1, ujson.Null), o, "n", None, None)),
      false
    )

  private def pending(id: String, origin: Option[String] = None) =
    GraphSnapshot.PendingTask(id, "n", VersionedJson(1, ujson.Null), None, None, origin, origin.map(_ => "n"))

  "GraphSnapshot.liveTasks" should "be the frontier, and the tasks resumed and parked continuations stand in for" in {
    snapshot(4, Vector(pending("4.0"), pending("3.2", origin = Some("1.0"))), Vector("2.1")).liveTasks shouldBe
      Set("4.0", "3.2", "1.0", "2.1")
  }

  "Commit.releasesEffect" should "release only at a checkpoint, finished tasks and an earlier claim's orphans" in {
    val token = FencingToken(5L)
    val other = FencingToken(4L)
    val c = Checkpoint(
      Checkpoint.CurrentFormat,
      "c",
      None,
      "t",
      "r",
      CheckpointStatus.Running,
      java.time.Instant.EPOCH,
      snapshot(4, Vector(pending("4.0")), Vector("2.1"))
    )
    Commit(token).releasesEffect(TaskId("1.0"), token) shouldBe false
    val commit = Commit(token, Some(c))
    commit.releasesEffect(TaskId("4.0"), token) shouldBe false // live
    commit.releasesEffect(TaskId("2.1"), other) shouldBe false // parked, an earlier run's
    commit.releasesEffect(TaskId("3.0"), token) shouldBe true  // finished
    commit.releasesEffect(TaskId("5.0"), token) shouldBe false // this run's, ahead of the commit
    commit.releasesEffect(TaskId("5.0"), other) shouldBe true  // an earlier run's orphan
    commit.releasesEffect(TaskId("updateState"), token) shouldBe true
  }
}
