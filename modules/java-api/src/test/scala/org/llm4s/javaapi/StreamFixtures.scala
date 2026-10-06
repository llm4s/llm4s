package org.llm4s.javaapi

import org.llm4s.agent.{ Agent, AgentBuilder, AgentResult }
import org.llm4s.agent.graph.{
  Checkpointer,
  Commit,
  EventRecord,
  InMemoryCheckpointer,
  RunEvent,
  StoredCheckpoint,
  StreamEvent,
  ThreadId
}
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import java.util.concurrent.locks.LockSupport
import scala.jdk.CollectionConverters.*

/** Deterministic fixtures for the agent stream specs: latches and parking, no sleeps. */
private[javaapi] object StreamFixtures {

  /** Safety deadline for a condition expected to hold; only a broken implementation reaches it. */
  val DeadlineSeconds: Long = 60L

  /** How long a parked model call waits for an interrupt; longer than every deadline that observes it. */
  val ParkSeconds: Long = 120L

  def completion(text: String): Completion =
    Completion(id = "id", created = 0L, content = text, model = "m", message = AssistantMessage(Some(text)))

  def chunk(i: Int): StreamedChunk = StreamedChunk(id = s"c$i", content = Some("x"))

  /** A client whose `streamComplete` and `complete` run `onStream`, `onComplete`. */
  final class Scripted(
    onStream: (StreamedChunk => Unit) => Result[Completion],
    onComplete: () => Result[Completion]
  ) extends LLMClient {
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] = onComplete()
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      onStream(onChunk)
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 256
  }

  def answering(text: String): LLMClient = new Scripted(_ => Right(completion(text)), () => Right(completion(text)))

  /** An agent over `client`, configured by `configure`, behind the Java facade. */
  def jAgentOf(client: LLMClient)(configure: AgentBuilder => AgentBuilder = identity): JAgent =
    Llm4s.wrapAgent(agentOf(client)(configure))

  def agentOf(client: LLMClient)(configure: AgentBuilder => AgentBuilder = identity): Agent =
    configure(Agent.builder("test", client)).build() match {
      case Right(agent) => agent
      case Left(e)      => throw new AssertionError(s"agent did not build: ${e.message}")
    }

  /** Parks until interrupted, or the safety deadline passes; returns whether it was interrupted. */
  def parkUntilInterrupted(): Boolean = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ParkSeconds)
    while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
      LockSupport.parkNanos(10000000L)
    Thread.currentThread().isInterrupted
  }

  /** A model whose first call sends one delta, then parks until interrupted; later calls answer "recovered". */
  final class ParksOnce {
    private val calls = new AtomicInteger(0)
    val unparked      = new CountDownLatch(1)
    val client: LLMClient = new Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          onChunk(chunk(0))
          if (parkUntilInterrupted()) unparked.countDown()
          Left(org.llm4s.error.CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
  }

  def parksOnce(): ParksOnce = new ParksOnce

  /** Spins (yielding) until `cond` holds; false only if the safety deadline passes. */
  def awaitCondition(cond: => Boolean): Boolean = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DeadlineSeconds)
    while (!cond && System.nanoTime() < deadline) Thread.`yield`()
    cond
  }

  /** A store that refuses every commit carrying `RunCompleted`: the run ends with no terminal event. */
  final class NoTerminal extends Checkpointer {
    private val underlying = InMemoryCheckpointer()
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if (!commit.events.exists(_.event == RunEvent.RunCompleted)) underlying.commit(threadId, commit)
      else Left(ProcessingError("store", "store down"))
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit]                   = underlying.deleteThread(threadId)
  }

  /** An in-memory store that opens `completed` once a commit carrying `RunCompleted` is stored. */
  final class SignalsCompletion extends Checkpointer {
    private val underlying = InMemoryCheckpointer()
    val completed          = new CountDownLatch(1)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = {
      val stored = underlying.commit(threadId, commit)
      if (commit.events.exists(_.event == RunEvent.RunCompleted)) completed.countDown()
      stored
    }
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit]                   = underlying.deleteThread(threadId)
  }

  /** A listener that records every callback, the threads they ran on, and runs `onEach` per event. */
  final class Recorder(onEach: StreamEvent => Unit = _ => ()) extends AgentStreamListener {
    private val received = new CopyOnWriteArrayList[StreamEvent]()
    val threads          = new CopyOnWriteArrayList[Thread]()
    val completed        = new AtomicReference[AgentResult](null)
    val failed           = new AtomicReference[LlmException](null)
    val terminals        = new AtomicInteger(0)
    val ended            = new CountDownLatch(1)

    def onEvent(event: StreamEvent): Unit = {
      threads.add(Thread.currentThread())
      received.add(event)
      onEach(event)
    }
    override def onComplete(result: AgentResult): Unit = terminal(completed.set(result))
    override def onError(error: LlmException): Unit    = terminal(failed.set(error))

    private def terminal(record: => Unit): Unit = {
      threads.add(Thread.currentThread())
      record
      terminals.incrementAndGet()
      ended.countDown()
    }

    def events: Vector[StreamEvent] = received.asScala.toVector
    def durable: Vector[RunEvent]   = events.collect { case StreamEvent.Durable(r) => r.event }
    def gaps: Int                   = events.collect { case StreamEvent.LiveGap(n) => n }.sum
  }
}
