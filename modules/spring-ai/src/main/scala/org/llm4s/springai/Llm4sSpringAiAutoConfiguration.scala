package org.llm4s.springai

import org.llm4s.annotation.Experimental
import org.llm4s.javaapi.JLlmClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.{
  ConditionalOnBean,
  ConditionalOnClass,
  ConditionalOnMissingBean
}
import org.springframework.context.annotation.Bean

/**
 * Exposes the llm4s client as a Spring AI `ChatModel` bean (#2278): when Spring AI is on the classpath and the context
 * has a `JLlmClient` - the one the llm4s Spring Boot starter configures from `llm4s.*`, or the application's own - and
 * no `ChatModel` bean yet. A `ChatClient` built from that model then answers through llm4s. An application that defines
 * its own `ChatModel` keeps it.
 */
@Experimental
@AutoConfiguration(afterName = Array("org.llm4s.spring.Llm4sAutoConfiguration"))
@ConditionalOnClass(name = Array("org.springframework.ai.chat.model.ChatModel"))
class Llm4sSpringAiAutoConfiguration {

  @Bean
  @ConditionalOnBean(Array(classOf[JLlmClient]))
  @ConditionalOnMissingBean(Array(classOf[ChatModel]))
  def llm4sChatModel(client: JLlmClient): Llm4sChatModel = Llm4sChatModel.of(client)
}
