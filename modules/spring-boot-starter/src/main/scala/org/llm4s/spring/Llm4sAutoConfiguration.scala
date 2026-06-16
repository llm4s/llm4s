package org.llm4s.spring

import org.llm4s.java.{ JLlmClient, Llm4s }
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{ ConditionalOnClass, ConditionalOnMissingBean }
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.{ Bean, Configuration }

@AutoConfiguration
@Configuration
@ConditionalOnClass(name = Array("org.llm4s.java.Llm4s$"))
@EnableConfigurationProperties(Array(classOf[Llm4sProperties]))
class Llm4sAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  def llm4sClient(properties: Llm4sProperties): JLlmClient =
    ProviderConfigParser
      .parse(properties)
      .map(config => Llm4s.createClient(config).get())
      .get()

  @Bean
  @ConditionalOnMissingBean
  def llm4sTemplate(client: JLlmClient): LLM4STemplate =
    new LLM4STemplate(client)
}
