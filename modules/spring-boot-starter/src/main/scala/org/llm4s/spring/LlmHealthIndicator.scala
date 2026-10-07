package org.llm4s.spring

import org.llm4s.javaapi.{ ConversationBuilder, JLlmClient, LlmResult }
import org.llm4s.llmconnect.model.CompletionOptions
import org.springframework.boot.actuate.health.{ Health, HealthIndicator }
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
 * Reports what was actually checked. By default (`llm4s.health.probe=false`) it makes no provider
 * call: it is UP with `probe=disabled` and the provider and model names, meaning "configured", not
 * "reachable". With `llm4s.health.probe=true` it sends a one-token completion on `executor`, waits at
 * most `llm4s.health.probe-timeout`, and caches the outcome for `llm4s.health.probe-ttl`. A failed
 * or timed-out probe is DOWN with a redacted message.
 */
final class LlmHealthIndicator(
  private val client: JLlmClient,
  private val settings: HealthSettings,
  private val executor: ExecutorService,
  private val nanoClock: () => Long = () => System.nanoTime()
) extends HealthIndicator {

  final private case class Probed(atNanos: Long, health: Health)

  private var cached: Option[Probed] = None

  private val tokenLike = "(?i)\\b(?:sk|pk|key|token)[-_][A-Za-z0-9_\\-]{8,}|Bearer\\s+\\S+".r

  private def builder(status: String, probe: String): Health.Builder =
    Health
      .status(status)
      .withDetail("provider", settings.provider)
      .withDetail("model", settings.model)
      .withDetail("probe", probe)

  override def health(): Health =
    if (!settings.probe) builder("UP", "disabled").build()
    else probeCached()

  // Synchronised so concurrent health checks share one probe instead of each calling the provider.
  private def probeCached(): Health = synchronized {
    val now = nanoClock()
    cached.filter(c => now - c.atNanos < settings.ttl.toNanos).map(_.health).getOrElse {
      val h = probeNow()
      cached = Some(Probed(nanoClock(), h))
      h
    }
  }

  private def probeNow(): Health = {
    val call: Callable[LlmResult[String]] = () =>
      client.complete(ConversationBuilder.create().user("ping").build(), CompletionOptions().withMaxTokens(1))
    Try(executor.submit(call)) match {
      case Failure(t) => down("failed", describe(t))
      case Success(f) =>
        Try(f.get(settings.timeout.toNanos, TimeUnit.NANOSECONDS)) match {
          case Success(r) if r.isSuccess => builder("UP", "ok").build()
          case Success(r)                => down("failed", redact(r.getError().getMessage))
          case Failure(_: TimeoutException) =>
            f.cancel(true)
            down("timeout", s"no answer within ${settings.timeout.toMillis} ms")
          // ExecutionException wraps what the call threw; anything else is described as it is.
          case Failure(t) => down("failed", describe(Option(t.getCause).getOrElse(t)))
        }
    }
  }

  private def down(probe: String, error: String): Health =
    builder("DOWN", probe).withDetail("error", error).build()

  private def describe(t: Throwable): String =
    redact(s"${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")

  private def redact(message: String): String = {
    val withoutKnown = settings.secrets.foldLeft(Option(message).getOrElse(""))((m, s) => m.replace(s, "***"))
    tokenLike.replaceAllIn(withoutKnown, "***").take(200)
  }
}
