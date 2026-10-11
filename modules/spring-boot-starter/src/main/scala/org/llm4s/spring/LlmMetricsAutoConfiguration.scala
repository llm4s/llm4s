package org.llm4s.spring

import io.micrometer.core.instrument.MeterRegistry
import org.llm4s.metrics.{ MetricsCollector, MicrometerMetrics }
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{
  ConditionalOnBean,
  ConditionalOnClass,
  ConditionalOnMissingBean,
  ConditionalOnProperty
}
import org.springframework.context.annotation.{ Bean, Configuration }

/**
 * Records llm4s request, token, cost and error metrics in the application's Micrometer `MeterRegistry`, so they
 * appear on `/actuator/metrics` (and in whatever the registry exports to) with no wiring.
 *
 * Active when `llm4s-observability-micrometer` is on the classpath and the context has a `MeterRegistry` bean, as
 * Spring Boot Actuator provides. The starter declares that module `provided`, like Actuator itself, so an application
 * opts in by adding the dependency. A `MetricsCollector` bean of the application's own replaces this one; the client
 * bean reports to every `MetricsCollector` bean there is.
 *
 * It runs after the auto-configurations that create the `MeterRegistry`, so `@ConditionalOnBean` sees it. They are
 * named as strings, because Actuator is optional, and Spring Boot 4 moved them from
 * `org.springframework.boot.actuate.autoconfigure.metrics` (spring-boot-actuator-autoconfigure) to
 * `org.springframework.boot.micrometer.metrics.autoconfigure` (spring-boot-micrometer-metrics), so both names are
 * listed; Spring ignores a name that is not on the classpath. `LlmMetricsAutoConfigurationOrderingSpec` fails if a
 * Boot 3 name stops resolving on the Boot version the starter is tested against, or if the Boot 4 names are dropped.
 */
@AutoConfiguration(
  before = Array(classOf[Llm4sAutoConfiguration]),
  afterName = Array(
    // Spring Boot 3.x
    "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration",
    // Spring Boot 4.x
    "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
    "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
    "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
  )
)
@Configuration
@ConditionalOnClass(name = Array("io.micrometer.core.instrument.MeterRegistry", "org.llm4s.metrics.MicrometerMetrics"))
@ConditionalOnBean(`type` = Array("io.micrometer.core.instrument.MeterRegistry"))
@ConditionalOnProperty(prefix = "llm4s", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
class LlmMetricsAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(Array(classOf[MetricsCollector]))
  def llm4sMetricsCollector(registry: MeterRegistry): MetricsCollector = new MicrometerMetrics(registry)
}
