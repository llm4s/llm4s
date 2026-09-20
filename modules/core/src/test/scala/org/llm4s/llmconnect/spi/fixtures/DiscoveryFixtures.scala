package org.llm4s.llmconnect.spi.fixtures

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * A provider that exists only to be discovered.
 *
 * It stands in for a provider module outside `llm4s-core`: nothing in core
 * mentions it, and the only thing that makes it reachable is a
 * `META-INF/services` entry on the class loader under test.
 */
object FixtureProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("fixturecloud")
  val configSpec: ProviderConfigSpec = ProviderConfigSpec(requiresApiKey = true)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    Left(ConfigurationError("fixture provider builds no config"))

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    Left(ConfigurationError("fixture provider builds no client"))

/**
 * The services entry point for [[FixtureProvider]].
 *
 * A plain `class` with a public no-arg constructor, as `ServiceLoader`
 * requires — the shape every provider module must use.
 */
final class FixtureProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(FixtureProvider)

/** A module whose own code fails, to prove discovery survives a hostile module. */
final class ThrowingProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] =
    throw new IllegalStateException("this module is broken")
