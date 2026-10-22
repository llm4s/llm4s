package org.llm4s.spring

/**
 * The starter's specs run on Spring Boot 3 (this project) and Spring Boot 4 (`springBootStarterBoot4`, whose
 * `boot4/src/test` holds the Boot 4 counterpart of this file). Boot 4 moved the health and metrics types the specs
 * use, so the specs name them through this file and are otherwise the same on both lines.
 */
object BootLine {

  /** The Spring Boot major version these specs run on. */
  val Major = 3

  type Health          = org.springframework.boot.actuate.health.Health
  type HealthIndicator = org.springframework.boot.actuate.health.HealthIndicator
  type Status          = org.springframework.boot.actuate.health.Status

  object Status {
    val UP: Status   = org.springframework.boot.actuate.health.Status.UP
    val DOWN: Status = org.springframework.boot.actuate.health.Status.DOWN
  }

  type MetricsEndpoint = org.springframework.boot.actuate.metrics.MetricsEndpoint

  /** The auto-configurations that create an Actuator application's `MeterRegistry`. */
  val meterRegistryAutoConfigurations: Seq[Class[?]] = Seq(
    classOf[org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration],
    classOf[org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration],
    classOf[org.springframework.boot.actuate.autoconfigure.metrics.`export`.simple.SimpleMetricsExportAutoConfiguration]
  )

  /** The auto-configurations that serve `/actuator/metrics`. */
  val metricsEndpointAutoConfigurations: Seq[Class[?]] = Seq(
    classOf[org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration],
    classOf[org.springframework.boot.actuate.autoconfigure.metrics.MetricsEndpointAutoConfiguration]
  )
}

/** The starter's health indicator on this Spring Boot line. */
type LlmIndicator = LlmHealthIndicator

/** The auto-configuration that registers [[LlmIndicator]] on this Spring Boot line. */
type ActuatorAutoConfiguration = LlmActuatorAutoConfiguration
