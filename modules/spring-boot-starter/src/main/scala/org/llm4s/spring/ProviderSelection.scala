package org.llm4s.spring

import org.llm4s.javaapi.LlmResult
import org.llm4s.llmconnect.config.ProviderConfig
import org.springframework.core.env.ConfigurableEnvironment

/**
 * Chooses how the provider is configured (#1467).
 *
 * With any `llm4s.providers.*` property present, the provider is resolved through llm4s's own configuration
 * and the provider SPI ([[SpringProviderSource]]), which serves every provider on the classpath. Without one,
 * the flat `llm4s.provider`, `llm4s.model`, `llm4s.api-key` ... properties of the starter's first release
 * are read exactly as before by [[ProviderConfigParser]], for openai, anthropic and ollama.
 */
private[spring] object ProviderSelection {

  def resolve(properties: Llm4sProperties, env: ConfigurableEnvironment): LlmResult[ProviderConfig] =
    if (SpringProviderSource.usesProvidersBlock(env))
      SpringProviderSource.resolve(env, properties.provider).map(_.config)
    else ProviderConfigParser.parse(properties)
}
