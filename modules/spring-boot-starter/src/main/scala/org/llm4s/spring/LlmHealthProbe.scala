package org.llm4s.spring

import org.llm4s.error.CancelledError
import org.llm4s.javaapi.{ ConversationBuilder, JLlmClient, LlmResult }
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.types.Result
import org.springframework.core.env.ConfigurableEnvironment

import java.time.Duration
import java.util.concurrent.{ Callable, ExecutorService, TimeUnit, TimeoutException }
import scala.util.{ Failure, Success, Try }

/** What the indicator reports about and how it probes; the secrets are only used to redact messages. */
final case class HealthSettings(
  provider: String,
  model: String,
  probe: Boolean,
  ttl: Duration,
  timeout: Duration,
  secrets: Seq[String]
) {
  // The API key must never reach a health response, so it is kept out of toString too.
  override def toString: String = s"HealthSettings($provider, $model, probe=$probe, ttl=$ttl, timeout=$timeout)"
}

object HealthSettings {

  /** `from`, once the probe's timeout and cache lifetime are checked; startup fails on a bad one. */
  def checked(p: Llm4sProperties, env: ConfigurableEnvironment): HealthSettings = {
    require(p.health.probeTimeout.toNanos > 0, "llm4s.health.probe-timeout must be positive")
    require(!p.health.probeTtl.isNegative, "llm4s.health.probe-ttl must not be negative")
    from(p, env)
  }

  /**
   * The settings for the provider the properties configure. With `llm4s.providers.*` properties that is the
   * provider they resolve to, and every credential among them is kept out of the messages; if they do not
   * resolve (the client bean reports why) or are absent, it is what the flat properties say.
   */
  def from(p: Llm4sProperties, env: ConfigurableEnvironment): HealthSettings =
    if (SpringProviderSource.usesProvidersBlock(env)) {
      val resolved = SpringProviderSource.resolve(env, p.provider)
      if (resolved.isSuccess) {
        val r = resolved.get()
        HealthSettings(
          r.config.providerId.asString,
          Option(r.config.model).map(_.trim).filter(_.nonEmpty).getOrElse("unknown"),
          p.health.probe,
          p.health.probeTtl,
          p.health.probeTimeout,
          r.secrets
        )
      } else from(p)
    } else from(p)

  def from(p: Llm4sProperties): HealthSettings = {
    def name(s: String) = Option(s).map(_.trim).filter(_.nonEmpty).getOrElse("unknown")
    HealthSettings(
      name(p.provider),
      name(p.model),
      p.health.probe,
      p.health.probeTtl,
      p.health.probeTimeout,
      Option(p.apiKey).map(_.trim).filter(_.nonEmpty).toSeq
    )
  }
}

/**
 * What the health indicator reports, without Spring Boot's types: `status` is a Spring `Status` code (`UP`, `DOWN`) and
 * `details` its details, in order. Spring Boot 3 and 4 each have their own `Health`, so the probe produces this and
 * [[LlmHealthIndicator]] (Boot 3) and [[LlmBoot4HealthIndicator]] (Boot 4) turn it into theirs.
 */
final private[spring] case class HealthReport(status: String, details: Seq[(String, String)])

/**
 * Reports what was actually checked. By default (`llm4s.health.probe=false`) it makes no provider
 * call: it is UP with `probe=disabled` and the provider and model names, meaning "configured", not
 * "reachable". With `llm4s.health.probe=true` it sends a one-token completion on `executor`, waits at
 * most `llm4s.health.probe-timeout`, and caches the outcome for `llm4s.health.probe-ttl`. A failed
 * or timed-out probe is DOWN with a redacted message.
 *
 * `report()` never throws `InterruptedException`: a thread interrupted while it waits for the probe
 * gets DOWN with `probe=cancelled`, its interrupt flag set again, and the probe call is cancelled.
 * That outcome is not cached: it is about the interrupted thread, not the provider, so the next
 * check probes again.
 */
final private[spring] class LlmHealthProbe(
  client: JLlmClient,
  settings: HealthSettings,
  executor: ExecutorService,
  nanoClock: () => Long
) {

  final private case class Probed(atNanos: Long, report: HealthReport)

  private var cached: Option[Probed] = None

  private val tokenLike = "(?i)\\b(?:sk|pk|key|token)[-_][A-Za-z0-9_\\-]{8,}|Bearer\\s+\\S+".r

  private def report(status: String, probe: String, extra: (String, String)*): HealthReport =
    HealthReport(status, Seq("provider" -> settings.provider, "model" -> settings.model, "probe" -> probe) ++ extra)

  def report(): HealthReport =
    if (!settings.probe) report("UP", "disabled")
    else probeCached()

  // Synchronised so concurrent health checks share one probe instead of each calling the provider.
  private def probeCached(): HealthReport = synchronized {
    val now = nanoClock()
    cached.filter(c => now - c.atNanos < settings.ttl.toNanos).map(_.report).getOrElse {
      probeNow() match {
        case Right(h) =>
          cached = Some(Probed(nanoClock(), h))
          h
        // A cancellation is a fact about this caller's thread, not about the provider: it is reported to
        // this caller only, never cached, so the next uninterrupted check probes again.
        case Left(e) => down("cancelled", s"${e.message}: interrupted while waiting for the probe")
      }
    }
  }

  /** `Right` is what the provider did (a failure or a timeout too); `Left` is this thread's own interrupt. */
  private def probeNow(): Result[HealthReport] = {
    val call: Callable[LlmResult[String]] = () =>
      client.complete(ConversationBuilder.create().user("ping").build(), CompletionOptions().withMaxTokens(1))
    Try(executor.submit(call)) match {
      case Failure(t) => Right(down("failed", describe(t)))
      case Success(f) =>
        // `Try` does not catch `InterruptedException`: an interrupt of the thread checking health while it
        // waits (Actuator shutting down, a management pool interrupting its worker) is caught here, so that
        // report() never throws it, and the flag is set again for the caller, as JLlmClient does.
        val awaited: Result[Try[LlmResult[String]]] =
          CancelledError.attempt("health probe")(Right(Try(f.get(settings.timeout.toNanos, TimeUnit.NANOSECONDS))))
        awaited match {
          case Right(Success(r)) if r.isSuccess => Right(report("UP", "ok"))
          case Right(Success(r))                => Right(down("failed", redact(r.getError().getMessage)))
          case Right(Failure(_: TimeoutException)) =>
            f.cancel(true)
            Right(down("timeout", s"no answer within ${settings.timeout.toMillis} ms"))
          // ExecutionException wraps what the call threw; anything else is described as it is.
          case Right(Failure(t)) => Right(down("failed", describe(Option(t.getCause).getOrElse(t))))
          case Left(e) =>
            f.cancel(true)
            Left(e)
        }
    }
  }

  private def down(probe: String, error: String): HealthReport = report("DOWN", probe, "error" -> error)

  private def describe(t: Throwable): String =
    redact(s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")

  private def redact(message: String): String = {
    val withoutKnown = settings.secrets.foldLeft(Option(message).getOrElse(""))((m, s) => m.replace(s, "***"))
    tokenLike.replaceAllIn(withoutKnown, "***").take(200)
  }
}
