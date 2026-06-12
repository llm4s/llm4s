package org.llm4s.spring

import org.llm4s.error.ConfigurationError
import org.llm4s.java.LlmResult
import org.llm4s.llmconnect.config._

object ProviderConfigParser {

  def parse(properties: Llm4sProperties): LlmResult[ProviderConfig] = {
    val provider = properties.provider.trim.toLowerCase
    val model    = properties.model.trim

    if (provider.isEmpty) {
      return LlmResult.failure(ConfigurationError("llm4s.provider is required", List("llm4s.provider")))
    }
    if (model.isEmpty) {
      return LlmResult.failure(ConfigurationError("llm4s.model is required", List("llm4s.model")))
    }

    provider match {
      case "openai"    => parseOpenAI(properties)
      case "anthropic" => parseAnthropic(properties)
      case "ollama"    => parseOllama(properties)
      case unknown =>
        LlmResult.failure(
          ConfigurationError(
            s"Unknown provider: '$unknown'. Supported: openai, anthropic, ollama",
            List("llm4s.provider")
          )
        )
    }
  }

  private def parseOpenAI(p: Llm4sProperties): LlmResult[ProviderConfig] = {
    if (p.apiKey.trim.isEmpty) {
      return LlmResult.failure(
        ConfigurationError("llm4s.api-key is required for OpenAI", List("llm4s.api-key"))
      )
    }
    val baseUrl = if (p.baseUrl.trim.isEmpty) "https://api.openai.com/v1" else p.baseUrl.trim
    val org     = if (p.organization.trim.isEmpty) None else Some(p.organization.trim)
    LlmResult.success(
      OpenAIConfig(
        apiKey = p.apiKey.trim,
        model = p.model.trim,
        organization = org,
        baseUrl = baseUrl,
        contextWindow = p.contextWindow,
        reserveCompletion = p.reserveCompletion
      )
    )
  }

  private def parseAnthropic(p: Llm4sProperties): LlmResult[ProviderConfig] = {
    if (p.apiKey.trim.isEmpty) {
      return LlmResult.failure(
        ConfigurationError("llm4s.api-key is required for Anthropic", List("llm4s.api-key"))
      )
    }
    val baseUrl = if (p.baseUrl.trim.isEmpty) "https://api.anthropic.com" else p.baseUrl.trim
    LlmResult.success(
      AnthropicConfig(
        apiKey = p.apiKey.trim,
        model = p.model.trim,
        baseUrl = baseUrl,
        contextWindow = p.contextWindow,
        reserveCompletion = p.reserveCompletion
      )
    )
  }

  private def parseOllama(p: Llm4sProperties): LlmResult[ProviderConfig] = {
    val baseUrl = if (p.baseUrl.trim.isEmpty) "http://localhost:11434" else p.baseUrl.trim
    LlmResult.success(
      OllamaConfig(
        model = p.model.trim,
        baseUrl = baseUrl,
        contextWindow = p.contextWindow,
        reserveCompletion = p.reserveCompletion
      )
    )
  }
}
