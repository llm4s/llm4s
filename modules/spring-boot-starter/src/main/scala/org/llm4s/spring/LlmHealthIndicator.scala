package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.springframework.boot.actuate.health.{ Health, HealthIndicator }

import java.util.concurrent.ExecutorService

/**
 * The Spring Boot 3 Actuator health indicator, registered by [[LlmActuatorAutoConfiguration]]. By default
 * (`llm4s.health.probe=false`) it makes no provider call: it is UP with `probe=disabled` and the provider and model
 * names, meaning "configured", not "reachable". With `llm4s.health.probe=true` it sends a one-token completion on
 * `executor`, waits at most `llm4s.health.probe-timeout`, and caches the outcome for `llm4s.health.probe-ttl`. A
 * failed or timed-out probe is DOWN with a redacted message.
 *
 * `health()` never throws `InterruptedException`: a thread interrupted while it waits for the probe gets DOWN with
 * `probe=cancelled`, its interrupt flag set again, and the probe call is cancelled. That outcome is not cached: it is
 * about the interrupted thread, not the provider, so the next check probes again.
 *
 * On Spring Boot 4, whose `HealthIndicator` is another type, [[LlmBoot4HealthIndicator]] reports the same.
 */
final class LlmHealthIndicator(
  private val client: JLlmClient,
  private val settings: HealthSettings,
  private val executor: ExecutorService,
  private val nanoClock: () => Long = () => System.nanoTime()
) extends HealthIndicator {

  private val probe = new LlmHealthProbe(client, settings, executor, nanoClock)

  override def health(): Health = {
    val report = probe.report()
    report.details.foldLeft(Health.status(report.status)) { case (b, (k, v)) => b.withDetail(k, v) }.build()
  }
}
