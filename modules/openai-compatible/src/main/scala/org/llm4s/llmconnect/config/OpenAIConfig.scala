package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Configuration for the OpenAI API and providers that implement the
 * OpenAI-compatible REST interface.
 *
 * `baseUrl` governs which backend is contacted: `"https://api.openai.com/v1"`
 * reaches OpenAI directly, while a URL containing `"openrouter.ai"` causes
 * [[org.llm4s.llmconnect.LLMConnect]] to route to OpenRouter. Azure OpenAI
 * uses `AzureConfig`, not this class.
 *
 * This config lives in `llm4s-openai-compatible`, with OpenRouter, and
 * `llm4s-openai` - whose OpenAI and Requesty providers build it - depends on
 * that module for it ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 * Its package is unchanged from when it was in `llm4s-core`.
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
