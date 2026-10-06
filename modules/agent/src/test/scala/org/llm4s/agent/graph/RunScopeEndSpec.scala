package org.llm4s.agent.graph

import org.llm4s.agent.Agent
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A stream or a late subscription to a run that ends without a terminal durable event still ends -
 * at the run's end-of-run barrier, once everything queued before it is delivered, with no quiet
 * period: its subscription has left the thread's live set by the time `await` returns.
 */
class RunScopeEndSpec extends AnyFlatSpec with Matchers with Eventually:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

  private object Answering extends LLMClient:
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(Completion("answer", 0L, "hi", "test-model", AssistantMessage("hi")))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024

  /**
   * A store that refuses (`Left`) or throws on the first `failing` commits carrying a `RunCompleted`,
   * and takes every later one.
   */
  final private class NoTerminal(throws: Boolean, failing: Int = Int.MaxValue) extends Checkpointer:
    val underlying       = InMemoryCheckpointer()
    private val refusals = new AtomicInteger(0)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if !commit.events.exists(_.event == RunEvent.RunCompleted) || refusals.getAndIncrement() >= failing then
        underlying.commit(threadId, commit)
      else if throws then throw new IllegalStateException("store down")
      else Left(ProcessingError("store", "store down"))
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
      underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit] = underlying.deleteThread(threadId)

  private def agentOn(runtime: GraphRuntime): Agent =
    Agent.builder("assistant", Answering).withRuntime(runtime).build().fold(e => fail(e.message), identity)

  private def durable(events: CopyOnWriteArrayList[StreamEvent]): Vector[EventRecord] =
    events.asScala.toVector.collect { case StreamEvent.Durable(r) => r }

  /** The run's records in the store: every durable event it committed. */
  private def stored(store: NoTerminal, threadId: ThreadId, runId: RunId): Vector[EventRecord] =
    store.underlying
      .eventsAfter(threadId, 0L, 1000)
      .fold(e => fail(e.message), identity)
      .filter(_.runId == runId.value)

  /** The old quiet period: a crashed run's stream end, and so its `await`, took at least this long. */
  private val OldQuiet = 1.second

  private def timed[A](body: => A): (A, FiniteDuration) =
    val started = System.nanoTime()
    val result  = body
    (result, (System.nanoTime() - started).nanos)

  for (throws, how) <- Seq(false -> "refuses", true -> "throws on") do
    s"agent.stream, when the store $how the run's terminal commit" should
      "end its subscription at the run's barrier, having delivered every event of the run" in {
        val store    = NoTerminal(throws)
        val runtime  = GraphRuntime(store)
        val threadId = ThreadId(s"crash-$throws")
        val events   = new CopyOnWriteArrayList[StreamEvent]()
        val run =
          agentOn(runtime).stream(threadId, "hello")(e => events.add(e): Unit).fold(e => fail(e.message), identity)
        run.await().isLeft shouldBe true
        // await drained the stream's scope: it has ended, and left the live set, already
        runtime.liveSubscriptions(threadId) shouldBe 0
        durable(events).map(_.event).exists(RunScopeEndSpec.terminal) shouldBe false
        durable(events).headOption.map(_.event) shouldBe Some(RunEvent.RunStarted(None, None))
        durable(events) shouldBe stored(store, threadId, run.runId)
      }

    s"AgentRun.subscribe, when the store $how the run's terminal commit" should
      "end a late subscription at the run's barrier, after its replay" in {
        val store    = NoTerminal(throws)
        val runtime  = GraphRuntime(store)
        val threadId = ThreadId(s"crash-sub-$throws")
        val run      = agentOn(runtime).start(threadId, "hello").fold(e => fail(e.message), identity)
        run.await().isLeft shouldBe true
        val events = new CopyOnWriteArrayList[StreamEvent]()
        run.subscribe()(e => events.add(e): Unit).isRight shouldBe true
        // the run has ended: the subscription replays it, then reaches the barrier queued behind the replay
        run.await().isLeft shouldBe true
        runtime.liveSubscriptions(threadId) shouldBe 0
        durable(events) shouldBe stored(store, threadId, run.runId)
        durable(events).headOption.map(_.event) shouldBe Some(RunEvent.RunStarted(None, None))
      }

    s"AgentRun.await, when the store $how the run's terminal commit" should
      "return promptly, not after a quiet period" in {
        val runtime  = GraphRuntime(NoTerminal(throws))
        val threadId = ThreadId(s"crash-await-$throws")
        val run      = agentOn(runtime).stream(threadId, "hello")(_ => ()).fold(e => fail(e.message), identity)
        // timed from the run's end, so only the drain is measured
        eventually(run.status shouldBe RunStatus.Failed)
        val (result, took) = timed(run.await())
        result.isLeft shouldBe true
        // the stream's scope has ended by the time await returns: its subscription has left the live set
        runtime.liveSubscriptions(threadId) shouldBe 0
        took should be < OldQuiet / 2
      }

  "A crashed run's barrier" should "not end the stream of a later run on the same thread" in {
    val store    = NoTerminal(throws = false, failing = 1)
    val runtime  = GraphRuntime(store)
    val threadId = ThreadId("crash-then-run")
    val agent    = agentOn(runtime)
    val crashed  = agent.stream(threadId, "one")(_ => ()).fold(e => fail(e.message), identity)
    crashed.await().isLeft shouldBe true
    // a late subscription to the crashed run, possibly still replaying when the next run starts
    val late = new CopyOnWriteArrayList[StreamEvent]()
    crashed.subscribe()(e => late.add(e): Unit).isRight shouldBe true
    val events = new CopyOnWriteArrayList[StreamEvent]()
    // the crash left the thread's execution incomplete: the next run on it recovers it
    val next = agent.streamRecover(threadId)(e => events.add(e): Unit).fold(e => fail(e.message), identity)
    next.await().isRight shouldBe true
    crashed.await().isLeft shouldBe true // drains the late subscription
    durable(events).map(_.runId).distinct shouldBe Vector(next.runId.value)
    durable(events).lastOption.map(_.event) shouldBe Some(RunEvent.RunCompleted)
    durable(events) shouldBe stored(store, threadId, next.runId)
    durable(late) shouldBe stored(store, threadId, crashed.runId)
    runtime.liveSubscriptions(threadId) shouldBe 0
  }

  "A thread-scoped subscription" should "not end at a crashed run's barrier" in {
    val runtime  = GraphRuntime(NoTerminal(throws = false, failing = 1))
    val threadId = ThreadId("crash-thread-scoped")
    val agent    = agentOn(runtime)
    val events   = new CopyOnWriteArrayList[StreamEvent]()
    val sub      = runtime.subscribe(threadId)(e => events.add(e): Unit).fold(e => fail(e.message), identity)
    val crashed  = agent.start(threadId, "one").fold(e => fail(e.message), identity)
    crashed.await().isLeft shouldBe true
    val next = agent.startRecover(threadId).fold(e => fail(e.message), identity)
    next.await().isRight shouldBe true
    // still subscribed: the later run's terminal event reaches it (delivery to it is asynchronous)
    eventually(
      durable(events).filter(_.runId == next.runId.value).lastOption.map(_.event) shouldBe Some(RunEvent.RunCompleted)
    )
    runtime.liveSubscriptions(threadId) shouldBe 1
    sub.cancel()
    runtime.liveSubscriptions(threadId) shouldBe 0
  }

  "agent.stream" should "leave the live set after the run's terminal event" in {
    val runtime  = GraphRuntime.inMemory()
    val threadId = ThreadId("normal")
    val run      = agentOn(runtime).stream(threadId, "hello")(_ => ()).fold(e => fail(e.message), identity)
    run.await().isRight shouldBe true
    runtime.liveSubscriptions(threadId) shouldBe 0
  }

private object RunScopeEndSpec:
  def terminal(event: RunEvent): Boolean = org.llm4s.agent.RunScope.terminal(event)
