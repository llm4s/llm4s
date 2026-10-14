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

  "ToolContext.effectKey" should "derive from the call's task and id and the executing thread" in {
    val call = IdempotencyKey("call-key")
    val here = ToolContext(context(), ToolCallId("call_0"), call, ThreadState.empty(Map.empty))
    // a re-run after approval is a continuation standing in for the call's first task: the same effect
    val continuation = new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r"), "r/5", TaskId("5.0"), NodeId("n"), 5),
      NodeEventSink.none,
      owner = Some(TaskId("3.1") -> "")
    )
    val again = ToolContext(continuation, ToolCallId("call_0"), call, ThreadState.empty(Map.empty))
    again.effectKey("file.write", "/a") shouldBe here.effectKey("file.write", "/a")
    // a fork re-running the call keeps its key, but is a new effect in the fork
    val forked = ToolContext(context(thread = "fork"), ToolCallId("call_0"), call, ThreadState.empty(Map.empty))
    forked.effectKey("file.write", "/a") should not be here.effectKey("file.write", "/a")
    here.effectKey("file.write", "/a") should not be context().effectKey("file.write", "/a")
    // the call's key names the call: another call's key is another effect, whatever its provider id
    ToolContext(context(), ToolCallId("call_0"), IdempotencyKey("other"), ThreadState.empty(Map.empty))
      .effectKey("file.write", "/a") should not be here.effectKey("file.write", "/a")
  }

  "EffectRecord" should "round-trip through its JSON, and refuse a newer format or an unknown support class" in {
    val key = context().effectKey("op", "x")
    val records = Vector(
      new EffectRecord(key, TaskId("3.1"), "lin", EffectBody.Begun(EffectIntent.Unverifiable)),
      new EffectRecord(
        key,
        TaskId("3.1"),
        "lin",
        EffectBody.Completed(EffectIntent.Transactional, ujson.Obj("n" -> 1))
      ),
      new EffectRecord(
        key,
        TaskId("3.1"),
        "lin",
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
      owner = Some(TaskId("2.0") -> "")
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
    Commit(token, Some(failed)).releasesEffect(TaskId("3.1"), "") shouldBe true
    Commit(token, Some(failed)).releasesEffect(TaskId("4.0"), "") shouldBe true
    Commit(token, Some(failed.withStatus(CheckpointStatus.Suspended)))
      .releasesEffect(TaskId("3.1"), "") shouldBe false
  }

  "EffectRecord's JSON" should "share no mutable value with the record, in either direction" in {
    val key    = context().effectKey("op", "x")
    val out    = ujson.Obj("n" -> 1)
    val record = new EffectRecord(key, TaskId("3.1"), "lin", EffectBody.Completed(EffectIntent.Transactional, out))
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
        "lin",
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

  "A task's lineage" should "be part of its identity: work planned from other results has other keys" in {
    def at(lineage: String) = new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r"), "r/3", TaskId("5.0"), NodeId("n"), 5),
      NodeEventSink.none,
      owner = Some(TaskId("5.0") -> lineage)
    )
    at("a").taskKey should not be at("b").taskKey
    at("a").effectKey("op") should not be at("b").effectKey("op")
    at("a").taskKey shouldBe at("a").taskKey
    val call  = IdempotencyKey("call")
    val state = ThreadState.empty(Map.empty)
    // a call's key carries the issuing model task's lineage
    val issued = org.llm4s.llmconnect.model.ToolCall("c", "tool", ujson.Obj("x" -> 1))
    IdempotencyKey.ofCall(ThreadId("t"), at("a").taskKey, issued) should not be
      IdempotencyKey.ofCall(ThreadId("t"), at("b").taskKey, issued)
    IdempotencyKey.ofCall(ThreadId("t"), at("a").taskKey, issued) should not be
      IdempotencyKey.ofCall(ThreadId("t"), at("a").taskKey, issued.copy(arguments = ujson.Obj("x" -> 2)))
    // a tool's effects follow the call's key, which already carries the lineage
    ToolContext(at("a"), ToolCallId("c"), call, state).effectKey("op") shouldBe
      ToolContext(at("b"), ToolCallId("c"), call, state).effectKey("op")
  }

  it should "be a root lineage of the admitting claim for a task created before admission, kept on restore" in {
    val b     = GraphBuilder("g", "v1")
    val n     = b.node[Unit]("n")((_, _, _) => NodeResult.Continue(Command.empty))
    val graph = b.compile(n)(_ => Right(())).value
    val fresh = graph.start(())
    fresh.frontier.map(_.lineage) shouldBe Vector(Task.Unstamped)
    val stamped = fresh.stampedBy(9L)
    stamped.frontier.map(_.lineage) shouldBe Vector(Task.rootLineage(9L, TaskId("0.0")))
    val restored = graph.restore(graph.snapshot(stamped).value).value
    restored.frontier.map(_.lineage) shouldBe stamped.frontier.map(_.lineage)
    restored.stampedBy(12L).frontier.map(_.lineage) shouldBe stamped.frontier.map(_.lineage)
  }

  it should "follow what scheduled a task: the same results, the same lineage; other results, another" in {
    val log = StateKey.appending[String]("log")
    def graph(next: String) = {
      val b      = GraphBuilder("g", "v1")
      val second = b.node[Unit]("second", writes = Set(log))((_, _, _) => NodeResult.Continue(Command.empty))
      val first = b.node[Unit]("first", writes = Set(log)) { (_, _, _) =>
        NodeResult.Continue(Command.empty.update(log, next))
      }
      b.edge(first, second)
      b.compile(first)(_.get(log)).value
    }
    def scheduled(g: CompiledGraph[Unit, Vector[String]]) =
      g.step(ThreadId("t"), g.start(()).stampedBy(5L), RunConfig()) match
        case Step.Next(next) => next.frontier.map(t => t.id -> t.lineage)
        case other           => fail(s"did not step: $other")
    scheduled(graph("x")) shouldBe scheduled(graph("x"))
    scheduled(graph("x")).map(_._1) shouldBe scheduled(graph("y")).map(_._1)
    scheduled(graph("x")) should not be scheduled(graph("y"))
  }

  it should "digest results canonically, the order of a JSON object's members aside" in {
    val doc = StateKey.replace[ujson.Value]("doc", ujson.Null)
    def graph(value: ujson.Value) = {
      val b      = GraphBuilder("g", "v1")
      val second = b.node[Unit]("second", writes = Set(doc))((_, _, _) => NodeResult.Continue(Command.empty))
      val first =
        b.node[Unit]("first", writes = Set(doc))((_, _, _) => NodeResult.Continue(Command.empty.update(doc, value)))
      b.edge(first, second)
      b.compile(first)(_.get(doc)).value
    }
    def scheduled(g: CompiledGraph[Unit, ujson.Value]) =
      g.step(ThreadId("t"), g.start(()).stampedBy(5L), RunConfig()) match
        case Step.Next(next) => next.frontier.map(_.lineage)
        case other           => fail(s"did not step: $other")
    scheduled(graph(ujson.Obj("a" -> 1, "b" -> ujson.Obj("x" -> 1, "y" -> 2)))) shouldBe
      scheduled(graph(ujson.Obj("b" -> ujson.Obj("y" -> 2, "x" -> 1), "a" -> 1)))
    scheduled(graph(ujson.Obj("a" -> 1))) should not be scheduled(graph(ujson.Obj("a" -> 2)))
    // and a call's key, whatever the order of its arguments' members
    val task = IdempotencyKey("model-task")
    IdempotencyKey.ofCall(
      ThreadId("t"),
      task,
      org.llm4s.llmconnect.model.ToolCall("c", "w", ujson.Obj("p" -> 1, "q" -> 2))
    ) shouldBe
      IdempotencyKey.ofCall(
        ThreadId("t"),
        task,
        org.llm4s.llmconnect.model.ToolCall("c", "w", ujson.Obj("q" -> 2, "p" -> 1))
      )
  }

  it should "keep a run begun before lineages under the old identity: a format-6 superstep schedules legacy tasks" in {
    val log    = StateKey.appending[String]("log")
    val b      = GraphBuilder("g", "v1")
    val second = b.node[Unit]("second", writes = Set(log))((_, _, _) => NodeResult.Continue(Command.empty))
    val first =
      b.node[Unit]("first", writes = Set(log))((_, _, _) => NodeResult.Continue(Command.empty.update(log, "x")))
    b.edge(first, second)
    val g        = b.compile(first)(_.get(log)).value
    val snapshot = g.snapshot(g.start(())).value
    val legacy   = g.restore(snapshot.copy(frontier = snapshot.frontier.map(_.copy(lineage = Task.Legacy)))).value
    legacy.stampedBy(5L).frontier.map(_.lineage) shouldBe Vector(Task.Legacy)
    g.step(ThreadId("t"), legacy.stampedBy(5L), RunConfig()) match
      case Step.Next(next) => next.frontier.map(_.lineage) shouldBe Vector(Task.Legacy)
      case other           => fail(s"did not step: $other")
  }

  "Key and digest encoding" should "be one-to-one, unpaired surrogates included, and unchanged for well-formed text" in {
    val c = context()
    // String.getBytes(UTF_8) would turn each unpaired surrogate into '?'
    Set(
      c.effectKey("op", "a\uD800"),
      c.effectKey("op", "a?"),
      c.effectKey("op", "a\uDBFF"),
      c.effectKey("op", "a")
    ).size shouldBe 4
    Set(c.effectKey("op\uD800"), c.effectKey("op?")).size shouldBe 2
    Set(context(thread = "t\uDC00").taskKey, context(thread = "t?").taskKey).size shouldBe 2
    Set(context(task = "1.\uD800").taskKey, context(task = "1.?").taskKey).size shouldBe 2
    Set(context(thread = "t\uDC00").effectKey("op").scope, context(thread = "t?").effectKey("op").scope).size shouldBe 2
    val call = IdempotencyKey("call")
    Set(
      IdempotencyKey.ofResume(call, None, ujson.Str("q\uD800"), ujson.Str("a")),
      IdempotencyKey.ofResume(call, None, ujson.Str("q?"), ujson.Str("a")),
      IdempotencyKey.ofResume(call, None, ujson.Str("q"), ujson.Str("a\uDFFF")),
      IdempotencyKey.ofResume(call, None, ujson.Str("q"), ujson.Str("a?"))
    ).size shouldBe 4
    Set(
      IdempotencyKey.derive(ThreadId("t"), "c", 1, TaskId("1.0"), ToolCallId("call_\uD800")),
      IdempotencyKey.derive(ThreadId("t"), "c", 1, TaskId("1.0"), ToolCallId("call_?"))
    ).size shouldBe 2
    StateCheck.of("a\uD800") should not be StateCheck.of("a?")
    // well-formed text keeps its UTF-8 digest, so keys and checks recorded before are unchanged
    StateCheck.of("é ✓ 𝄞") shouldBe StateCheck.of("é ✓ 𝄞".getBytes("UTF-8"))
    val utf8 = java.security.MessageDigest.getInstance("SHA-256")
    Seq("x", "é𝄞").foreach { part =>
      val bytes = part.getBytes("UTF-8")
      utf8.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
      utf8.update(bytes)
    }
    KeyDigest.of(Seq("x", "é𝄞")) shouldBe KeyDigest.hex(utf8.digest())
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

  private def pending(
    id: String,
    origin: Option[String] = None,
    lineage: String = "",
    originLineage: Option[String] = None
  ) =
    GraphSnapshot.PendingTask(
      id,
      "n",
      VersionedJson(1, ujson.Null),
      None,
      None,
      origin,
      origin.map(_ => "n"),
      lineage = lineage,
      originLineage = originLineage
    )

  "GraphSnapshot.liveTasks" should "be the frontier, and the tasks resumed and parked continuations stand in for, by id and lineage" in {
    snapshot(
      4,
      Vector(
        pending("4.0", lineage = "L4"),
        pending("3.2", origin = Some("1.0"), lineage = "L3", originLineage = Some("L1"))
      ),
      Vector("2.1")
    ).liveTasks shouldBe
      Set("4.0" -> "L4", "3.2" -> "L3", "1.0" -> "L1", "2.1" -> "")
  }

  "Commit.releasesEffect" should "release only the provably settled: not live, and scheduled no later than the checkpoint" in {
    val token = FencingToken(5L)
    val c = Checkpoint(
      Checkpoint.CurrentFormat,
      "c",
      None,
      "t",
      "r",
      CheckpointStatus.Running,
      java.time.Instant.EPOCH,
      snapshot(4, Vector(pending("4.0", lineage = "recovered")), Vector("2.1"))
    )
    Commit(token).releasesEffect(TaskId("1.0"), "x") shouldBe false
    val commit = Commit(token, Some(c))
    commit.releasesEffect(TaskId("4.0"), "recovered") shouldBe false // live
    commit.releasesEffect(TaskId("2.1"), "") shouldBe false          // parked
    commit.releasesEffect(TaskId("3.0"), "any") shouldBe true        // finished
    // the durable plan scheduled other work as 4.0: the superstep that scheduled 4.0 never runs again
    commit.releasesEffect(TaskId("4.0"), "other") shouldBe true
    // scheduled after the checkpoint - running ahead of it, or work a takeover may plan again identically
    commit.releasesEffect(TaskId("5.0"), "any") shouldBe false
    commit.releasesEffect(TaskId("updateState"), "") shouldBe true
  }
}
