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
 * ([[GraphError.StaleClaim]]). `tenantId` is the tenant the claim was requested for
 * ([[ClaimRequest.tenantId]]). `takenOverFrom` is the holder of the expired claim, never released,
 * that this one replaced, named only when that claim was of the same tenant as this one: `None` when
 * the thread was free, and when the replaced claim was another tenant's or recorded no tenant (a
 * claim a store persisted before claims carried one), so that a takeover never reveals another
 * tenant's [[RunId]].
 *
 * To the store, the holder may still commit and renew past `expiresAt` until another claim replaces
 * it: expiry only lets another run claim the thread. A [[GraphRuntime]] does not rely on that: a run
 * judges its own lease, by its monotonic clock from just before it sent the request that granted or
 * last renewed the claim, and stops with [[GraphError.ClaimLost]] once that lease has passed. That is no
 * later than the store's expiry provided the store judges it by one clock that does not run faster than
 * the runtime's monotonic clock and is not stepped forward; a constant offset does not matter. A store
 * clock that runs ahead - stepped forward, or each process of a shared store on its own skewed clock -
 * can expire the claim early, and another run can take the thread over while this one still runs: it
 * then stops at its next refused renewal or commit, and fencing keeps every write safe.
 */
final case class RunClaim private (
  threadId: ThreadId,
  holder: RunId,
  tenantId: Option[TenantId],
  token: FencingToken,
  expiresAt: Instant,
  takenOverFrom: Option[RunId]
):
  def withThreadId(t: ThreadId): RunClaim              = copy(threadId = t)
  def withHolder(h: RunId): RunClaim                   = copy(holder = h)
  def withTenantId(t: TenantId): RunClaim              = copy(tenantId = Some(t))
  def withTenantId(t: Option[TenantId]): RunClaim      = copy(tenantId = t)
  def withToken(t: FencingToken): RunClaim             = copy(token = t)
  def withExpiresAt(at: Instant): RunClaim             = copy(expiresAt = at)
  def withTakenOverFrom(from: RunId): RunClaim         = copy(takenOverFrom = Some(from))
  def withTakenOverFrom(from: Option[RunId]): RunClaim = copy(takenOverFrom = from)

object RunClaim:
  def apply(
    threadId: ThreadId,
    holder: RunId,
    tenantId: Option[TenantId],
    token: FencingToken,
    expiresAt: Instant,
    takenOverFrom: Option[RunId] = None
  ): RunClaim =
    new RunClaim(threadId, holder, tenantId, token, expiresAt, takenOverFrom)

/**
 * What a run asks of [[Checkpointer.claim]]: who holds the claim, the tenant it holds it for (the run's
 * [[RunConfig.tenantId]]; `None` and `Some` differ, as for a thread), and for how long it lasts from the
 * moment the store grants it, by the store's clock. The store records the tenant with the claim, and
 * names the claim's holder to a later request - in a refusal, or as [[RunClaim.takenOverFrom]] - only
 * when that request is of the same tenant. `apply` and the `with*` setters throw
 * `IllegalArgumentException` for a non-positive `ttl`; [[ClaimRequest.of]] returns it as a
 * `ValidationError`.
 */
final case class ClaimRequest private (holder: RunId, tenantId: Option[TenantId], ttl: FiniteDuration):
  def withHolder(h: RunId): ClaimRequest              = ClaimRequest(h, tenantId, ttl)
  def withTenantId(t: TenantId): ClaimRequest         = ClaimRequest(holder, Some(t), ttl)
  def withTenantId(t: Option[TenantId]): ClaimRequest = ClaimRequest(holder, t, ttl)
  def withTtl(t: FiniteDuration): ClaimRequest        = ClaimRequest(holder, tenantId, t)

object ClaimRequest:
  private def problems(ttl: FiniteDuration): List[String] =
    Option.when(ttl.length <= 0)(s"ttl must be positive, was $ttl").toList

  /** Throws `IllegalArgumentException` for a non-positive `ttl`; use [[of]] for untrusted input. */
  def apply(holder: RunId, tenantId: Option[TenantId], ttl: FiniteDuration): ClaimRequest =
    val found = problems(ttl)
    require(found.isEmpty, found.mkString("; "))
    new ClaimRequest(holder, tenantId, ttl)

  def of(holder: RunId, tenantId: Option[TenantId], ttl: FiniteDuration): Result[ClaimRequest] =
    problems(ttl) match
      case Nil   => Right(new ClaimRequest(holder, tenantId, ttl))
      case found => Left(ValidationError("claim", found))

/**
 * How a [[GraphRuntime]] holds its runs' claims: each claim lasts `ttl` from when the store grants or
 * renews it, and is renewed every `renewEvery` from its grant - also while the run is still being
 * admitted, and while `fork` or `updateState` holds a thread - until it is released. A process that
 * dies stops renewing, so after at most `ttl` its threads can be recovered elsewhere.
 *
 * A run judges its own lease: it ends `ttl` after the moment, by the runtime's monotonic clock, just
 * before the request that granted or last renewed the claim was sent. A run whose renewals fail or
 * hang until that lease passes - its store is unreachable, or partitioned from it - stops at once, as
 * one whose renewal or commit the store refuses as stale does: its tasks are interrupted, it commits
 * nothing more, and it fails with [[GraphError.ClaimLost]]. A renewal that fails is tried again at the
 * next beat while the lease lasts. Tasks already running when the loss is found may still complete a
 * side effect before they see their interrupt.
 *
 * A renewal must land within `ttl - renewEvery` of being due, or the run judges its lease passed and
 * stops: keep `renewEvery` at most a third of `ttl`, as the default does, so a renewal can fail or be slow
 * once and still be retried in time.
 *
 * `label`, when set, names the runtime to an operator: it is logged with the run id when a claim is
 * granted (at INFO; without a label, at DEBUG) and when a renewal fails or the claim is lost, so that the
 * holder a [[GraphError.ThreadBusy]] names can be traced to the runtime holding it - a host, a pod, a
 * process. It goes nowhere else: not to the store, and not into an error another caller gets.
 *
 * Both durations must be positive and `renewEvery` shorter than `ttl`, and a label must not be blank:
 * `apply` and the `with*` setters throw `IllegalArgumentException` otherwise, and [[ClaimPolicy.of]]
 * returns a `ValidationError`.
 */
final case class ClaimPolicy private (ttl: FiniteDuration, renewEvery: FiniteDuration, label: Option[String]):
  def withTtl(t: FiniteDuration): ClaimPolicy        = ClaimPolicy(t, renewEvery, label)
  def withRenewEvery(r: FiniteDuration): ClaimPolicy = ClaimPolicy(ttl, r, label)
  def withLabel(l: String): ClaimPolicy              = ClaimPolicy(ttl, renewEvery, Some(l))
  def withLabel(l: Option[String]): ClaimPolicy      = ClaimPolicy(ttl, renewEvery, l)

object ClaimPolicy:
  private def problems(ttl: FiniteDuration, renewEvery: FiniteDuration, label: Option[String]): List[String] =
    List(
      Option.when(ttl.length <= 0)(s"ttl must be positive, was $ttl"),
      Option.when(renewEvery.length <= 0)(s"renewEvery must be positive, was $renewEvery"),
      Option.when(ttl.length > 0 && renewEvery.length > 0 && renewEvery >= ttl)(
        s"renewEvery ($renewEvery) must be shorter than ttl ($ttl)"
      ),
      Option.when(label.exists(_.isBlank))("label must not be blank")
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid policy; use [[of]] for untrusted input. */
  def apply(
    ttl: FiniteDuration = 30.seconds,
    renewEvery: FiniteDuration = 10.seconds,
    label: Option[String] = None
  ): ClaimPolicy =
    val found = problems(ttl, renewEvery, label)
    require(found.isEmpty, found.mkString("; "))
    new ClaimPolicy(ttl, renewEvery, label)

  def of(
    ttl: FiniteDuration = 30.seconds,
    renewEvery: FiniteDuration = 10.seconds,
    label: Option[String] = None
  ): Result[ClaimPolicy] =
    problems(ttl, renewEvery, label) match
      case Nil   => Right(new ClaimPolicy(ttl, renewEvery, label))
      case found => Left(ValidationError("claims", found))

  /** A 30 second claim, renewed every 10 seconds, with no label. */
  val default: ClaimPolicy = apply()
