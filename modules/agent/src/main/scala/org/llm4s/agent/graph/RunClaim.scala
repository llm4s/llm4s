package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.time.Instant
import scala.concurrent.duration.*

/**
 * A run's lease on a thread, as a [[Checkpointer]] granted it: the run that holds it, the
 * [[FencingToken]] its commits must carry, and when it expires by the store's clock unless renewed.
 * While it is live - `expiresAt` has not passed - no other claim on the thread is granted, so a run
 * in another runtime or process is refused with [[GraphError.ThreadBusy]]. Once it has expired,
 * another run may take the thread over, and the store then refuses this claim's token
 * ([[GraphError.StaleClaim]]). Until then the holder may still commit and renew, even past
 * `expiresAt`: expiry only lets another run claim the thread.
 */
final case class RunClaim private (threadId: ThreadId, holder: RunId, token: FencingToken, expiresAt: Instant):
  def withThreadId(t: ThreadId): RunClaim  = copy(threadId = t)
  def withHolder(h: RunId): RunClaim       = copy(holder = h)
  def withToken(t: FencingToken): RunClaim = copy(token = t)
  def withExpiresAt(at: Instant): RunClaim = copy(expiresAt = at)

object RunClaim:
  def apply(threadId: ThreadId, holder: RunId, token: FencingToken, expiresAt: Instant): RunClaim =
    new RunClaim(threadId, holder, token, expiresAt)

/**
 * What a run asks of [[Checkpointer.claim]]: who holds the claim, and for how long it lasts from the
 * moment the store grants it, by the store's clock. `apply` and the `with*` setters throw
 * `IllegalArgumentException` for a non-positive `ttl`; [[ClaimRequest.of]] returns it as a
 * `ValidationError`.
 */
final case class ClaimRequest private (holder: RunId, ttl: FiniteDuration):
  def withHolder(h: RunId): ClaimRequest       = ClaimRequest(h, ttl)
  def withTtl(t: FiniteDuration): ClaimRequest = ClaimRequest(holder, t)

object ClaimRequest:
  private def problems(ttl: FiniteDuration): List[String] =
    Option.when(ttl.length <= 0)(s"ttl must be positive, was $ttl").toList

  /** Throws `IllegalArgumentException` for a non-positive `ttl`; use [[of]] for untrusted input. */
  def apply(holder: RunId, ttl: FiniteDuration): ClaimRequest =
    val found = problems(ttl)
    require(found.isEmpty, found.mkString("; "))
    new ClaimRequest(holder, ttl)

  def of(holder: RunId, ttl: FiniteDuration): Result[ClaimRequest] =
    problems(ttl) match
      case Nil   => Right(new ClaimRequest(holder, ttl))
      case found => Left(ValidationError("claim", found))

/**
 * How a [[GraphRuntime]] holds its runs' claims: each claim lasts `ttl` from when the store grants or
 * renews it, and a running run renews it every `renewEvery`, on a virtual thread named
 * `llm4s-claim-<threadId>`, until the run ends and releases it. A process that dies stops renewing,
 * so after at most `ttl` its threads can be recovered elsewhere; a live run that cannot reach its
 * store for longer than `ttl` can lose its claim the same way, after which its commits are refused
 * ([[GraphError.StaleClaim]]) and it fails with [[GraphError.CheckpointWriteFailed]].
 *
 * Both must be positive and `renewEvery` shorter than `ttl`: `apply` and the `with*` setters throw
 * `IllegalArgumentException` otherwise, and [[ClaimPolicy.of]] returns a `ValidationError`.
 */
final case class ClaimPolicy private (ttl: FiniteDuration, renewEvery: FiniteDuration):
  def withTtl(t: FiniteDuration): ClaimPolicy        = ClaimPolicy(t, renewEvery)
  def withRenewEvery(r: FiniteDuration): ClaimPolicy = ClaimPolicy(ttl, r)

object ClaimPolicy:
  private def problems(ttl: FiniteDuration, renewEvery: FiniteDuration): List[String] =
    List(
      Option.when(ttl.length <= 0)(s"ttl must be positive, was $ttl"),
      Option.when(renewEvery.length <= 0)(s"renewEvery must be positive, was $renewEvery"),
      Option.when(ttl.length > 0 && renewEvery.length > 0 && renewEvery >= ttl)(
        s"renewEvery ($renewEvery) must be shorter than ttl ($ttl)"
      )
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid policy; use [[of]] for untrusted input. */
  def apply(ttl: FiniteDuration = 30.seconds, renewEvery: FiniteDuration = 10.seconds): ClaimPolicy =
    val found = problems(ttl, renewEvery)
    require(found.isEmpty, found.mkString("; "))
    new ClaimPolicy(ttl, renewEvery)

  def of(ttl: FiniteDuration = 30.seconds, renewEvery: FiniteDuration = 10.seconds): Result[ClaimPolicy] =
    problems(ttl, renewEvery) match
      case Nil   => Right(new ClaimPolicy(ttl, renewEvery))
      case found => Left(ValidationError("claims", found))

  /** A 30 second claim, renewed every 10 seconds. */
  val default: ClaimPolicy = apply()
