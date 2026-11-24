package org.llm4s.llmconnect.spi

import org.llm4s.annotation.Stable
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.ContextWindowResolver
import org.llm4s.llmconnect.{ LlmClientOptions, ProviderExchangeLogging }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService

/**
 * What [[ProviderDescriptor.buildConfig]] is given besides the section itself.
 *
 * One value rather than a list of parameters, so that something a later provider needs is a new field here and
 * not a new signature for every descriptor.
 *
 * @param providerName   the user's name for the section (`llm4s.providers.<name>`), for error messages and for
 *                       the `ProviderDescriptor.require*` helpers
 * @param contextWindows resolves a model's context window and output reserve; a config's `fromValues` takes it
 *                       as a `using` parameter, so a descriptor usually starts with
 *                       `given ContextWindowResolver = context.contextWindows`
 */
@Stable
final case class ProviderConfigContext private (providerName: String, contextWindows: ContextWindowResolver):

  /** Sets the section name used in error messages. */
  def withProviderName(providerName: String): ProviderConfigContext = copy(providerName = providerName)

  /** Sets the resolver of context windows. */
  def withContextWindows(contextWindows: ContextWindowResolver): ProviderConfigContext =
    copy(contextWindows = contextWindows)

object ProviderConfigContext:

  /** Creates a [[ProviderConfigContext]]. */
  def apply(providerName: String, contextWindows: ContextWindowResolver): ProviderConfigContext =
    new ProviderConfigContext(providerName, contextWindows)

  /**
   * The context for the section named `providerName`, with the `ContextWindowResolver` in scope - which, with a
   * `ModelRegistryService` in scope and no resolver, is the one over that registry.
   */
  def of(providerName: String)(using contextWindows: ContextWindowResolver): ProviderConfigContext =
    new ProviderConfigContext(providerName, contextWindows)

/**
 * What [[ProviderDescriptor.build]] is given besides the config itself.
 *
 * @param options       the caller's client options: metrics, exchange logging and their own HTTP client
 * @param modelRegistry the model registry the client looks model metadata up in; a client's constructor usually
 *                      takes it as a `using` parameter, so a descriptor passes it on with
 *                      `given ModelRegistryService = context.modelRegistry`
 */
@Stable
final case class ProviderBuildContext private (options: LlmClientOptions, modelRegistry: ModelRegistryService):

  /** The collector the client reports request metrics to: `options.metrics`. */
  def metrics: MetricsCollector = options.metrics

  /** Whether, and where, provider exchanges are logged: `options.exchangeLogging`. */
  def exchangeLogging: ProviderExchangeLogging = options.exchangeLogging

  /**
   * The HTTP client to send requests through, when the caller supplied one: `options.httpClient`. The caller
   * owns it, so the provider's client does not close it.
   */
  def httpClient: Option[Llm4sHttpClient] = options.httpClient

  /** Sets the caller's client options. */
  def withOptions(options: LlmClientOptions): ProviderBuildContext = copy(options = options)

  /** Sets the model registry. */
  def withModelRegistry(modelRegistry: ModelRegistryService): ProviderBuildContext =
    copy(modelRegistry = modelRegistry)

object ProviderBuildContext:

  /** Creates a [[ProviderBuildContext]]. */
  def apply(options: LlmClientOptions, modelRegistry: ModelRegistryService): ProviderBuildContext =
    new ProviderBuildContext(options, modelRegistry)

  /** The context for `options` - the defaults, with no argument - with the `ModelRegistryService` in scope. */
  def of(options: LlmClientOptions = LlmClientOptions.default)(using
    modelRegistry: ModelRegistryService
  ): ProviderBuildContext =
    new ProviderBuildContext(options, modelRegistry)
