package org.llm4s.llmconnect.config

import org.slf4j.LoggerFactory
import org.llm4s.util.Redaction

/**
 * Identifies a specific LLM provider, model, and connection details.
 *
 * Each subtype carries the credentials, endpoint URL, and context-window
 * metadata needed to construct an [[org.llm4s.llmconnect.LLMClient]] via
 * [[org.llm4s.llmconnect.LLMConnect]]. Instances are normally obtained from
 * [[org.llm4s.config.Llm4sConfig.provider]], which reads standard environment
 * variables (`LLM_MODEL`, `OPENAI_API_KEY`, etc.).
 *
 * Prefer each subtype's `fromValues` factory over its primary constructor:
 * `fromValues` resolves `contextWindow` and `reserveCompletion` automatically
 * from the model name, so you only need to supply credentials and endpoint.
 */
sealed trait ProviderConfig {

  /** Model identifier forwarded verbatim to the provider API (e.g. `"gpt-4o"`, `"claude-sonnet-4-5-latest"`). */
  def model: String

  /** Maximum token capacity of the model across both prompt and completion combined. */
  def contextWindow: Int

  /**
   * Tokens reserved for the model's completion response.
   *
   * Context-compression logic caps the prompt history at
   * `contextWindow - reserveCompletion`, ensuring the model always has at
   * least this many tokens available to generate a reply.
   */
  def reserveCompletion: Int
}

/**
 * Configuration for the OpenAI API and providers that implement the
 * OpenAI-compatible REST interface.
 *
 * `baseUrl` governs which backend is contacted: `"https://api.openai.com/v1"`
 * reaches OpenAI directly, while a URL containing `"openrouter.ai"` causes
 * [[org.llm4s.llmconnect.LLMConnect]] to route to OpenRouter. Azure OpenAI
 * uses [[AzureConfig]], not this class.
 *
 * Prefer [[OpenAIConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` from the model name automatically.
 *
 * @param apiKey        OpenAI API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"gpt-4o"`.
 * @param organization  Optional OpenAI organisation ID.
 * @param baseUrl       API base URL; determines provider routing in
 *                      [[org.llm4s.llmconnect.LLMConnect]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class OpenAIConfig(
  apiKey: String,
  model: String,
  organization: Option[String],
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"OpenAIConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, organization=$organization, baseUrl=$baseUrl, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"
}

object OpenAIConfig {
  private val standardReserve = 4096

  private def openAIFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gpt-4o")        => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo")   => (128000, standardReserve)
      case name if name.contains("gpt-4")         => (8192, standardReserve)
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      case name if name.contains("o1-")           => (128000, standardReserve)
      case _                                      => (8192, standardReserve)
    }

  /**
   * Constructs an [[OpenAIConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * The resolver first consults a bundled model-metadata catalogue; if the
   * model is not listed there it falls back to name-pattern matching before
   * defaulting to 8192 tokens. Prefer this factory over the primary
   * constructor so that new models receive correct context-window values
   * without manual lookup.
   *
   * @param modelName    Model identifier, e.g. `"gpt-4o"`.
   * @param apiKey       OpenAI API key; must be non-empty.
   * @param organization Optional OpenAI organisation ID.
   * @param baseUrl      API base URL; must be non-empty. Pass a URL containing
   *                     `"openrouter.ai"` to route through OpenRouter.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    organization: Option[String],
    baseUrl: String
  ): OpenAIConfig = {
    require(apiKey.trim.nonEmpty, "OpenAI apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "OpenAI baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("openai"),
      modelName = modelName,
      defaultContextWindow = 8192,
      defaultReserve = standardReserve,
      fallbackResolver = openAIFallback
    )
    OpenAIConfig(
      apiKey = apiKey,
      model = modelName,
      organization = organization,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

/**
 * Configuration for Azure OpenAI deployments.
 *
 * Although Azure exposes an OpenAI-compatible API, it uses a different URL
 * structure (per-deployment endpoint) and requires an `apiVersion` query
 * parameter. [[org.llm4s.llmconnect.LLMConnect]] constructs an
 * [[org.llm4s.llmconnect.provider.OpenAIClient]] internally; this config
 * carries the Azure-specific fields that [[OpenAIConfig]] does not have.
 *
 * Prefer [[AzureConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` automatically.
 *
 * @param endpoint      Azure OpenAI deployment endpoint URL, e.g.
 *                      `"https://my-resource.openai.azure.com/openai/deployments/my-deploy"`.
 * @param apiKey        Azure API key; redacted in `toString`.
 * @param model         Deployment name used as the model identifier.
 * @param apiVersion    Azure OpenAI API version string, e.g. `"2025-01-01-preview"`.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class AzureConfig(
  endpoint: String,
  apiKey: String,
  model: String,
  apiVersion: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"AzureConfig(endpoint=$endpoint, apiKey=${Redaction.secret(apiKey)}, model=$model, apiVersion=$apiVersion, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"
}

object AzureConfig {
  private val standardReserve = 4096

  private def azureFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gpt-4o")        => (128000, standardReserve)
      case name if name.contains("gpt-4-turbo")   => (128000, standardReserve)
      case name if name.contains("gpt-4")         => (8192, standardReserve)
      case name if name.contains("gpt-3.5-turbo") => (16384, standardReserve)
      case name if name.contains("o1-")           => (128000, standardReserve)
      case _                                      => (8192, standardReserve)
    }

  /**
   * Constructs an [[AzureConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * The resolver checks Azure-specific and OpenAI model catalogues in order
   * before falling back to name-pattern matching.
   *
   * @param modelName  Deployment name used as the model identifier.
   * @param endpoint   Azure deployment endpoint URL; must be non-empty.
   * @param apiKey     Azure API key; must be non-empty.
   * @param apiVersion Azure OpenAI API version string.
   */
  def fromValues(
    modelName: String,
    endpoint: String,
    apiKey: String,
    apiVersion: String
  ): AzureConfig = {
    require(endpoint.trim.nonEmpty, "Azure endpoint must be non-empty")
    require(apiKey.trim.nonEmpty, "Azure apiKey must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("azure", "openai"),
      modelName = modelName,
      defaultContextWindow = 8192,
      defaultReserve = standardReserve,
      fallbackResolver = azureFallback,
      logPrefix = "Azure "
    )
    AzureConfig(
      endpoint = endpoint,
      apiKey = apiKey,
      model = modelName,
      apiVersion = apiVersion,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

/**
 * Configuration for the Anthropic Claude API.
 *
 * Prefer [[AnthropicConfig.fromValues]] over the primary constructor; it
 * resolves `contextWindow` and `reserveCompletion` automatically from the
 * model name.
 *
 * @param apiKey        Anthropic API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"claude-sonnet-4-5-latest"`.
 * @param baseUrl       API base URL, defaulting to `"https://api.anthropic.com"`.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class AnthropicConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"AnthropicConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object AnthropicConfig {
  private val standardReserve = 4096

  private def anthropicFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("claude-3")       => (200000, standardReserve)
      case name if name.contains("claude-3.5")     => (200000, standardReserve)
      case name if name.contains("claude-instant") => (100000, standardReserve)
      case _                                       => (200000, standardReserve)
    }

  /**
   * Constructs an [[AnthropicConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"claude-sonnet-4-5-latest"`.
   * @param apiKey    Anthropic API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): AnthropicConfig = {
    require(apiKey.trim.nonEmpty, "Anthropic apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Anthropic baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("anthropic"),
      modelName = modelName,
      defaultContextWindow = 200000,
      defaultReserve = standardReserve,
      fallbackResolver = anthropicFallback
    )
    AnthropicConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

/**
 * Configuration for a locally-running Ollama instance.
 *
 * Ollama requires no API key — authentication is handled at the network
 * level by controlling access to the Ollama endpoint. Prefer
 * [[OllamaConfig.fromValues]] over the primary constructor.
 *
 * @param model         Model identifier as registered in Ollama, e.g. `"llama3"`.
 * @param baseUrl       Ollama server URL, e.g. `"http://localhost:11434"`.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class OllamaConfig(
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig

object OllamaConfig {
  private val standardReserve = 4096

  private def ollamaFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("llama2")    => (4096, standardReserve)
      case name if name.contains("llama3")    => (8192, standardReserve)
      case name if name.contains("codellama") => (16384, standardReserve)
      case name if name.contains("mistral")   => (32768, standardReserve)
      case _                                  => (8192, standardReserve)
    }

  /**
   * Constructs an [[OllamaConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier as registered in Ollama.
   * @param baseUrl   Ollama server URL; must be non-empty.
   */
  def fromValues(
    modelName: String,
    baseUrl: String
  ): OllamaConfig = {
    require(baseUrl.trim.nonEmpty, "Ollama baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("ollama"),
      modelName = modelName,
      defaultContextWindow = 8192,
      defaultReserve = standardReserve,
      fallbackResolver = ollamaFallback
    )
    OllamaConfig(
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

/**
 * Configuration for the Z.ai GLM API.
 *
 * Prefer [[ZaiConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name.
 *
 * @param apiKey        Z.ai API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"GLM-4.7"`.
 * @param baseUrl       API base URL; defaults to [[ZaiConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class ZaiConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"ZaiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object ZaiConfig {
  private val standardReserve = 4096

  val DEFAULT_BASE_URL: String = "https://api.z.ai/api/paas/v4"

  private def zaiFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("GLM-4.7") => (128000, standardReserve)
      case name if name.contains("GLM-4.5") => (32000, standardReserve)
      case _                                => (128000, standardReserve)
    }

  /**
   * Constructs a [[ZaiConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"GLM-4.7"`.
   * @param apiKey    Z.ai API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty. Defaults to
   *                  [[ZaiConfig.DEFAULT_BASE_URL]] when loaded via
   *                  [[org.llm4s.config.Llm4sConfig]].
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): ZaiConfig = {
    require(apiKey.trim.nonEmpty, "Zai apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Zai baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("zai"),
      modelName = modelName,
      defaultContextWindow = 128000,
      defaultReserve = standardReserve,
      fallbackResolver = zaiFallback
    )
    ZaiConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

/**
 * Configuration for the Google Gemini API.
 *
 * Prefer [[GeminiConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name.
 *
 * @param apiKey        Google API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"gemini-2.0-flash"`.
 * @param baseUrl       API base URL.
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class GeminiConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"GeminiConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object GeminiConfig {
  private val standardReserve = 8192

  private def geminiFallback(modelName: String): (Int, Int) =
    modelName match {
      case name if name.contains("gemini-2")     => (1048576, standardReserve)
      case name if name.contains("gemini-1.5")   => (1048576, standardReserve)
      case name if name.contains("gemini-1.0")   => (32768, standardReserve)
      case name if name.contains("gemini-pro")   => (1048576, standardReserve)
      case name if name.contains("gemini-flash") => (1048576, standardReserve)
      case _                                     => (1048576, standardReserve)
    }

  /**
   * Constructs a [[GeminiConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"gemini-2.0-flash"`.
   * @param apiKey    Google API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty.
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): GeminiConfig = {
    require(apiKey.trim.nonEmpty, "Gemini apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Gemini baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("gemini", "google"),
      modelName = modelName,
      defaultContextWindow = 1048576,
      defaultReserve = standardReserve,
      fallbackResolver = geminiFallback
    )
    GeminiConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

/**
 * Configuration for the DeepSeek API.
 *
 * Prefer [[DeepSeekConfig.fromValues]] over the primary constructor; it
 * resolves `contextWindow` and `reserveCompletion` automatically, and logs a
 * warning for unknown or legacy model names.
 *
 * @param apiKey        DeepSeek API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"deepseek-chat"` or `"deepseek-reasoner"`.
 * @param baseUrl       API base URL; defaults to [[DeepSeekConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class DeepSeekConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"DeepSeekConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object DeepSeekConfig {
  private val logger          = LoggerFactory.getLogger(getClass)
  private val standardReserve = 8192

  val DEFAULT_BASE_URL: String = "https://api.deepseek.com"

  private def deepSeekFallback(modelName: String): (Int, Int) =
    // Explicit allowlist based on official DeepSeek API (as of Feb 2026)
    // Source: https://api-docs.deepseek.com/quick_start/pricing
    modelName.toLowerCase match {
      case "deepseek-chat" | "deepseek/deepseek-chat" | "deepseek-reasoner" | "deepseek/deepseek-reasoner" =>
        (128000, standardReserve)
      case "deepseek-chat-r1" | "deepseek/deepseek-chat-r1" | "deepseek-r1-distill" | "deepseek/deepseek-r1-distill" |
          "deepseek-coder" | "deepseek/deepseek-coder" | "deepseek-v3" | "deepseek/deepseek-v3" =>
        logger.warn(s"Legacy/variant model $modelName - may not be available via official API")
        (128000, standardReserve)
      case _ =>
        logger.warn(s"Unknown DeepSeek model: $modelName, using conservative 128K fallback")
        (128000, standardReserve)
    }

  /**
   * Constructs a [[DeepSeekConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * Unknown or legacy model names produce a warning log but still succeed,
   * falling back to a conservative 128K context window.
   *
   * @param modelName Model identifier; see DeepSeek API docs for the current
   *                  allowlist (`"deepseek-chat"`, `"deepseek-reasoner"`).
   * @param apiKey    DeepSeek API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty. Defaults to
   *                  [[DeepSeekConfig.DEFAULT_BASE_URL]] when loaded via
   *                  [[org.llm4s.config.Llm4sConfig]].
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): DeepSeekConfig = {
    require(apiKey.trim.nonEmpty, "DeepSeek apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "DeepSeek baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("deepseek"),
      modelName = modelName,
      defaultContextWindow = 64000,
      defaultReserve = standardReserve,
      fallbackResolver = deepSeekFallback
    )
    DeepSeekConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}

/**
 * Configuration for the Cohere API.
 *
 * Prefer [[CohereConfig.fromValues]] over the primary constructor; it resolves
 * `contextWindow` and `reserveCompletion` automatically from the model name.
 *
 * @param apiKey        Cohere API key; redacted in `toString`.
 * @param model         Model identifier, e.g. `"command-r-plus"`.
 * @param baseUrl       API base URL; defaults to [[CohereConfig.DEFAULT_BASE_URL]].
 * @param contextWindow Model's total token capacity (prompt + completion combined).
 * @param reserveCompletion Tokens held back from prompt history for the completion.
 */
case class CohereConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig {
  override def toString: String =
    s"CohereConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"
}

object CohereConfig {
  private val DefaultContextWindow     = 128000
  private val DefaultReserveCompletion = 4096

  val DEFAULT_BASE_URL: String = "https://api.cohere.com"

  private val cohereFallback: String => (Int, Int) = _ => (DefaultContextWindow, DefaultReserveCompletion)

  /**
   * Constructs a [[CohereConfig]], resolving `contextWindow` and
   * `reserveCompletion` from the model name automatically.
   *
   * @param modelName Model identifier, e.g. `"command-r-plus"`.
   * @param apiKey    Cohere API key; must be non-empty.
   * @param baseUrl   API base URL; must be non-empty. Defaults to
   *                  [[CohereConfig.DEFAULT_BASE_URL]] when loaded via
   *                  [[org.llm4s.config.Llm4sConfig]].
   */
  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  ): CohereConfig = {
    require(apiKey.trim.nonEmpty, "Cohere apiKey must be non-empty")
    require(baseUrl.trim.nonEmpty, "Cohere baseUrl must be non-empty")
    val (cw, rc) = ContextWindowResolver.resolve(
      lookupProviders = Seq("cohere"),
      modelName = modelName,
      defaultContextWindow = DefaultContextWindow,
      defaultReserve = DefaultReserveCompletion,
      fallbackResolver = cohereFallback
    )
    CohereConfig(
      apiKey = apiKey,
      model = modelName,
      baseUrl = baseUrl,
      contextWindow = cw,
      reserveCompletion = rc
    )
  }
}
