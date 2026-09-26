package org.llm4s.llmconnect.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Identifies a specific LLM provider, model, and connection details.
 *
 * Each subtype carries the credentials, endpoint URL, and context-window
 * metadata needed to construct an [[org.llm4s.llmconnect.LLMClient]] via
 * [[org.llm4s.llmconnect.LLMConnect]]. Instances are normally obtained from
 * [[org.llm4s.config.Llm4sConfig.defaultProvider]] or
 * [[org.llm4s.config.Llm4sConfig.provider(name)*]], which resolve configured
 * named providers under `llm4s.providers`.
 *
 * Prefer each subtype's `fromValues` factory over its primary constructor:
 * `fromValues` resolves `contextWindow` and `reserveCompletion` automatically
 * from the model name, so you only need to supply credentials and endpoint.
 * It returns a `Result`: a blank credential or endpoint is a
 * [[org.llm4s.error.ConfigurationError]] naming the provider and the field,
 * never a thrown exception.
 *
 * This trait is deliberately '''not''' `sealed`. In Scala 3 `sealed` confines
 * subtypes to the same source file, which would keep every provider's config in
 * this one file forever and make a provider supplied by another module
 * impossible. Implementations are therefore expected from outside `llm4s-core`,
 * and consumers must not assume the set of subtypes is closed: describe a config
 * through [[providerId]], [[endpointUrl]] and [[withModel]] rather than by
 * pattern-matching on its runtime type.
 */
trait ProviderConfig {

  /** Canonical id of the provider this config addresses, e.g. `ProviderId("openai")`. */
  def providerId: ProviderId

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

  /**
   * The endpoint this config will contact, when it is known statically.
   *
   * Used by policy checks and diagnostics that need to know where traffic will
   * go without knowing which provider it belongs to. `None` means the config
   * carries no single URL — not that it makes no network calls.
   */
  def endpointUrl: Option[String]

  /** The same provider and credentials, pointed at a different model. */
  def withModel(model: String): ProviderConfig
}

object ProviderConfig {

  /**
   * The check behind every `fromValues` factory: `value` must not be blank.
   *
   * A blank credential or endpoint is a configuration mistake, so it is reported
   * as a [[org.llm4s.error.ConfigurationError]] naming the provider and the
   * field, not thrown.
   *
   * @param provider the provider's display name, e.g. `"OpenAI"`.
   * @param field    the parameter name, e.g. `"apiKey"`; also reported as the missing key.
   */
  private[llm4s] def nonEmpty(provider: String, field: String, value: String): Result[Unit] =
    Either.cond(
      value.trim.nonEmpty,
      (),
      ConfigurationError(s"$provider $field must be non-empty", List(field))
    )
}

/**
 * Configuration for the OpenAI API and providers that implement the
 * OpenAI-compatible REST interface.
 *
 * `baseUrl` governs which backend is contacted: `"https://api.openai.com/v1"`
 * reaches OpenAI directly, while a URL containing `"openrouter.ai"` causes
 * [[org.llm4s.llmconnect.LLMConnect]] to route to OpenRouter. Azure OpenAI
 * uses `AzureConfig`, not this class.
 *
 * The OpenAI, Requesty and Azure clients live in `llm4s-openai`
 * ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]), but this config stays
 * in `llm4s-core` because OpenRouter, which is still in core, shares it.
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
) extends ProviderConfig:
  /**
   * `openai`, or `openrouter` when `baseUrl` points at OpenRouter.
   *
   * OpenRouter reuses this config but has its own client, and the base URL is
   * the only thing distinguishing the two. Deriving the id here keeps that
   * knowledge with the config rather than in a special case inside
   * [[org.llm4s.llmconnect.LLMConnect]], which is how routing worked before the
   * provider registry (#1131).
   */
  override def providerId: ProviderId =
    if baseUrl.contains("openrouter.ai") then ProviderId("openrouter") else ProviderId("openai")

  override def endpointUrl: Option[String]            = Some(baseUrl)
  override def withModel(model: String): OpenAIConfig = copy(model = model)
  override def toString: String =
    s"OpenAIConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, organization=$organization, baseUrl=$baseUrl, " +
      s"contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"

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
  )(using resolver: ContextWindowResolver): Result[OpenAIConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("OpenAI", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("OpenAI", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
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
) extends ProviderConfig:
  override val providerId: ProviderId                 = ProviderId("cohere")
  override def endpointUrl: Option[String]            = Some(baseUrl)
  override def withModel(model: String): CohereConfig = copy(model = model)
  override def toString: String =
    s"CohereConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

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
  )(using resolver: ContextWindowResolver): Result[CohereConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Cohere", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("Cohere", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
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

case class MistralConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId                  = ProviderId("mistral")
  override def endpointUrl: Option[String]             = Some(baseUrl)
  override def withModel(model: String): MistralConfig = copy(model = model)
  override def toString: String =
    s"MistralConfig(apiKey=${Redaction.secret(apiKey)}, model=$model, baseUrl=$baseUrl, contextWindow=$contextWindow, " +
      s"reserveCompletion=$reserveCompletion)"

object MistralConfig:
  val DEFAULT_BASE_URL: String = "https://api.mistral.ai"

  private val DefaultContextWindow     = 128000
  private val DefaultReserveCompletion = 4096

  private val mistralFallback: String => (Int, Int) =
    _ => (DefaultContextWindow, DefaultReserveCompletion)

  def fromValues(
    modelName: String,
    apiKey: String,
    baseUrl: String
  )(using resolver: ContextWindowResolver): Result[MistralConfig] =
    for {
      _ <- ProviderConfig.nonEmpty("Mistral", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("Mistral", "baseUrl", baseUrl)
    } yield {
      val (cw, rc) = resolver.resolve(
        lookupProviders = Seq("mistral"),
        modelName = modelName,
        defaultContextWindow = DefaultContextWindow,
        defaultReserve = DefaultReserveCompletion,
        fallbackResolver = mistralFallback
      )
      MistralConfig(
        apiKey = apiKey,
        model = modelName,
        baseUrl = baseUrl,
        contextWindow = cw,
        reserveCompletion = rc
      )
    }
