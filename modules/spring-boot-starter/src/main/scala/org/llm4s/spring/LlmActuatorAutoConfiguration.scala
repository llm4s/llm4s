package org.llm4s.spring

import org.llm4s.java.JLlmClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{ ConditionalOnClass, ConditionalOnMissingBean }
import org.springframework.context.annotation.{ Bean, Configuration }

@AutoConfiguration(after = Array(classOf[Llm4sAutoConfiguration]))
@Configuration
@ConditionalOnClass(name = Array("org.springframework.boot.actuate.health.HealthIndicator"))
class LlmActuatorAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  def llmHealthIndicator(client: JLlmClient): LlmHealthIndicator =
    new LlmHealthIndicator(client)
}
