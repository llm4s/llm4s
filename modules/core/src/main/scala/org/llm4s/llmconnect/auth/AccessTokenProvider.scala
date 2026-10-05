package org.llm4s.llmconnect.auth

import org.llm4s.annotation.Experimental
import org.llm4s.types.Result

import java.time.{ Clock, Duration as JDuration, Instant }
import scala.concurrent.duration.*

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
 * fetch. A failed fetch is not cached: the next call tries again.
 */
@Experimental
final class CachingAccessTokenProvider(
  fetch: () => Result[AccessToken],
  refreshMargin: FiniteDuration = CachingAccessTokenProvider.DefaultRefreshMargin,
  clock: Clock = Clock.systemUTC()
) extends AccessTokenProvider:

  final private case class Cached(token: AccessToken, refreshAt: Instant)

  @volatile private var cached: Option[Cached] = None
  private val lock                             = new Object

  def token(): Result[String] =
    fresh() match
      case Some(value) => Right(value)
      case None =>
        lock.synchronized {
          fresh() match
            case Some(value) => Right(value)
            case None =>
              fetch().map { token =>
                cached = Some(Cached(token, refreshAt(token)))
                token.value
              }
        }

  def invalidate(rejected: String): Unit =
    lock.synchronized { cached = cached.filterNot(_.token.value == rejected) }

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
