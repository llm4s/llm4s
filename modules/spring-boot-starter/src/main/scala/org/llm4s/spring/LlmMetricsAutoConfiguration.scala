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
 */
@AutoConfiguration(
  before = Array(classOf[Llm4sAutoConfiguration]),
  afterName = Array(
    "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
    "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"
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
