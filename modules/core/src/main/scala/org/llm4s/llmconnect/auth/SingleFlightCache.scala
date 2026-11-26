package org.llm4s.llmconnect.auth

import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.types.Result

import java.time.{ Clock, Instant }
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration.*
import scala.util.Using

/**
 * One value that is slow to load - a token exchanged, a key read from a secret store - cached until the instant its
 * load names, with one load in flight at a time. [[CachingAccessTokenProvider]] and the secret-store
 * [[ApiKeyProvider]]s (`llm4s-secrets-vault`, `llm4s-secrets-aws`) are built on it.
 *
 * A failed load is shared for a brief window: the callers waiting behind it get the same failure at once instead of
 * each making their own attempt in turn, which during an outage would hold the last of N callers for N times the
 * load's timeout. A failure that is not `isRetryable` is shared for [[SingleFlightCache.FailureTtl]], one that is for
 * [[SingleFlightCache.TransientFailureTtl]]. After the window the next call loads again; a success, or
 * [[invalidateIf]] dropping the cached value, ends it sooner. A value past its instant is never returned in place of
 * a failure.
 *
 * A cancellation is never shared. A load whose thread is interrupted - it returns `CancelledError`, throws
 * `InterruptedException`, or fails while the flag is set - returns `CancelledError` to that caller alone, with its
 * interrupt flag set, and caches nothing: the next caller through the lock makes its own load. So does a caller
 * interrupted while it waits behind another caller's load, which returns at once.
 *
 * @param operation the operation a `CancelledError` names
 * @param clock     tells whether the value is still fresh and when a failure's window ends
 */
final private[llm4s] class SingleFlightCache[A](operation: String, clock: Clock):

  import SingleFlightCache.{ Cached, Failed }

  @volatile private var cached: Option[Cached[A]] = None
  @volatile private var failed: Option[Failed]    = None
  // Not `synchronized`: the load is blocking I/O, and a monitor pins a virtual thread's carrier
  // for its whole duration, which can starve the very I/O the holder waits on.
  private val lock = new ReentrantLock()

  // Interruptible: a caller cancelled while it waits behind another caller's load returns at once
  // with its interrupt flag set again, as llm4s cancellation expects.
  private def locked[B](onInterrupt: => B)(body: => B): B =
    CancelledError.catchInterrupt(lock.lockInterruptibly()) match
      case Left(_) =>
        Thread.currentThread().interrupt()
        onInterrupt
      case Right(()) =>
        Using.resource((() => lock.unlock()): AutoCloseable)(_ => body)

  /**
   * The cached value while it is fresh, else what `load` returns: the value and the instant until which it is served
   * without loading again. An exception `load` throws propagates, caching nothing.
   */
  def get(load: () => Result[(A, Instant)]): Result[A] =
    fresh() match
      case Some(value) => Right(value)
      case None =>
        locked[Result[A]](Left(CancelledError(operation))) {
          // Another caller may have loaded the value, or failed to, while this one waited.
          fresh() match
            case Some(value) => Right(value)
            case None =>
              recentFailure() match
                case Some(error) => Left(error)
                case None =>
                  CancelledError.attempt(operation)(load()) match
                    case Right((value, freshUntil)) =>
                      cached = Some(Cached(value, freshUntil))
                      failed = None
                      Right(value)
                    case Left(cancelled: CancelledError) =>
                      // Caller-local: this thread was cancelled, not the load. Sharing it would
                      // hand a cancellation to callers nobody interrupted.
                      if !Thread.currentThread().isInterrupted then Thread.currentThread().interrupt()
                      Left(cancelled)
                    case Left(error) =>
                      failed = Some(Failed(error, clock.instant().plusMillis(SingleFlightCache.ttlOf(error).toMillis)))
                      Left(error)
        }

  /**
   * Drops the cached value, and any shared failure, if `matches` holds for it. Interrupted while waiting for the
   * lock, it returns without dropping anything, the flag set again.
   */
  def invalidateIf(matches: A => Boolean): Unit =
    locked(()) {
      if cached.exists(c => matches(c.value)) then
        cached = None
        failed = None
    }

  private def recentFailure(): Option[LLMError] =
    failed.collect { case Failed(error, until) if clock.instant().isBefore(until) => error }

  private def fresh(): Option[A] =
    cached.collect { case Cached(value, until) if clock.instant().isBefore(until) => value }

private[llm4s] object SingleFlightCache:

  final private case class Cached[A](value: A, freshUntil: Instant)

  final private case class Failed(error: LLMError, until: Instant)

  /** How long a failed load that is not retryable (a rejection) is shared with the callers that arrive meanwhile. */
  val FailureTtl: FiniteDuration = 5.seconds

  /** How long a transient failure (a retryable error) is shared with the callers that arrive meanwhile. */
  val TransientFailureTtl: FiniteDuration = 1.second

  private def ttlOf(error: LLMError): FiniteDuration =
    if error.isRetryable then TransientFailureTtl else FailureTtl
