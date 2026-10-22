package org.llm4s.agent.metrics

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture, FailingLLMClient, NTurnFakeLLMClient }
import org.llm4s.agent.delegation.ChildRef
import org.llm4s.agent.events.{ SubagentEvents, TaskEnded }
import org.llm4s.agent.graph.*
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.model.UsageSummary
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.Modifier
import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** [[MeteredCheckpointer]] records runs, claims, commits and child tasks from what each store call did (design 4.59). */
class MeteredCheckpointerSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {
  import AgentMetrics.*

  private val start = Instant.parse("2026-10-22T12:00:00Z")

  final private class MovingClock extends Clock {
    private val current                        = new AtomicReference(start)
    def instant(): Instant                     = current.get
    def getZone: ZoneId                        = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    def advance(by: FiniteDuration): Unit      = current.updateAndGet(_.plusNanos(by.toNanos)): Unit
  }

  final private class Fixture {
    val clock   = new MovingClock
    val store   = new InMemoryCheckpointer(clock)
    val metrics = new RecordingMetrics
    val metered = MeteredCheckpointer(store, metrics)
  }

  private def thread(): ThreadId = ThreadId(s"metered-${java.util.UUID.randomUUID().toString.take(8)}")

  private def snapshot(graph: String) =
    GraphSnapshot(graph, "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)

  private def checkpoint(
    on: ThreadId,
    run: String,
    token: FencingToken,
    parent: Option[String] = None,
    origin: Option[RunOrigin] = None
  ): Checkpoint =
    Checkpoint(
      Checkpoint.CurrentFormat,
      s"c-${java.util.UUID.randomUUID().toString.take(8)}",
      parent,
      on.value,
      run,
      CheckpointStatus.Running,
      start,
      snapshot("researcher"),
      None,
      Some(token),
      origin
    )

  private def event(run: String, at: Instant, e: RunEvent) = EventDraft(run, None, None, None, at, e)

  "MeteredCheckpointer" should "count claims acquired, refused as busy and taken over, and renewals kept and lost" in {
    val f     = new Fixture
    val on    = thread()
    val first = f.metered.claim(on, ClaimRequest(RunId("a"), None, 30.seconds)).value
    f.metered.claim(on, ClaimRequest(RunId("b"), None, 30.seconds)).left.value shouldBe a[GraphError.ThreadBusy]
    f.metered.renew(on, first.token, 30.seconds).value
    f.clock.advance(31.seconds)
    f.metered.claim(on, ClaimRequest(RunId("c"), None, 30.seconds)).value.takenOverFrom shouldBe Some(RunId("a"))
    f.metered.renew(on, first.token, 30.seconds).left.value shouldBe a[GraphError.StaleClaim]

    f.metrics.series(Claims) shouldBe Map(Vector("acquired") -> 1.0, Vector("busy") -> 1.0, Vector("taken_over") -> 1.0)
    f.metrics.series(ClaimRenewals) shouldBe Map(Vector("renewed") -> 1.0, Vector("lost") -> 1.0)
  }

  it should "time commits by result: ok, a parent conflict, and a lost claim" in {
    val f     = new Fixture
    val on    = thread()
    val claim = f.metered.claim(on, ClaimRequest(RunId("a"), None, 30.seconds)).value
    val first = checkpoint(on, "a", claim.token)
    f.metered.commit(on, Commit(claim.token, Some(first))).value
    // a parent that is not the thread's latest
    f.metered.commit(on, Commit(claim.token, Some(checkpoint(on, "a", claim.token)))).left.value shouldBe
      a[GraphError.CheckpointConflict]
    f.metered.release(on, claim.token).value
    f.metered
      .commit(on, Commit(claim.token, Some(checkpoint(on, "a", claim.token, Some(first.id)))))
      .left
      .value shouldBe
      a[GraphError.StaleClaim]

    f.metrics.timed(CommitDuration) shouldBe Set(Vector("ok"), Vector("conflict"), Vector("claim_lost"))
    f.metrics.durations(CommitDuration, "ok") should have size 1
  }

  it should "count a completed agent run started and ended, by its graph, with its duration" in {
    val f = new Fixture
    val agent = Agent
      .builder("assistant", new NTurnFakeLLMClient(CompletionFixture.simple("hi")))
      .withRuntime(new GraphRuntime(f.metered))
      .build()
      .value
    val on = thread()
    agent.run(on, "hello").value
    val graph = f.store.latest(on).value.value.checkpoint.snapshot.graphId

    f.metrics.series(RunsStarted) shouldBe Map(Vector(graph, "start") -> 1.0)
    f.metrics.series(RunsEnded) shouldBe Map(Vector(graph, "completed") -> 1.0)
    f.metrics.durations(RunDuration, graph, "completed") should have size 1
  }

  it should "count a failed run, and its recovery as a run of its own" in {
    val f  = new Fixture
    val on = thread()
    val failed = Agent
      .builder("assistant", new FailingLLMClient(ServiceError(500, "test", "down")))
      .withRuntime(new GraphRuntime(f.metered))
      .build()
      .value
    failed.run(on, "hello").isLeft shouldBe true
    val graph = f.store.latest(on).value.value.checkpoint.snapshot.graphId
    val ok = Agent
      .builder("assistant", new NTurnFakeLLMClient(CompletionFixture.simple("hi")))
      .withRuntime(new GraphRuntime(f.metered))
      .build()
      .value
    ok.recover(on).value

    f.metrics.count(RunsStarted, graph, "start") shouldBe 1.0
    f.metrics.count(RunsEnded, graph, "failed") shouldBe 1.0
    f.metrics.count(RunsStarted, graph, "recover") shouldBe 1.0
    f.metrics.count(RunsEnded, graph, "completed") shouldBe 1.0
  }

  it should "end a cancelled run committed with no checkpoint under the graph it started with, timing it" in {
    val f     = new Fixture
    val on    = thread()
    val claim = f.metered.claim(on, ClaimRequest(RunId("a"), None, 30.seconds)).value
    val first = checkpoint(on, "a", claim.token)
    f.metered
      .commit(on, Commit(claim.token, Some(first), events = Vector(event("a", start, RunEvent.RunStarted(None, None)))))
      .value
    f.metered
      .commit(on, Commit(claim.token, None, events = Vector(event("a", start.plusSeconds(4), RunEvent.RunCancelled()))))
      .value

    f.metrics.count(RunsEnded, "researcher", "cancelled") shouldBe 1.0
    f.metrics.durations(RunDuration, "researcher", "cancelled") shouldBe Vector(4.seconds)
  }

  it should "count a child task started at its child's first admission and ended by the parent's task_ended" in {
    val f      = new Fixture
    val parent = thread()
    val child  = thread()
    val origin = RunOrigin(parent, IdempotencyKey("call-1"), AgentId.of("researcher").value, start)
    val claim  = f.metered.claim(child, ClaimRequest(RunId("c1"), None, 30.seconds)).value
    f.metered
      .commit(
        child,
        Commit(
          claim.token,
          Some(checkpoint(child, "c1", claim.token, origin = Some(origin))),
          events = Vector(event("c1", start, RunEvent.RunStarted(None, None)))
        )
      )
      .value
    val ended = TaskEnded(
      ChildRef(child, RunId("c1"), AgentId.of("researcher").value, None, 1),
      "blocked:pii",
      UsageSummary(),
      3.seconds
    )
    val held    = f.metered.claim(parent, ClaimRequest(RunId("p1"), None, 30.seconds)).value
    val payload = upickle.default.writeJs(ended)
    f.metered
      .commit(
        parent,
        Commit(
          held.token,
          Some(checkpoint(parent, "p1", held.token)),
          events = Vector(event("p1", start, RunEvent.Custom(SubagentEvents.TaskEnded.name, 1, payload)))
        )
      )
      .value

    f.metrics.series(TasksStarted) shouldBe Map(Vector("researcher") -> 1.0)
    // a guardrail's name never becomes a series
    f.metrics.series(TasksEnded) shouldBe Map(Vector("researcher", "blocked") -> 1.0)
    f.metrics.durations(TaskDuration, "researcher", "blocked") shouldBe Vector(3.seconds)
  }

  it should "answer every call as the store does, even when the collector throws" in {
    val store = InMemoryCheckpointer()
    val metered = MeteredCheckpointer(
      store,
      new RecordingMetrics {
        override def increment(metric: org.llm4s.metrics.Metric.Counter, labelValues: Seq[String], amount: Double) =
          throw new IllegalStateException("collector down")
        override def observe(
          metric: org.llm4s.metrics.Metric.Timer,
          labelValues: Seq[String],
          duration: FiniteDuration
        ) =
          throw new IllegalStateException("collector down")
      }
    )
    val on    = thread()
    val claim = metered.claim(on, ClaimRequest(RunId("a"), None, 30.seconds)).value
    metered
      .commit(
        on,
        Commit(
          claim.token,
          Some(checkpoint(on, "a", claim.token)),
          events = Vector(event("a", start, RunEvent.RunStarted(None, None)))
        )
      )
      .value should have size 1
    metered.latest(on).value shouldBe store.latest(on).value
  }

  it should "forward every method of Checkpointer, the defaulted ones included, so a store's own wait is kept" in {
    def methods(cls: Class[?]): Set[String] =
      cls.getDeclaredMethods.toList
        .filter(m => Modifier.isPublic(m.getModifiers) && !Modifier.isStatic(m.getModifiers))
        .filter(m => !m.isSynthetic && !m.isBridge && !m.getName.contains("$"))
        .map(m => s"${m.getName}(${m.getParameterTypes.map(_.getSimpleName).mkString(", ")})")
        .toSet
    val declared = methods(classOf[Checkpointer])
    declared should not be empty
    (declared -- methods(classOf[MeteredCheckpointer])) shouldBe empty
  }

  "MeteredCheckpointer.taskStatus" should "name a blocked task by its kind only" in {
    MeteredCheckpointer.taskStatus("blocked:pii") shouldBe "blocked"
    MeteredCheckpointer.taskStatus("completed") shouldBe "completed"
  }
}
