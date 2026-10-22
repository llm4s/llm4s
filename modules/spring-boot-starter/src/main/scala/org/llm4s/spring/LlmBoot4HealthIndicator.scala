package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.springframework.boot.health.contributor.{ Health, HealthIndicator }

import java.util.concurrent.ExecutorService

/**
 * The Spring Boot 4 health indicator, registered by [[LlmBoot4ActuatorAutoConfiguration]]. Spring Boot 4 moved
 * `HealthIndicator` and `Health` from `org.springframework.boot.actuate.health` (spring-boot-actuator) to
 * `org.springframework.boot.health.contributor` (spring-boot-health), so the Boot 3 [[LlmHealthIndicator]] cannot be
 * one; this reports exactly what it does, from the same probe, settings and cache rules.
 */
final class LlmBoot4HealthIndicator(
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
