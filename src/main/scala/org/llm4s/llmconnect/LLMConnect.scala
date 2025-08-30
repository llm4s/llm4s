package org.llm4s.llmconnect

import org.llm4s.config.{ ConfigKeys, ConfigReader }
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider._
import org.llm4s.types.Result
import ConfigKeys.OPENAI_BASE_URL
import ConfigKeys.LLM_MODEL
import org.llm4s.config.DefaultConfig.DEFAULT_OPENAI_BASE_URL
object LLMConnect {

  def getClient(reader: ConfigReader): LLMClient = {
    val model = reader
      .get(LLM_MODEL)
      .getOrElse(
        throw new IllegalArgumentException(
          s"Please set the `$LLM_MODEL` environment variable to specify the default model"
        )
      )

    // Always use OpenRouterClient if OPENAI_BASE_URL contains 'openrouter.ai'
    val openaiBaseUrl = reader.get(OPENAI_BASE_URL).getOrElse(DEFAULT_OPENAI_BASE_URL)
    if (openaiBaseUrl.contains("openrouter.ai")) {
      val modelName = if (model.startsWith("openai/")) model.replace("openai/", "") else model
      val config    = OpenAIConfig.from(modelName, reader)
      new OpenRouterClient(config)
    } else if (model.startsWith("openai/")) {
      val modelName = model.replace("openai/", "")
      val config    = OpenAIConfig.from(modelName, reader)
      new OpenAIClient(config)
    } else if (model.startsWith("openrouter/")) {
      val modelName = model.replace("openrouter/", "")
      val config    = OpenAIConfig.from(modelName, reader)
      new OpenRouterClient(config)
    } else if (model.startsWith("azure/")) {
      val modelName = model.replace("azure/", "")
      val config    = AzureConfig.from(modelName, reader)
      new OpenAIClient(config)
    } else if (model.startsWith("anthropic/")) {
      val modelName = model.replace("anthropic/", "")
      val config    = AnthropicConfig.from(modelName, reader)
      new AnthropicClient(config)
    } else if (model.startsWith("ollama/")) {
      val modelName = model.replace("ollama/", "")
      val config    = OllamaConfig.from(modelName, reader)
      new OllamaClient(config)
    } else {
      val msg =
        s"Model $model is not supported. Supported formats are: " +
          "'openai/...', 'openrouter/...', 'azure/...', 'anthropic/...', or 'ollama/...'."
      throw new IllegalArgumentException(msg)
    }
  }

  /**
   * Get an LLMClient with explicit provider and configuration
   */
  def getClient(provider: LLMProvider, config: ProviderConfig): LLMClient =
    (provider, config) match {
      case (LLMProvider.OpenAI, cfg: OpenAIConfig)       => new OpenAIClient(cfg)
      case (LLMProvider.OpenRouter, cfg: OpenAIConfig)   => new OpenRouterClient(cfg)
      case (LLMProvider.Azure, cfg: AzureConfig)         => new OpenAIClient(cfg)
      case (LLMProvider.Anthropic, cfg: AnthropicConfig) => new AnthropicClient(cfg)
      case (LLMProvider.Ollama, cfg: OllamaConfig)       => new OllamaClient(cfg)
      case (prov, wrongCfg) =>
        val cfgType = wrongCfg.getClass.getSimpleName
        val msg     = s"Invalid config type $cfgType for provider $prov"
        throw new IllegalArgumentException(msg)
    }

  /**
   * Convenience method for quick completion
   */
  def complete(
    messages: Seq[Message],
    options: CompletionOptions = CompletionOptions()
  )(reader: ConfigReader): Result[Completion] = {
    val conversation = Conversation(messages)
    getClient(reader).complete(conversation, options)
  }
}
