package org.llm4s.spring

/**
 * The Spring Boot 4 counterpart of `modules/spring-boot-starter/src/test/scala-boot3/org/llm4s/spring/BootLine.scala`:
 * the starter's specs, compiled here against Spring Boot 4, name the health and metrics types Boot 4 moved through
 * this file.
 */
object BootLine {

  /** The Spring Boot major version these specs run on. */
  val Major = 4

  type Health          = org.springframework.boot.health.contributor.Health
  type HealthIndicator = org.springframework.boot.health.contributor.HealthIndicator
  type Status          = org.springframework.boot.health.contributor.Status

  object Status {
    val UP: Status   = org.springframework.boot.health.contributor.Status.UP
    val DOWN: Status = org.springframework.boot.health.contributor.Status.DOWN
  }

  type MetricsEndpoint = org.springframework.boot.micrometer.metrics.actuate.endpoint.MetricsEndpoint

  /** The auto-configurations that create an Actuator application's `MeterRegistry`. */
  val meterRegistryAutoConfigurations: Seq[Class[?]] = Seq(
    classOf[org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration],
    classOf[org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration],
    classOf[
      org.springframework.boot.micrometer.metrics.autoconfigure.`export`.simple.SimpleMetricsExportAutoConfiguration
    ]
  )

  /** The auto-configurations that serve `/actuator/metrics`. */
  val metricsEndpointAutoConfigurations: Seq[Class[?]] = Seq(
    classOf[org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration],
    classOf[org.springframework.boot.micrometer.metrics.autoconfigure.MetricsEndpointAutoConfiguration]
  )
}

/** The starter's health indicator on this Spring Boot line. */
type LlmIndicator = LlmBoot4HealthIndicator

/** The auto-configuration that registers [[LlmIndicator]] on this Spring Boot line. */
type ActuatorAutoConfiguration = LlmBoot4ActuatorAutoConfiguration
