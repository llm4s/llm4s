package org.llm4s.effect.cats

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import java.util.concurrent.locks.LockSupport

import org.llm4s.error.{ CancelledError, SimpleError }
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
private[cats] object Fixtures {

  val completion: Completion =
    Completion(id = "id", created = 0L, content = "ok", model = "m", message = AssistantMessage(Some("ok")))

  val conversation: Conversation = Conversation(Seq(UserMessage("ping")))

  def chunk(i: Int): StreamedChunk = StreamedChunk(id = s"c$i", content = Some("x"))

  /** Safety deadline for any condition expected to hold; only ever reached by a broken implementation. */
  val DeadlineSeconds: Long = 30L

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
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
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

  val boom: Result[Completion] = Left(SimpleError("boom"))
}
