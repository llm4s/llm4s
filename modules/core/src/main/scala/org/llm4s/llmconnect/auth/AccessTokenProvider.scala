package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.types.Result

import java.time.{ Clock, Duration as JDuration, Instant }
import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.duration.*
import scala.util.Using

/** A bearer token and when it stops being valid. The value is redacted in `toString`. */
@Experimental
final case class AccessToken(value: String, expiresAt: Instant):
  override def toString: String = s"AccessToken(***, expiresAt=$expiresAt)"

/** Supplies the bearer token for each request, refreshing it as needed. */
@Experimental
trait AccessTokenProvider:
  /** A token believed valid now. */
  def token(): Result[String]

  /**
   * Reports that the server rejected `rejected`. The cached token is dropped only if it is that
   * token, so concurrent calls that all saw a 401 for the same token cause one refresh, and a late
   * report about an older token does not discard a newer one.
   */
  def invalidate(rejected: String): Unit

/**
 * Caches the token `fetch` returns until `refreshMargin` before it expires - or half-way through
 * its life, if it lives shorter than twice the margin. Concurrent callers share one in-flight
 * fetch.
 *
 * A failed fetch is shared for a brief window: the callers waiting behind it get the same failure
 * at once instead of each making their own attempt in turn, which during an outage would hold the
 * last of N callers for N times the exchange's timeout. A rejection (an authentication or
 * configuration error, or any other error not marked recoverable) is shared for `FailureTtl`
 * (5 seconds); a transient failure (a [[org.llm4s.error.RecoverableError]]: network, timeout, rate
 * limit, 5xx) for `TransientFailureTtl` (1 second) - long enough for the callers queued behind the
 * fetch, short enough that a blip does not outlive itself. After the window the next call tries
 * again; a success, or a rejected cached token, ends it sooner.
 *
 * A cancellation is never shared. A fetch whose thread is interrupted - it returns
 * `CancelledError`, throws `InterruptedException`, or fails while the flag is set - returns
 * `CancelledError` to that caller alone, with its interrupt flag set, and caches nothing: the next
 * caller through the lock makes its own fetch.
 */
@Experimental
final class CachingAccessTokenProvider(
  fetch: () => Result[AccessToken],
  refreshMargin: FiniteDuration = CachingAccessTokenProvider.DefaultRefreshMargin,
  clock: Clock = Clock.systemUTC()
) extends AccessTokenProvider:

  final private case class Cached(token: AccessToken, refreshAt: Instant)

  final private case class Failed(error: LLMError, until: Instant)

  @volatile private var cached: Option[Cached] = None
  @volatile private var failed: Option[Failed] = None
  // Not `synchronized`: the fetch is blocking I/O, and a monitor pins a virtual thread's carrier
  // for its whole duration, which can starve the very I/O the holder waits on.
  private val lock = new ReentrantLock()

  // Interruptible: a caller cancelled while it waits behind another caller's fetch returns at once
  // with `CancelledError` and its interrupt flag set again, as llm4s cancellation expects.
  private def locked[A](onInterrupt: => A)(body: => A): A =
    CancelledError.catchInterrupt(lock.lockInterruptibly()) match
      case Left(_) =>
        Thread.currentThread().interrupt()
        onInterrupt
      case Right(()) =>
        Using.resource((() => lock.unlock()): AutoCloseable)(_ => body)

  def token(): Result[String] =
    fresh() match
      case Some(value) => Right(value)
      case None =>
        locked[Result[String]](Left(CancelledError(CachingAccessTokenProvider.Operation))) {
          fresh() match
            case Some(value) => Right(value)
            case None =>
              recentFailure() match
                case Some(error) => Left(error)
                case None =>
                  CancelledError.attempt(CachingAccessTokenProvider.Operation)(fetch()) match
                    case Right(token) =>
                      cached = Some(Cached(token, refreshAt(token)))
                      failed = None
                      Right(token.value)
                    case Left(cancelled: CancelledError) =>
                      // Caller-local: this thread was cancelled, not the exchange. Sharing it would
                      // hand a cancellation to callers nobody interrupted.
                      if !Thread.currentThread().isInterrupted then Thread.currentThread().interrupt()
                      Left(cancelled)
                    case Left(error) =>
                      failed = Some(Failed(error, clock.instant().plusMillis(failureTtl(error).toMillis)))
                      Left(error)
        }

  /** Interrupted while waiting for the lock, it returns without dropping anything, the flag set again. */
  def invalidate(rejected: String): Unit =
    locked(()) {
      if cached.exists(_.token.value == rejected) then
        cached = None
        failed = None
    }

  private def failureTtl(error: LLMError): FiniteDuration =
    if LLMError.isRecoverable(error) then CachingAccessTokenProvider.TransientFailureTtl
    else CachingAccessTokenProvider.FailureTtl

  private def recentFailure(): Option[LLMError] =
    failed.collect { case Failed(error, until) if clock.instant().isBefore(until) => error }

  private def fresh(): Option[String] =
    cached.collect { case Cached(token, at) if clock.instant().isBefore(at) => token.value }

  private def refreshAt(token: AccessToken): Instant =
    val now      = clock.instant()
    val lifetime = JDuration.between(now, token.expiresAt)
    if lifetime.isNegative || lifetime.isZero then now
    else
      val margin = JDuration.ofMillis(refreshMargin.toMillis)
      val half   = lifetime.dividedBy(2)
      token.expiresAt.minus(if half.compareTo(margin) < 0 then half else margin)

object CachingAccessTokenProvider:
  val DefaultRefreshMargin: FiniteDuration = 60.seconds

  /** The operation a [[CancelledError]] names when a caller is interrupted waiting for a token. */
  private[llm4s] val Operation: String = "workload-identity.token"

  /** How long a rejected fetch (not recoverable) is shared with the callers that arrive meanwhile. */
  private[auth] val FailureTtl: FiniteDuration = 5.seconds

  /** How long a transient failure (a recoverable error) is shared with the callers that arrive meanwhile. */
  private[auth] val TransientFailureTtl: FiniteDuration = 1.second
