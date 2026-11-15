package org.llm4s.spring

import org.llm4s.javaapi.JLlmClient
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{
  ConditionalOnBean,
  ConditionalOnClass,
  ConditionalOnMissingBean,
  ConditionalOnProperty
}
import org.springframework.context.annotation.{ Bean, Configuration }

/**
 * The `llm4sReactiveTemplate` bean (#2279): the client's replies as Reactor `Flux`es, for Spring WebFlux. Active when
 * Reactor and `llm4s-reactor` are on the classpath, both optional dependencies of the starter. A bean of type
 * [[ReactiveLLM4STemplate]] replaces it.
 */
@AutoConfiguration(after = Array(classOf[Llm4sAutoConfiguration]))
@Configuration
@ConditionalOnClass(name = Array("reactor.core.publisher.Flux", "org.llm4s.javaapi.reactor.Streams"))
@ConditionalOnBean(Array(classOf[JLlmClient]))
@ConditionalOnProperty(prefix = "llm4s", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
class Llm4sReactiveAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  def llm4sReactiveTemplate(client: JLlmClient): ReactiveLLM4STemplate = new ReactiveLLM4STemplate(client)
}

/**
 * The `llm4sSseTemplate` bean (#2279): the client's replies as Spring MVC `SseEmitter`s. Active when Spring MVC is on
 * the classpath, which the starter declares `provided`. A bean of type [[SseLLM4STemplate]] replaces it.
 */
@AutoConfiguration(after = Array(classOf[Llm4sAutoConfiguration]))
@Configuration
@ConditionalOnClass(name = Array("org.springframework.web.servlet.mvc.method.annotation.SseEmitter"))
@ConditionalOnBean(Array(classOf[JLlmClient]))
@ConditionalOnProperty(prefix = "llm4s", name = Array("enabled"), havingValue = "true", matchIfMissing = true)
class Llm4sSseAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  def llm4sSseTemplate(client: JLlmClient): SseLLM4STemplate = new SseLLM4STemplate(client)
}
