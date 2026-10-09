package org.llm4s.javaapi

import org.llm4s.agent.{ Agent, AgentBuilder }
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
  final class ParksOnce(deltas: Int, linger: CountDownLatch) {
    private val calls = new AtomicInteger(0)
    val parked        = new CountDownLatch(1)
    val unparked      = new CountDownLatch(1)
    val client: LLMClient = new Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          (0 until deltas).foreach(i => onChunk(chunk(i)))
          parked.countDown()
          if (parkUntilInterrupted()) unparked.countDown()
          // once interrupted, the call does not return until `linger` opens
          Thread.interrupted(): Unit
          linger.await(DeadlineSeconds, TimeUnit.SECONDS): Unit
          Left(org.llm4s.error.CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
  }

  /** A model whose first call sends `deltas` deltas, parks until interrupted, then returns once `linger` opens. */
  def parksOnce(deltas: Int = 1, linger: CountDownLatch = new CountDownLatch(0)): ParksOnce =
    new ParksOnce(deltas, linger)

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

  /**
   * A fatal error for a listener to throw. `Safety` does not capture it, so it ends the stream's thread
   * uncaught, by design - after `await` has already returned. Left to the default handler, it would be printed
   * to `System.err` whenever that thread gets to it, into whatever another spec is capturing then (#1719).
   * [[raise]] gives the throwing thread its own handler, which records the error instead, and [[awaitDeath]]
   * waits for that thread to end, so nothing of it outlives the test.
   */
  final class FatalListenerError(error: Throwable) {
    private val thread = new AtomicReference[Thread](null)
    private val died   = new AtomicReference[Throwable](null)

    /** Throws `error` from the calling (the stream's) thread, once that thread's handler records it. */
    def raise(): Nothing = {
      val current = Thread.currentThread()
      current.setUncaughtExceptionHandler((_, e) => died.set(e))
      thread.set(current)
      throw error
    }

    /** Waits for the thread that raised to end; returns what it died of - null if it did not end, or not of an uncaught error. */
    def awaitDeath(): Throwable =
      Option(thread.get).filter(_.join(java.time.Duration.ofSeconds(DeadlineSeconds))).map(_ => died.get).orNull
  }

  /** A listener that records every callback, the threads they ran on, and runs `onEach` per event. */
  final class Recorder(onEach: StreamEvent => Unit = _ => ()) extends AgentStreamListener {
    private val received = new CopyOnWriteArrayList[StreamEvent]()
    val threads          = new CopyOnWriteArrayList[Thread]()
    val completed        = new AtomicReference[JAgentResult](null)
    val failed           = new AtomicReference[LlmException](null)
    val terminals        = new AtomicInteger(0)
    val ended            = new CountDownLatch(1)

    def onEvent(event: StreamEvent): Unit = {
      threads.add(Thread.currentThread())
      received.add(event)
      onEach(event)
    }
    override def onComplete(result: JAgentResult): Unit = terminal(completed.set(result))
    override def onError(error: LlmException): Unit     = terminal(failed.set(error))

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
