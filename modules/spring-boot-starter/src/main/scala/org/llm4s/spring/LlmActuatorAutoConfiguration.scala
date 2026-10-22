package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{
  ConditionalOnBean,
  ConditionalOnClass,
  ConditionalOnMissingBean,
  ConditionalOnProperty
}
import org.springframework.context.annotation.{ Bean, Configuration }
import org.springframework.core.env.ConfigurableEnvironment

import java.util.concurrent.ExecutorService

@AutoConfiguration(after = Array(classOf[Llm4sAutoConfiguration]))
@Configuration
// Spring Boot 3's HealthIndicator; on Spring Boot 4, LlmBoot4ActuatorAutoConfiguration registers the indicator.
@ConditionalOnClass(name = Array("org.springframework.boot.actuate.health.HealthIndicator"))
@ConditionalOnBean(Array(classOf[JLlmClient]))
// The same switch as Llm4sAutoConfiguration: llm4s.enabled=false turns the whole starter off. Without
// this, an application supplying its own JLlmClient with the starter disabled failed startup, because
// this configuration stayed eligible while Llm4sProperties and the executor bean were gone (#1592).
@ConditionalOnProperty(prefix = "llm4s", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
class LlmActuatorAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  def llmHealthIndicator(
    client: JLlmClient,
    properties: Llm4sProperties,
    @Qualifier(Llm4sExecutors.BeanName) executor: ExecutorService,
    environment: ConfigurableEnvironment
  ): LlmHealthIndicator =
    new LlmHealthIndicator(client, HealthSettings.checked(properties, environment), executor)
}
