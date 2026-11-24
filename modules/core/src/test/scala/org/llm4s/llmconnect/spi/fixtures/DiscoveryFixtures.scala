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
  val id: ProviderId                                           = ProviderId("fixtureembed")
  val configSpec: org.llm4s.llmconnect.spi.EmbeddingConfigSpec = org.llm4s.llmconnect.spi.EmbeddingConfigSpec()
  override val aliases: Set[String]                            = Set("fixture-embeddings")

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

/** A module supplying both halves, as `llm4s-ollama` does. */
final class FixtureBothHalvesModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor]               = Seq(FixtureProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(FixtureEmbeddings)

/** A provider that collides with [[FixtureProvider]]'s id, to exercise collision reporting. */
object CollidingProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("fixturecloud")
  val configSpec: ProviderConfigSpec = ProviderConfigSpec(requiresApiKey = true)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    Left(ConfigurationError("colliding provider builds no config"))

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    Left(ConfigurationError("colliding provider builds no client"))

/** A second module claiming `fixturecloud`, the same id [[FixtureProviderModule]] supplies. */
final class CollidingProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(CollidingProvider)

/** An embedding provider that collides with [[FixtureEmbeddings]]'s id, to exercise collision reporting. */
object CollidingEmbeddings extends EmbeddingProviderDescriptor:
  val id: ProviderId                                           = ProviderId("fixtureembed")
  val configSpec: org.llm4s.llmconnect.spi.EmbeddingConfigSpec = org.llm4s.llmconnect.spi.EmbeddingConfigSpec()

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
    Left(ConfigurationError("colliding embedding provider builds no embedding provider"))

/** A second module claiming `fixtureembed`, the same id [[FixtureEmbeddingModule]] supplies. */
final class CollidingEmbeddingModule extends Llm4sProviderModule:
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(CollidingEmbeddings)

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

/**
 * A chat provider compiled against another llm4s: asking for its aliases throws, as a missing method
 * does (#2812).
 */
object SkewedProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("skewed")
  val configSpec: ProviderConfigSpec = ProviderConfigSpec()

  override def aliases: Set[String] =
    throw new AbstractMethodError("org.llm4s.llmconnect.spi.ProviderDescriptor.aliases()")

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    Left(ConfigurationError("skewed provider builds no config"))

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    Left(ConfigurationError("skewed provider builds no client"))

/** An embedding provider whose id throws, as a version-skewed descriptor's can (#2812). */
object SkewedEmbeddings extends EmbeddingProviderDescriptor:
  def id: ProviderId = throw new AbstractMethodError("org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor.id()")
  val configSpec: org.llm4s.llmconnect.spi.EmbeddingConfigSpec = org.llm4s.llmconnect.spi.EmbeddingConfigSpec()

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = FixtureEmbeddings.build(config)

/** A module whose skewed descriptors sit beside a working one: only the skewed ones are dropped. */
final class SkewedProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor]               = Seq(SkewedProvider, FixtureProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(SkewedEmbeddings, FixtureEmbeddings)
