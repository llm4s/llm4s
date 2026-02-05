package org.llm4s.llmconnect

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.provider._
import org.llm4s.metrics.MetricsCollector
import org.llm4s.types.Result

object LLMConnect {

  private def buildClient(config: ProviderConfig, metrics: MetricsCollector): Result[LLMClient] =
    config match {
      case cfg: OpenAIConfig =>
        if (cfg.baseUrl.contains("openrouter.ai"))
          OpenRouterClient(cfg, metrics)
        else OpenAIClient(cfg, metrics)
      case cfg: AzureConfig =>
        OpenAIClient(cfg, metrics)
      case cfg: AnthropicConfig =>
        AnthropicClient(cfg, metrics)
      case cfg: OllamaConfig =>
        OllamaClient(cfg, metrics)
      case cfg: ZaiConfig =>
        ZaiClient(cfg, metrics)
      case cfg: GeminiConfig =>
        GeminiClient(cfg, metrics)
    }

  // Typed-config entry: build client directly from ProviderConfig
  def getClient(
    config: ProviderConfig,
    metrics: MetricsCollector
  ): Result[LLMClient] =
    buildClient(config, metrics)

  // Convenience overload with noop metrics default
  def getClient(config: ProviderConfig): Result[LLMClient] =
    buildClient(config, MetricsCollector.noop)

  // Provider-typed entry with explicit metrics
  def getClient(
    provider: LLMProvider,
    config: ProviderConfig,
    metrics: MetricsCollector
  ): Result[LLMClient] =
    (provider, config) match {
      case (LLMProvider.OpenAI, cfg: OpenAIConfig)       => OpenAIClient(cfg, metrics)
      case (LLMProvider.OpenRouter, cfg: OpenAIConfig)   => OpenRouterClient(cfg, metrics)
      case (LLMProvider.Azure, cfg: AzureConfig)         => OpenAIClient(cfg, metrics)
      case (LLMProvider.Anthropic, cfg: AnthropicConfig) => AnthropicClient(cfg, metrics)
      case (LLMProvider.Ollama, cfg: OllamaConfig)       => OllamaClient(cfg, metrics)
      case (LLMProvider.Zai, cfg: ZaiConfig)             => ZaiClient(cfg, metrics)
      case (LLMProvider.Gemini, cfg: GeminiConfig)       => GeminiClient(cfg, metrics)
      case (prov, wrongCfg) =>
        val cfgType = wrongCfg.getClass.getSimpleName
        val msg     = s"Invalid config type $cfgType for provider $prov"
        Left(ConfigurationError(msg))
    }

  // Convenience overload with noop metrics default
  def getClient(
    provider: LLMProvider,
    config: ProviderConfig
  ): Result[LLMClient] =
    getClient(provider, config, MetricsCollector.noop)
}
