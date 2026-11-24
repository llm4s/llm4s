package org.llm4s.llmconnect

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.spi.{ ProviderBuildContext, ProviderRegistry }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Constructs an [[LLMClient]] from provider configuration.
 *
 * Which client is built is decided by the [[org.llm4s.llmconnect.spi.ProviderRegistry]]:
 * the config names a provider through
 * [[org.llm4s.llmconnect.config.ProviderConfig.providerId]], and that provider's
 * [[org.llm4s.llmconnect.spi.ProviderDescriptor]] builds its own client. `LLMConnect`
 * itself knows no provider names, and `llm4s-core` ships no provider: every one - the
 * project's own provider modules and a third party's alike - is reached the same way.
 *
 * Pass a different registry with `using` to resolve against a custom set of providers;
 * with none in scope, [[org.llm4s.llmconnect.spi.ProviderRegistry.default]] is used.
 *
 * @example
 * {{{
 * for {
 *   registry <- Llm4sConfig.modelRegistryService()
 *   cfg      <- Llm4sConfig.defaultProvider()
 *   client   <- LLMConnect.getClient(cfg)(using registry)
 * } yield client
 * }}}
 *
 * @see [[org.llm4s.config.Llm4sConfig.defaultProvider]] to load the configured default named provider
 * @see [[LLMClient]] for the conversation and completion API
 */
@Stable
object LLMConnect {

  /**
   * Constructs an [[LLMClient]] for the provider `config` names.
   *
   * A config whose provider is not registered yields a
   * [[org.llm4s.error.ConfigurationError]] listing the providers that are; `Left`
   * is also returned if the underlying client constructor fails (for example, if
   * the HTTP client library throws during initialisation).
   *
   * @param config  Provider configuration; its `providerId` selects the client.
   * @param options Runtime options for the client: the metrics collector that receives per-call latency, token
   *                usage and cost (`LlmClientOptions(metrics = collector)`), raw provider exchange logging, and
   *                the HTTP client. [[LlmClientOptions.default]] records no metrics and logs no exchange.
   */
  def getClient(
    config: ProviderConfig,
    options: LlmClientOptions = LlmClientOptions.default
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    summon[ProviderRegistry].resolve(config.providerId).flatMap(_.build(config, ProviderBuildContext.of(options)))

  /**
   * Constructs an [[LLMClient]] by explicitly specifying the provider.
   *
   * Validates that `config` is the type `provider`'s descriptor accepts; a mismatch
   * (for example, an OpenAI provider with an Anthropic config) is a
   * [[org.llm4s.error.ConfigurationError]] rather than a runtime failure later.
   * Prefer the overload without `provider` when a config is all you have.
   *
   * @param provider Selects which client implementation to construct.
   * @param config   Provider configuration; must be the config type `provider` accepts.
   * @param options  Runtime options for the client, as for the overload without `provider`. It has no default
   *                 here, since Scala allows one overload to have defaults: pass [[LlmClientOptions.default]].
   */
  def getClient(
    provider: ProviderId,
    config: ProviderConfig,
    options: LlmClientOptions
  )(using ModelRegistryService, ProviderRegistry): Result[LLMClient] =
    summon[ProviderRegistry].resolve(provider).flatMap(_.build(config, ProviderBuildContext.of(options)))
}
