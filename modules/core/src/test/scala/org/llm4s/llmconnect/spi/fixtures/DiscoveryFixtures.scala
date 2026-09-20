package org.llm4s.llmconnect.spi.fixtures

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{
  EmbeddingProviderDescriptor,
  Llm4sProviderModule,
  ProviderConfigSpec,
  ProviderDescriptor
}
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

/**
 * A module that fails the way a stale jar does.
 *
 * `AbstractMethodError` is what a module compiled against an older llm4s throws
 * when core calls it, and `scala.util.Try` does not catch it — nor any other
 * `LinkageError`. Without an explicit guard it escapes discovery entirely and
 * `ProviderRegistry.default` cannot initialise, taking every working provider
 * with it.
 */
final class LinkageErrorProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] =
    throw new AbstractMethodError("org.llm4s.llmconnect.spi.Llm4sProviderModule.chatProviders()")

/**
 * An embedding provider that exists only to be discovered.
 *
 * Voyage's shape: embeddings and no chat client, which is the case that makes
 * `EmbeddingProviderDescriptor` a separate trait.
 */
object FixtureEmbeddings extends EmbeddingProviderDescriptor:
  val id: ProviderId                = ProviderId("fixtureembed")
  override val aliases: Set[String] = Set("fixture-embeddings")

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
    Right(
      new EmbeddingProvider:
        def embed(request: org.llm4s.llmconnect.model.EmbeddingRequest) =
          Right(
            org.llm4s.llmconnect.model.EmbeddingResponse(
              embeddings = Seq(Vector(1.0, 2.0)),
              metadata = Map("provider" -> "fixtureembed", "baseUrl" -> config.baseUrl)
            )
          )
    )

/** A module supplying only embeddings - it never overrides `chatProviders`. */
final class FixtureEmbeddingModule extends Llm4sProviderModule:
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(FixtureEmbeddings)

/** A module supplying both halves, as `llm4s-ollama` will. */
final class FixtureBothHalvesModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor]               = Seq(FixtureProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(FixtureEmbeddings)

/**
 * A module whose embedding half throws while its chat half is fine.
 *
 * Discovery asks for the two lists separately and guards each, so the working
 * half must still be registered and the report must say which half failed.
 */
final class HalfBrokenProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(FixtureProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] =
    throw new IllegalStateException("the embedding half of this module is broken")
