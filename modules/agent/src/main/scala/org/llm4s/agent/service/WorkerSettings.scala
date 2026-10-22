package org.llm4s.agent.service

import org.llm4s.agent.graph.SweepPolicy
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * How an [[AgentWorker]] runs (Stage 5 W2, design 4.56), loaded from `llm4s.agent.worker` by
 * [[org.llm4s.config.WorkerConfigLoader WorkerConfigLoader]]:
 *
 *  - `concurrency`: the most runs the worker has at once (default 4).
 *  - `pollInterval`: how long the worker waits before it asks the queue again once it found nothing to take (default 1
 *    second), and how long it passes over an entry it deferred to a live run on the thread.
 *  - `unrunnableAfter`: how long an entry may wait for a worker whose graph version can restore its thread before a
 *    worker that cannot closes it `Failed(Unrunnable)` (default 1 hour): long enough for a rolling upgrade's old
 *    workers to finish their runs. Judged by the store's clock, from the entry's submission or its latest attempt.
 *  - `drainGrace`: on shutdown, how long running runs have to reach a suspension or their end before the worker cancels
 *    them (default 30 seconds).
 *  - `exitTimeout`: how long a cancelled run then has to exit before the worker gives up on it (default 10 seconds). A
 *    run that has not exited keeps its claim and lease, which expire after their ttl once the process exits.
 *  - `sweepEvery`: how often the worker runs the store-wide retention sweep (`Checkpointer.sweep`) and reclaims closed
 *    entries past their ticket lifetime (`RunQueue.reclaim`), one instance of the fleet at a time; `None` turns the
 *    sweep off (default 5 minutes).
 *  - `sweep`: what the sweep prunes and reclaims (`SweepPolicy`; default `SweepPolicy.keepAll`, which prunes nothing,
 *    so the sweep only reclaims expired queue entries).
 *  - `label`: names the worker in its lease holder and its logs, such as a host or pod name; a random suffix keeps two
 *    workers with one label apart.
 *
 * Every duration must be positive, `concurrency` at least 1 and a label not blank: `apply` and the `with*` setters throw
 * `IllegalArgumentException` otherwise, and [[WorkerSettings.of]] returns a `ValidationError`.
 */
@Experimental
final case class WorkerSettings private (
  concurrency: Int,
  pollInterval: FiniteDuration,
  unrunnableAfter: FiniteDuration,
  drainGrace: FiniteDuration,
  exitTimeout: FiniteDuration,
  sweepEvery: Option[FiniteDuration],
  sweep: SweepPolicy,
  label: Option[String]
):
  private def changed(
    concurrency: Int = concurrency,
    pollInterval: FiniteDuration = pollInterval,
    unrunnableAfter: FiniteDuration = unrunnableAfter,
    drainGrace: FiniteDuration = drainGrace,
    exitTimeout: FiniteDuration = exitTimeout,
    sweepEvery: Option[FiniteDuration] = sweepEvery,
    sweep: SweepPolicy = sweep,
    label: Option[String] = label
  ): WorkerSettings =
    WorkerSettings(concurrency, pollInterval, unrunnableAfter, drainGrace, exitTimeout, sweepEvery, sweep, label)

  def withConcurrency(n: Int): WorkerSettings                   = changed(concurrency = n)
  def withPollInterval(d: FiniteDuration): WorkerSettings       = changed(pollInterval = d)
  def withUnrunnableAfter(d: FiniteDuration): WorkerSettings    = changed(unrunnableAfter = d)
  def withDrainGrace(d: FiniteDuration): WorkerSettings         = changed(drainGrace = d)
  def withExitTimeout(d: FiniteDuration): WorkerSettings        = changed(exitTimeout = d)
  def withSweepEvery(d: FiniteDuration): WorkerSettings         = changed(sweepEvery = Some(d))
  def withSweepEvery(d: Option[FiniteDuration]): WorkerSettings = changed(sweepEvery = d)
  def withSweep(policy: SweepPolicy): WorkerSettings            = changed(sweep = policy)
  def withLabel(l: String): WorkerSettings                      = changed(label = Some(l))
  def withLabel(l: Option[String]): WorkerSettings              = changed(label = l)

@Experimental
object WorkerSettings:

  val DefaultConcurrency: Int                = 4
  val DefaultPollInterval: FiniteDuration    = 1.second
  val DefaultUnrunnableAfter: FiniteDuration = 1.hour
  val DefaultDrainGrace: FiniteDuration      = 30.seconds
  val DefaultExitTimeout: FiniteDuration     = 10.seconds
  val DefaultSweepEvery: FiniteDuration      = 5.minutes

  private def problems(
    concurrency: Int,
    pollInterval: FiniteDuration,
    unrunnableAfter: FiniteDuration,
    drainGrace: FiniteDuration,
    exitTimeout: FiniteDuration,
    sweepEvery: Option[FiniteDuration],
    label: Option[String]
  ): List[String] =
    def positive(name: String, d: FiniteDuration) = Option.when(d.length <= 0)(s"$name must be positive, was $d")
    List(
      Option.when(concurrency < 1)(s"concurrency must be at least 1, was $concurrency"),
      positive("pollInterval", pollInterval),
      positive("unrunnableAfter", unrunnableAfter),
      positive("drainGrace", drainGrace),
      positive("exitTimeout", exitTimeout),
      sweepEvery.flatMap(positive("sweepEvery", _)),
      Option.when(label.exists(_.isBlank))("label must not be blank")
    ).flatten

  /** Throws `IllegalArgumentException` for invalid settings; use [[of]] for untrusted input. */
  def apply(
    concurrency: Int = DefaultConcurrency,
    pollInterval: FiniteDuration = DefaultPollInterval,
    unrunnableAfter: FiniteDuration = DefaultUnrunnableAfter,
    drainGrace: FiniteDuration = DefaultDrainGrace,
    exitTimeout: FiniteDuration = DefaultExitTimeout,
    sweepEvery: Option[FiniteDuration] = Some(DefaultSweepEvery),
    sweep: SweepPolicy = SweepPolicy.keepAll,
    label: Option[String] = None
  ): WorkerSettings =
    val found = problems(concurrency, pollInterval, unrunnableAfter, drainGrace, exitTimeout, sweepEvery, label)
    require(found.isEmpty, found.mkString("; "))
    new WorkerSettings(concurrency, pollInterval, unrunnableAfter, drainGrace, exitTimeout, sweepEvery, sweep, label)

  def of(
    concurrency: Int = DefaultConcurrency,
    pollInterval: FiniteDuration = DefaultPollInterval,
    unrunnableAfter: FiniteDuration = DefaultUnrunnableAfter,
    drainGrace: FiniteDuration = DefaultDrainGrace,
    exitTimeout: FiniteDuration = DefaultExitTimeout,
    sweepEvery: Option[FiniteDuration] = Some(DefaultSweepEvery),
    sweep: SweepPolicy = SweepPolicy.keepAll,
    label: Option[String] = None
  ): Result[WorkerSettings] =
    problems(concurrency, pollInterval, unrunnableAfter, drainGrace, exitTimeout, sweepEvery, label) match
      case Nil =>
        Right(
          new WorkerSettings(
            concurrency,
            pollInterval,
            unrunnableAfter,
            drainGrace,
            exitTimeout,
            sweepEvery,
            sweep,
            label
          )
        )
      case found => Left(ValidationError("worker", found))

  /** The defaults. */
  val default: WorkerSettings = apply()
