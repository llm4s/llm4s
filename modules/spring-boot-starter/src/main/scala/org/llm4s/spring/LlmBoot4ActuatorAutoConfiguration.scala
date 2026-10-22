package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{
  ConditionalOnBean,
  ConditionalOnClass,
  ConditionalOnMissingBean,
  ConditionalOnMissingClass,
  ConditionalOnProperty
}
import org.springframework.context.annotation.{ Bean, Configuration }
import org.springframework.core.env.ConfigurableEnvironment

import java.util.concurrent.ExecutorService

/**
 * [[LlmActuatorAutoConfiguration]] for Spring Boot 4: the same `llmHealthIndicator` bean, as an
 * [[LlmBoot4HealthIndicator]], when Spring Boot 4's `spring-boot-health` (which `spring-boot-starter-actuator` brings)
 * is on the classpath and Spring Boot 3's `org.springframework.boot.actuate.health.HealthIndicator` is not. Spring Boot
 * 3 has no `org.springframework.boot.health.contributor.HealthIndicator` and Spring Boot 4 no
 * `org.springframework.boot.actuate.health.HealthIndicator`; the second condition keeps a Boot 3 application that has
 * a stray Boot 4 `spring-boot-health` jar to [[LlmActuatorAutoConfiguration]] alone, so one indicator is registered.
 */
@AutoConfiguration(after = Array(classOf[Llm4sAutoConfiguration]))
@Configuration
@ConditionalOnClass(name = Array("org.springframework.boot.health.contributor.HealthIndicator"))
@ConditionalOnMissingClass(Array("org.springframework.boot.actuate.health.HealthIndicator"))
@ConditionalOnBean(Array(classOf[JLlmClient]))
@ConditionalOnProperty(prefix = "llm4s", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
class LlmBoot4ActuatorAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  def llmHealthIndicator(
    client: JLlmClient,
    properties: Llm4sProperties,
    @Qualifier(Llm4sExecutors.BeanName) executor: ExecutorService,
    environment: ConfigurableEnvironment
  ): LlmBoot4HealthIndicator =
    new LlmBoot4HealthIndicator(client, HealthSettings.checked(properties, environment), executor)
}
