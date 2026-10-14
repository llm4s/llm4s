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

  it should "be the logical task's for a continuation: the task it stands in for, not the one executing" in {
    val origin = context(task = "2.0")
    val continuation = new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r2"), "r2/7", TaskId("5.3"), NodeId("review"), 5, Some(FencingToken(9L))),
      NodeEventSink.none,
      owner = Some(TaskId("2.0"))
    )
    continuation.taskKey shouldBe origin.taskKey
    continuation.effectKey("memory.record", "m") shouldBe origin.effectKey("memory.record", "m")
    continuation.effectOwner shouldBe TaskId("2.0")
  }

  "An answered resume's key" should "name its phase: the asking phase, the question and the answer, by thread" in {
    val call    = IdempotencyKey("call-key")
    val state   = ThreadState.empty(Map.empty)
    val q       = ujson.Obj("prompt" -> "sure?")
    val first   = IdempotencyKey.ofResume(call, None, q, ujson.True)
    val second  = IdempotencyKey.ofResume(call, Some(first), q, ujson.True)
    val execute = ToolContext(context(), ToolCallId("c"), call, state)
    def resuming(phase: IdempotencyKey, thread: String = "t") =
      ToolContext.resuming(context(thread = thread), ToolCallId("c"), call, state, approved = false, phase)
    val keys = Seq(execute, resuming(first), resuming(second)).map(_.effectKey("op", "x"))
    keys.distinct should have size 3
    // stable: the same resume, recovered, derives the same key
    resuming(IdempotencyKey.ofResume(call, None, ujson.Obj("prompt" -> "sure?"), ujson.True))
      .effectKey("op", "x") shouldBe keys(1)
    IdempotencyKey.ofResume(call, None, q, ujson.False) should not be first
    // a fork resuming the same question is a new effect
    resuming(first, thread = "fork").effectKey("op", "x") should not be keys(1)
    // an approval re-run of execute keeps execute's key
    execute.withApproved(true).effectKey("op", "x") shouldBe keys(0)
    resuming(first).withApproved(true).effectKey("op", "x") shouldBe keys(1)
  }

  "A hook that asks" should "keep its task key when it runs again with its answers" in {
    val c = context(task = "2.0")
    c.withAnswers(Map("m" -> (ujson.Str("q"), ujson.Str("a")))).taskKey shouldBe c.taskKey
    c.withAnswers(Map("m" -> (ujson.Str("q"), ujson.Str("a")))).effectKey("op") shouldBe c.effectKey("op")
  }

  "Commit.releasesEffect at a settled checkpoint" should "release every record, a parked sibling's of a Block included" in {
    val token = FencingToken(5L)
    val failed = Checkpoint(
      Checkpoint.CurrentFormat,
      "c",
      None,
      "t",
      "r",
      CheckpointStatus.Failed,
      java.time.Instant.EPOCH,
      snapshot(4, Vector(pending("4.0")), Vector("3.1"))
    )
    Commit(token, Some(failed)).releasesEffect(TaskId("3.1"), token) shouldBe true
    Commit(token, Some(failed)).releasesEffect(TaskId("4.0"), token) shouldBe true
    Commit(token, Some(failed.withStatus(CheckpointStatus.Suspended)))
      .releasesEffect(TaskId("3.1"), token) shouldBe false
  }

  "EffectRecord's JSON" should "share no mutable value with the record, in either direction" in {
    val key    = context().effectKey("op", "x")
    val out    = ujson.Obj("n" -> 1)
    val record = new EffectRecord(key, TaskId("3.1"), EffectBody.Completed(EffectIntent.Transactional, out))
    val before = ujson.write(EffectRecord.toJson(record))
    // what toJson hands out cannot change the record
    EffectRecord.toJson(record)("outcome")("value")("n") = 99
    ujson.write(EffectRecord.toJson(record)) shouldBe before
    // what fromJson was given cannot change the record it read
    val json = EffectRecord.toJson(record)
    val read = EffectRecord.fromJson(json).value
    json("outcome")("value")("n") = 42
    ujson.write(EffectRecord.toJson(read)) shouldBe before
    val verifiable = EffectRecord.toJson(
      new EffectRecord(
        key,
        TaskId("3.1"),
        EffectBody.Begun(EffectIntent.Verifiable(StateCheck.absent, StateCheck.of("x"), ujson.Arr(1)))
      )
    )
    val readV = EffectRecord.fromJson(verifiable).value
    verifiable("intent")("presumed").arr += ujson.Num(2)
    EffectRecord.toJson(readV)("intent")("presumed") shouldBe ujson.Arr(1)
  }

  it should "not let a replayed outcome alias the stored one" in {
    val c   = context()
    val key = c.effectKey("op", "json")
    ReplayGuard.unverifiable[ujson.Value](c, key, idempotent = false)(Right(ujson.Obj("n" -> 1))).value("n") = 7
    val first = ReplayGuard.unverifiable[ujson.Value](c, key, idempotent = false)(Right(ujson.Null)).value
    first("n") = 5
    ReplayGuard.unverifiable[ujson.Value](c, key, idempotent = false)(Right(ujson.Null)).value shouldBe ujson.Obj(
      "n" -> 1
    )
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
