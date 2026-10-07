package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{
  ConditionalOnBean,
  ConditionalOnClass,
  ConditionalOnMissingBean
}
import org.springframework.context.annotation.{ Bean, Configuration }
import org.springframework.core.env.ConfigurableEnvironment

import java.util.concurrent.ExecutorService

@AutoConfiguration(after = Array(classOf[Llm4sAutoConfiguration]))
@Configuration
@ConditionalOnClass(name = Array("org.springframework.boot.actuate.health.HealthIndicator"))
@ConditionalOnBean(Array(classOf[JLlmClient]))
class LlmActuatorAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  def llmHealthIndicator(
    client: JLlmClient,
    properties: Llm4sProperties,
    @Qualifier(Llm4sExecutors.BeanName) executor: ExecutorService,
    environment: ConfigurableEnvironment
  ): LlmHealthIndicator = {
    require(properties.health.probeTimeout.toNanos > 0, "llm4s.health.probe-timeout must be positive")
    require(!properties.health.probeTtl.isNegative, "llm4s.health.probe-ttl must not be negative")
    new LlmHealthIndicator(client, HealthSettings.from(properties, environment), executor)
  }
}
