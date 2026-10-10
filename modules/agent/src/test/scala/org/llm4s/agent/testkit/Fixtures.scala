package org.llm4s.agent.testkit

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import java.util.concurrent.locks.LockSupport

import org.llm4s.agent.{ Agent, AgentBuilder }
import org.llm4s.agent.graph.{ GraphError, RunContext, ThreadId }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId }
import org.llm4s.error.{ CancelledError, LLMError, SimpleError, UnknownError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  StreamedChunk,
  UserMessage
}
import org.llm4s.types.Result

/** Shared deterministic fixtures for the concurrency specs: no sleeps, only latches and parking. */
object Fixtures {

  val completion: Completion =
    Completion(id = "id", created = 0L, content = "ok", model = "m", message = AssistantMessage(Some("ok")))

  val conversation: Conversation = Conversation(Seq(UserMessage("ping")))

  def chunk(i: Int): StreamedChunk = StreamedChunk(id = s"c$i", content = Some("x"))

  /** Safety deadline for any condition expected to hold; only ever reached by a broken implementation. */
  val DeadlineSeconds: Long = 60L

  /** How long a parked provider call waits for an interrupt; longer than every deadline that observes it. */
  val ParkSeconds: Long = 120L

  /** Upper bound for a cancellation that must be prompt; below `ParkSeconds`, so a call that ignores the interrupt still fails it. */
  val PromptSeconds: Long = 60L

  /** Spins (yielding) until `cond` holds; returns false only if the safety deadline passes. */
  def awaitCondition(cond: => Boolean): Boolean = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DeadlineSeconds)
    while (!cond && System.nanoTime() < deadline) Thread.`yield`()
    cond
  }

  /**
   * Parks the current thread until it is interrupted (or the safety deadline passes, so a broken
   * implementation cannot leak the thread forever). Returns whether it was interrupted.
   */
  def parkUntilInterrupted(): Boolean = {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ParkSeconds)
    while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
      LockSupport.parkNanos(10000000L)
    Thread.currentThread().isInterrupted
  }

  /** A client with a scripted `streamComplete` and `complete`; everything else is fixed. */
  final class Scripted(
    onStream: (StreamedChunk => Unit) => Result[Completion] = _ => Right(completion),
    onComplete: () => Result[Completion] = () => Right(completion)
  ) extends LLMClient {
    val streamCalls: AtomicInteger                                          = new AtomicInteger(0)
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] = onComplete()
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] = {
      streamCalls.incrementAndGet()
      onStream(onChunk)
    }
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 256
  }

  /** Emits `n` chunks, counting entries to and returns from `onChunk`, then finishes with `end`. */
  final class Counting(n: Int, end: () => Result[Completion] = () => Right(completion)) {
    val entered: AtomicInteger          = new AtomicInteger(0)
    val returned: AtomicInteger         = new AtomicInteger(0)
    val finished: AtomicBoolean         = new AtomicBoolean(false)
    val exited: CountDownLatch          = new CountDownLatch(1)
    val interrupted: AtomicBoolean      = new AtomicBoolean(false)
    val thread: AtomicReference[Thread] = new AtomicReference[Thread](null)
    val client: Scripted = new Scripted(onChunk => {
      thread.set(Thread.currentThread())
      val outcome = CancelledError.catchInterrupt {
        (0 until n).foreach { i =>
          entered.incrementAndGet()
          onChunk(chunk(i))
          returned.incrementAndGet()
        }
      }
      val result: Result[Completion] = outcome match {
        case Left(_) =>
          interrupted.set(true)
          Left(CancelledError("test"))
        case Right(_) =>
          finished.set(true)
          end()
      }
      exited.countDown()
      result
    })
  }

  /**
   * Waits until the producer of `counting` is blocked on a full queue: it has produced more than
   * the queue capacity, its thread is parked, and it has made no progress for a long run of
   * consecutive samples spanning at least `QuiescentNanos`. Sampling is by spinning, never by
   * sleeping. The quiescence window only guards against a cold-start stall being mistaken for
   * backpressure (which would let a broken unbounded buffer pass); a correct implementation always
   * satisfies it. Returns early if the call finishes, which an unbounded buffer would allow.
   */
  def awaitBlocked(counting: Counting): Unit = {
    val QuiescentNanos = TimeUnit.MILLISECONDS.toNanos(250L)
    var last           = -1
    var stable         = 0
    var since          = System.nanoTime()
    val deadline       = System.nanoTime() + TimeUnit.SECONDS.toNanos(DeadlineSeconds)
    var done           = false
    while (!done && !counting.finished.get() && System.nanoTime() < deadline) {
      val now    = counting.entered.get()
      val thread = counting.thread.get()
      val parked = thread != null && (thread.getState match {
        case Thread.State.WAITING | Thread.State.TIMED_WAITING | Thread.State.BLOCKED => true
        case _                                                                        => false
      })
      if (now == last && now > 64 && parked) {
        stable += 1
        done = stable >= 20000 && System.nanoTime() - since >= QuiescentNanos
      } else {
        last = now
        stable = 0
        since = System.nanoTime()
      }
      Thread.`yield`()
    }
  }

  /** A stream call that emits one chunk then parks until interrupted, recording what happened. */
  final class ParksAfterFirst {
    val started: CountDownLatch         = new CountDownLatch(1)
    val exited: CountDownLatch          = new CountDownLatch(1)
    val interruptions: AtomicInteger    = new AtomicInteger(0)
    val thread: AtomicReference[Thread] = new AtomicReference[Thread](null)
    val client: Scripted = new Scripted(onChunk => {
      thread.set(Thread.currentThread())
      val sent = CancelledError.catchInterrupt(onChunk(chunk(1)))
      started.countDown()
      val wasInterrupted = sent.isLeft || parkUntilInterrupted()
      if (wasInterrupted) interruptions.incrementAndGet()
      exited.countDown()
      Left(CancelledError("test"))
    })
  }

  /** An [[Agent]] over `client`, configured by `configure`; a builder that does not build fails the test. */
  def agentOf(client: LLMClient)(configure: AgentBuilder => AgentBuilder = identity): Agent =
    configure(Agent.builder("test", client)).build() match {
      case Right(agent) => agent
      case Left(e)      => throw new AssertionError(s"agent did not build: ${e.message}")
    }

  /** The error a node failed with: the runtime wraps a failing model call in `NodeFailed`. */
  def causeOf(e: LLMError): LLMError = e match {
    case GraphError.NodeFailed(_, _, cause) => cause
    case other                              => other
  }

  /** The throwable a node threw, as the runtime kept it: `NodeFailed` over an `UnknownError` over the throwable. */
  def thrownOf(e: LLMError): Option[Throwable] = causeOf(e) match {
    case u: UnknownError => Some(u.cause)
    case _               => None
  }

  /** A middleware that records the thread of the run it sees, for specs that need to `recover` a failed run. */
  def threadCapture(): (AgentMiddleware, AtomicReference[Option[ThreadId]]) = {
    val thread = new AtomicReference[Option[ThreadId]](None)
    val middleware = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("capture")
      override def beforeAgent(text: String, context: RunContext): Result[String] = {
        thread.set(Some(context.position.threadId))
        Right(text)
      }
    }
    (middleware, thread)
  }

  val boom: Result[Completion] = Left(SimpleError("boom"))
}
