package org.llm4s.agent.service

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.ForwardingCheckpointer
import org.llm4s.config.RunQueueConfigLoader
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.time.Instant
import scala.concurrent.duration.*

class RunQueueSpec extends AnyFlatSpec with TestTimeLimit with Matchers with EitherValues with OptionValues {

  private val start  = Instant.parse("2026-10-21T12:00:00Z")
  private val agent  = AgentId.of("assistant").value
  private val thread = ThreadId("queued")

  private def checkpoint(id: String, run: String, status: CheckpointStatus): Checkpoint =
    Checkpoint(
      Checkpoint.CurrentFormat,
      id,
      None,
      thread.value,
      run,
      status,
      start,
      GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)
    )

  private def entry(input: QueuedInput, admitted: Boolean = false): QueueEntry =
    QueueEntry.stored(
      EntryId.unsafe(1),
      thread,
      agent,
      None,
      input,
      BusyThreadPolicy.Enqueue,
      None,
      3,
      None,
      start,
      Vector(Attempt(RunId("r1"), start, "w")),
      admitted,
      None,
      None,
      None,
      None,
      None,
      Some(0),
      None
    )

  // ---- rule 4: classifying an attempt ----

  "AttemptPlan" should "start an input none of whose runs committed, whatever the thread's latest checkpoint" in {
    val other = checkpoint("c9", "direct", CheckpointStatus.Running)
    AttemptPlan.of(entry(QueuedInput.query("q")), None, None) shouldBe AttemptPlan.Start
    AttemptPlan.of(entry(QueuedInput.query("q")), None, Some(other)) shouldBe AttemptPlan.Start
    AttemptPlan.of(entry(QueuedInput.Resume(Map.empty)), None, Some(other)) shouldBe AttemptPlan.Start
  }

  it should "recover the thread when the entry's admitted run is its latest and incomplete" in {
    val own = checkpoint("c1", "r1", CheckpointStatus.Running)
    AttemptPlan.of(entry(QueuedInput.query("q")), Some(own), Some(own)) shouldBe AttemptPlan.Recover
  }

  it should "close the entry from the thread's state once its admitted input reached rest or the thread moved on" in {
    val own                        = checkpoint("c1", "r1", CheckpointStatus.Running)
    def closed(latest: Checkpoint) = AttemptPlan.of(entry(QueuedInput.query("q")), Some(own), Some(latest))
    closed(checkpoint("c1", "r1", CheckpointStatus.Completed)) shouldBe
      AttemptPlan.Close(EntryOutcome.Done(Some("c1"), Some(CheckpointStatus.Completed)))
    closed(checkpoint("c1", "r1", CheckpointStatus.Suspended)) shouldBe
      AttemptPlan.Close(EntryOutcome.Done(Some("c1"), Some(CheckpointStatus.Suspended)))
    // a direct recover took the thread on: the input was applied once, and the queue runs nothing
    closed(checkpoint("c2", "direct", CheckpointStatus.Running)) shouldBe
      AttemptPlan.Close(EntryOutcome.Done(Some("c2"), Some(CheckpointStatus.Running)))
    closed(checkpoint("c2", "r1", CheckpointStatus.Failed)) match {
      case AttemptPlan.Close(EntryOutcome.Failed(FailureReason.RunFailed(message))) => message should include("c2")
      case other                                                                    => fail(s"unexpected $other")
    }
    AttemptPlan.of(entry(QueuedInput.query("q")), Some(own), None) shouldBe
      AttemptPlan.Close(EntryOutcome.Cancelled(CancelReason.ThreadDeleted))
  }

  it should "close an admitted entry whose checkpoints a retraction removed, never starting its input again" in {
    // the lookup finds none of the entry's runs, but the store recorded that one of them committed
    val failed = checkpoint("c3", "direct", CheckpointStatus.Failed)
    AttemptPlan.of(entry(QueuedInput.query("q"), admitted = true), None, Some(failed)) match {
      case AttemptPlan.Close(EntryOutcome.Failed(FailureReason.RunFailed(message))) => message should include("c3")
      case other                                                                    => fail(s"unexpected $other")
    }
    AttemptPlan.of(entry(QueuedInput.Recover, admitted = true), None, Some(failed)) shouldBe a[AttemptPlan.Close]
    AttemptPlan.of(entry(QueuedInput.query("q"), admitted = true), None, None) shouldBe
      AttemptPlan.Close(EntryOutcome.Cancelled(CancelReason.ThreadDeleted))
  }

  it should "recover for a queued recover until one of its runs commits: the owner's transfer of the thread" in {
    val direct = checkpoint("c1", "direct", CheckpointStatus.Running)
    AttemptPlan.of(entry(QueuedInput.Recover), None, Some(direct)) shouldBe AttemptPlan.Recover
    val own = checkpoint("c2", "r1", CheckpointStatus.Running)
    AttemptPlan.of(entry(QueuedInput.Recover), Some(own), Some(own)) shouldBe AttemptPlan.Recover
    AttemptPlan.of(
      entry(QueuedInput.Recover),
      Some(own),
      Some(checkpoint("c2", "r1", CheckpointStatus.Completed))
    ) shouldBe
      AttemptPlan.Close(EntryOutcome.Done(Some("c2"), Some(CheckpointStatus.Completed)))
  }

  // ---- a queued run on a real agent ----

  "A queued start" should "be classified by the run-id lookup before and after the run it records" in {
    val store   = InMemoryCheckpointer()
    val runtime = new GraphRuntime(store)
    val client  = ScriptedLLMClient.of(CompletionFixture.simple("hello back"))
    val tracing = new org.llm4s.agent.delegation.NestedStreamSpec.Recording
    val built   = Agent.builder("assistant", client).withRuntime(runtime).withTracing(tracing).build().value
    val queue   = store.runQueue
    val context = org.llm4s.trace.TraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7")
    queue.submit(Submission(thread, agent, QueuedInput.query("hello")).withTraceContext(context)).value
    val leased = queue.take(TakeRequest("worker", Set(agent), 30.seconds)).value.get
    val run    = RunId.random()
    val recorded = queue.recordAttempt(leased.lease, run).value match {
      case AttemptStart.Recorded(e) => e
      case other                    => fail(s"unexpected $other")
    }
    def plan() =
      AttemptPlan.of(
        recorded,
        store.latestOfRuns(thread, recorded.runIds).value,
        store.latest(thread).value.map(_.checkpoint)
      )
    plan() shouldBe AttemptPlan.Start
    val query = recorded.input match {
      case QueuedInput.Start(input) => upickle.default.read[org.llm4s.agent.graph.toolloop.AgentInput](input).query
      case other                    => fail(s"unexpected $other")
    }
    val config = recorded.attemptConfig(run)
    config.runId shouldBe run
    config.traceContext shouldBe Some(context)
    built.run(thread, query, config).value.answer shouldBe Some("hello back")
    store.latestOfRuns(thread, Set(run)).value.map(_.id) shouldBe store.latest(thread).value.map(_.checkpoint.id)
    // the attempt is in the submitter's trace: its run span is under the submitter's, as is what its thread records
    val ended = tracing.events.collect { case e: org.llm4s.trace.TraceEvent.AgentRunEnded => e }
    ended.map(_.span.flatMap(_.parent)) shouldBe Vector(Some(context))
    store.latest(thread).value.value.checkpoint.trace.value.traceId shouldBe context.traceId
    val outcome = plan() match {
      case AttemptPlan.Close(o) => o
      case other                => fail(s"unexpected $other")
    }
    outcome shouldBe a[EntryOutcome.Done]
    queue.close(leased.lease, outcome).value.isOpen shouldBe false
  }

  // ---- following a ticket ----

  "RunSubmitter.follow" should "page the entry's run events only, and finish once the entry is closed" in {
    val store     = InMemoryCheckpointer()
    val submitter = new RunSubmitter(store.runQueue, store, RunQueueSettings(maxAttempts = 2))
    val ticket    = submitter.submit(thread, agent, QueuedInput.query("q"), policy = BusyThreadPolicy.Enqueue).value
    submitter.entry(None, ticket.entryId).value.maxAttempts shouldBe 2
    def commitAs(run: String, id: String, parent: Option[String]): Unit = {
      val claim = store.claim(thread, ClaimRequest(RunId(run), None, 30.seconds)).value
      val draft = EventDraft(run, Some(id), None, None, start, RunEvent.Custom("e", 1, ujson.Obj()))
      store
        .commit(
          thread,
          Commit(
            claim.token,
            Some(checkpoint(id, run, CheckpointStatus.Running).withParent(parent)),
            events = Vector(draft)
          )
        )
        .value
      store.release(thread, claim.token).value
    }
    commitAs("direct", "c1", None)
    submitter.follow(None, ticket.entryId, 0L, 10, 10.millis).value shouldBe
      FollowedRun(submitter.entry(None, ticket.entryId).value, Vector.empty, 1L, finished = false)
    val leased = store.runQueue.take(TakeRequest("w", Set(agent), 30.seconds)).value.get
    store.runQueue.recordAttempt(leased.lease, RunId("queued-run")).value
    commitAs("queued-run", "c2", Some("c1"))
    val page = submitter.follow(None, ticket.entryId, 1L, 10, 1.second).value
    page.events.map(_.runId) shouldBe Vector("queued-run")
    page.next shouldBe 2L
    page.finished shouldBe false
    // a follower waiting when the entry closes is told it has finished on that page
    val waiting = new java.util.concurrent.atomic.AtomicReference[FollowedRun]()
    val follower =
      Thread.ofVirtual().start(() => waiting.set(submitter.follow(None, ticket.entryId, 2L, 10, 5.seconds).value))
    Thread.sleep(50)
    store.runQueue.close(leased.lease, EntryOutcome.Done(Some("c2"), Some(CheckpointStatus.Completed))).value
    commitAs("direct", "c3", Some("c2"))
    follower.join(5000)
    waiting.get.finished shouldBe true
    waiting.get.events shouldBe empty
    waiting.get.next shouldBe 2L
    // the thread goes on with other runs: following the closed entry stops at its close, however busy the thread
    commitAs("direct", "c4", Some("c3"))
    submitter.entry(None, ticket.entryId).value.closedAtSeq shouldBe Some(2L)
    val last = submitter.follow(None, ticket.entryId, 2L, 1, 1.second).value
    last.finished shouldBe true
    last.events shouldBe empty
    last.next shouldBe 2L
    val replayed = submitter.follow(None, ticket.entryId, 0L, 1, 1.second).value
    replayed.finished shouldBe false
    replayed.events shouldBe empty
    replayed.next shouldBe 1L
    val rest = submitter.follow(None, ticket.entryId, 1L, 1, 1.second).value
    rest.events.map(_.runId) shouldBe Vector("queued-run")
    rest.finished shouldBe true
    submitter.await(None, ticket.entryId, 1.second).value.outcome shouldBe
      Some(EntryOutcome.Done(Some("c2"), Some(CheckpointStatus.Completed)))
    submitter.follow(None, ticket.entryId, 0L, 0, 10.millis).left.value shouldBe a[ValidationError]
    submitter.follow(Some(TenantId("other")), ticket.entryId, 0L, 10, 10.millis).left.value shouldBe
      QueueError.TicketExpired(ticket.entryId)
  }

  it should "read the log again when the entry closes while it waits, never finishing on a stale page" in {
    val store  = InMemoryCheckpointer()
    val ticket = store.runQueue.submit(Submission(thread, agent, QueuedInput.query("q"))).value
    val leased = store.runQueue.take(TakeRequest("w", Set(agent), 30.seconds)).value.get
    store.runQueue.recordAttempt(leased.lease, RunId("queued-run")).value
    // the wait comes back empty just before the run commits its last event and the entry closes
    val stale = new ForwardingCheckpointer(store) {
      override def awaitEventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int, timeout: FiniteDuration) = {
        val claim = store.claim(thread, ClaimRequest(RunId("queued-run"), None, 30.seconds)).value
        val draft = EventDraft("queued-run", Some("c1"), None, None, start, RunEvent.Custom("e", 1, ujson.Obj()))
        store
          .commit(
            thread,
            Commit(
              claim.token,
              Some(RunQueueSpec.this.checkpoint("c1", "queued-run", CheckpointStatus.Completed)),
              events = Vector(draft)
            )
          )
          .value
        store.release(thread, claim.token).value
        store.runQueue.close(leased.lease, EntryOutcome.Done(Some("c1"), Some(CheckpointStatus.Completed))).value
        Right(Vector.empty)
      }
    }
    val page = new RunSubmitter(store.runQueue, stale).follow(None, ticket.entryId, 0L, 10, 1.second).value
    page.events.map(_.runId) shouldBe Vector("queued-run")
    page.finished shouldBe true
    page.next shouldBe 1L
  }

  // ---- settings ----

  "RunQueueSettings" should "default to five attempts, a day's ticket lifetime and the claim's lease" in {
    val settings = RunQueueSettings.default
    settings.maxAttempts shouldBe 5
    settings.ticketLifetime shouldBe 24.hours
    settings.lease shouldBe LeasePolicy(30.seconds, 10.seconds)
    RunQueueSettings.of(maxAttempts = 0).left.value shouldBe a[ValidationError]
    RunQueueSettings.of(ticketLifetime = Duration.Zero).left.value shouldBe a[ValidationError]
    LeasePolicy.of(10.seconds, 10.seconds).left.value shouldBe a[ValidationError]
    Submission.of(thread, agent, QueuedInput.Recover, maxAttempts = 0).left.value shouldBe a[ValidationError]
    an[IllegalArgumentException] should be thrownBy RunQueueSettings(maxAttempts = 0)
    SubmissionKey.of("").left.value shouldBe a[ValidationError]
    SubmissionKey.of("x" * 201).left.value shouldBe a[ValidationError]
    EntryId.parse("12").value.value shouldBe 12L
    EntryId.parse("0").left.value shouldBe a[ValidationError]
    EntryId.parse("x").left.value shouldBe a[ValidationError]
  }

  "RunQueueConfigLoader" should "load llm4s.agent.queue with reference.conf's defaults" in {
    RunQueueConfigLoader.load(ConfigSource.default).value shouldBe RunQueueSettings.default
  }

  it should "read every setting, and refuse an invalid one naming its path" in {
    val loaded = RunQueueConfigLoader
      .load(
        ConfigSource.string(
          "llm4s.agent.queue { maxAttempts = 2, ticketLifetime = 1 hour, lease { ttl = 9 seconds, renewEvery = 3 seconds } }"
        )
      )
      .value
    loaded shouldBe RunQueueSettings(2, 1.hour, LeasePolicy(9.seconds, 3.seconds))
    val badLease = RunQueueConfigLoader.load(
      ConfigSource.string(
        "llm4s.agent.queue { maxAttempts = 2, ticketLifetime = 1 hour, lease { ttl = 3 seconds, renewEvery = 3 seconds } }"
      )
    )
    badLease.left.value shouldBe a[ConfigurationError]
    badLease.left.value.message should include("llm4s.agent.queue.lease")
    val missing = RunQueueConfigLoader.load(ConfigSource.string("llm4s.agent.queue { maxAttempts = 2 }"))
    missing.left.value.message should include("ticketLifetime")
  }

  "QueuedInput and EntryOutcome" should "survive their stored JSON, and refuse JSON that is neither" in {
    val inputs = Vector(
      QueuedInput.query("q"),
      QueuedInput.typed(Map("a" -> 1)),
      QueuedInput.Resume(Map(InterruptId("i") -> ujson.True)),
      QueuedInput.Recover
    )
    inputs.foreach(i => QueuedInput.fromJson(QueuedInput.toJson(i)).value shouldBe i)
    QueuedInput.fromJson(ujson.Obj("kind" -> "other")).isLeft shouldBe true
    QueuedInput.fromJson(ujson.Obj("kind" -> "start")).isLeft shouldBe true
    QueuedInput.Recover.kind shouldBe "recover"
    val interrupted = EntryOutcome.Interrupted(Some(EntryId.unsafe(7)))
    EntryOutcome.fromJson(EntryOutcome.toJson(interrupted)).value shouldBe interrupted
    interrupted.state shouldBe "interrupted"
    EntryOutcome.fromJson(ujson.Obj("state" -> "lost")).isLeft shouldBe true
  }
}
