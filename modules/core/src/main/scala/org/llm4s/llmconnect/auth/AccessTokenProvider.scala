package org.llm4s.llmconnect.auth

import org.llm4s.annotation.{ Experimental, Stable }
import org.llm4s.error.AuthenticationError
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.time.{ Clock, Duration as JDuration, Instant }
import scala.concurrent.duration.*
import scala.util.Try

/**
 * A bearer token and when it stops being valid. The value is a `Secret`, as a key is: build it with
 * `Secret.token(value)`, and read it with `value.reveal` only where it is sent.
 *
 * Build one with the companion `apply` and change it with the `with*` setters; the constructor is private, so a
 * field can be added without breaking callers (#1818).
 */
@Stable
final case class AccessToken private (value: Secret, expiresAt: Instant):
  def withValue(value: Secret): AccessToken          = copy(value = value)
  def withExpiresAt(expiresAt: Instant): AccessToken = copy(expiresAt = expiresAt)

  override def toString: String = s"AccessToken(***, expiresAt=$expiresAt)"

object AccessToken:

  /** Creates an [[AccessToken]]. */
  def apply(value: Secret, expiresAt: Instant): AccessToken = new AccessToken(value, expiresAt)

/**
 * Supplies the bearer token for each request, refreshing it as needed.
 *
 * Stable since #1818: `OpenAICompatibleClient.Credential.Dynamic` takes one. An implementation should return a
 * failure as a `Left` rather than throw; a client that calls it turns a thrown exception into an
 * `AuthenticationError` all the same.
 *
 * One of three sibling suppliers of a credential, each with the one method `current()`: [[ApiKeyProvider]]
 * (an API key), this (a bearer token) and [[IdentityTokenProvider]] (the identity token a token exchange
 * presents).
 */
@Stable
trait AccessTokenProvider:
  /** A token believed valid now. Build it with `Secret.token(value)`, which keeps it out of logs and errors. */
  def current(): Result[Secret]

  /**
   * Reports that the server rejected `rejected`. A provider that caches drops the cached token only if it is that
   * token, so concurrent calls that all saw a 401 for the same token cause one refresh, and a late
   * report about an older token does not discard a newer one.
   *
   * Does nothing by default, which suits a provider that caches nothing: every [[current]] call then asks afresh. With
   * it defaulted the trait has one abstract method, so a lambda is a provider: `() => readToken().map(Secret.token)`.
   */
  def invalidate(@scala.annotation.unused rejected: Secret): Unit = ()

/**
 * Caches the token `fetch` returns until `refreshMargin` before it expires - or half-way through
 * its life, if it lives shorter than twice the margin. Concurrent callers share one in-flight
 * fetch.
 *
 * A failed fetch is shared for a brief window: the callers waiting behind it get the same failure
 * at once instead of each making their own attempt in turn, which during an outage would hold the
 * last of N callers for N times the exchange's timeout. A rejection (an authentication or
 * configuration error, or any other error not marked recoverable) is shared for `FailureTtl`
 * (5 seconds); a transient failure (one that is `isRetryable`: network, timeout, rate
 * limit, 5xx) for `TransientFailureTtl` (1 second) - long enough for the callers queued behind the
 * fetch, short enough that a blip does not outlive itself. After the window the next call tries
 * again; a success, or a rejected cached token, ends it sooner.
 *
 * A fetch that throws instead of returning a `Left` fails like one that returned an `AuthenticationError`
 * naming the exception's class (not its message, which may hold a credential): `token()` never throws for it.
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

  // The cache, the single in-flight fetch, the shared failure and the cancellation rules are SingleFlightCache's.
  private val tokens = new SingleFlightCache[AccessToken](CachingAccessTokenProvider.Operation, clock)

  def current(): Result[Secret] =
    tokens
      .get(() => CachingAccessTokenProvider.guarded(fetch()).map(token => token -> refreshAt(token)))
      .map(_.value)

  /** Interrupted while waiting for the lock, it returns without dropping anything, the flag set again. */
  override def invalidate(rejected: Secret): Unit = tokens.invalidateIf(_.value == rejected)

  private def refreshAt(token: AccessToken): Instant =
    val now      = clock.instant()
    val lifetime = JDuration.between(now, token.expiresAt)
    if lifetime.isNegative || lifetime.isZero then now
    else
      val margin = JDuration.ofMillis(refreshMargin.toMillis)
      val half   = lifetime.dividedBy(2)
      token.expiresAt.minus(if half.compareTo(margin) < 0 then half else margin)

@Experimental
object CachingAccessTokenProvider:
  val DefaultRefreshMargin: FiniteDuration = 60.seconds

  /**
   * `fetch`, with a non-fatal exception it throws returned as an `AuthenticationError` naming the exception's
   * class. `InterruptedException` is not caught here: [[org.llm4s.error.CancelledError.attempt CancelledError.attempt]] turns it into a cancellation.
   */
  private[llm4s] def guarded[A](fetch: => Result[A]): Result[A] =
    Try(fetch).fold(
      e => Left(AuthenticationError(Provider, s"the token fetch threw ${e.getClass.getName}")),
      identity
    )

  private val Provider = ProviderId("workload-identity")

  /** The operation a [[org.llm4s.error.CancelledError CancelledError]] names when a caller is interrupted waiting for a token. */
  private[llm4s] val Operation: String = "workload-identity.token"

  /** How long a rejected fetch (not recoverable) is shared with the callers that arrive meanwhile. */
  private[auth] val FailureTtl: FiniteDuration = SingleFlightCache.FailureTtl

  /** How long a transient failure (a recoverable error) is shared with the callers that arrive meanwhile. */
  private[auth] val TransientFailureTtl: FiniteDuration = SingleFlightCache.TransientFailureTtl
