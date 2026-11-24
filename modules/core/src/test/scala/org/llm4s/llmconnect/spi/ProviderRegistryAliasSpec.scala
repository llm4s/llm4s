package org.llm4s.llmconnect.spi

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ProviderConfig }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.fixtures.{ FixtureEmbeddings, FixtureProvider }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.testutil.FixtureChatProvider
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URLClassLoader
import org.llm4s.llmconnect.spi.{ EmbeddingBuildContext, ProviderBuildContext, ProviderConfigContext }

/** Lookups honour aliases, an id beats an alias, alias collisions are reported, and discovery is guarded (#2812). */
class ProviderRegistryAliasSpec extends AnyWordSpec with Matchers:

  final private class Stub(name: String, override val aliases: Set[String] = Set.empty) extends ProviderDescriptor:
    val id: ProviderId                 = ProviderId(name)
    val configSpec: ProviderConfigSpec = ProviderConfigSpec()

    def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] = Left(
      ConfigurationError(s"stub $name")
    )

    def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] = Left(
      ConfigurationError(s"stub $name")
    )

  final private class EmbeddingStub(name: String, override val aliases: Set[String] = Set.empty)
      extends EmbeddingProviderDescriptor:
    val id: ProviderId                                           = ProviderId(name)
    val configSpec: org.llm4s.llmconnect.spi.EmbeddingConfigSpec = org.llm4s.llmconnect.spi.EmbeddingConfigSpec()

    def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] = Left(
      ConfigurationError(s"stub $name")
    )

  private def aliasCollisions(registry: ProviderRegistry) = registry.report.collisions.filter(_.kind.isAlias)

  "find and resolve" should {
    "find a provider by an alias it declares, in any spelling" in {
      val acme     = new Stub("acme", aliases = Set("acme-cloud"))
      val registry = ProviderRegistry.of(acme)

      registry.find(ProviderId(" ACME-Cloud ")) shouldBe Some(acme)
      registry.resolve(ProviderId("acme-cloud"), Some("llm4s.providers.x.provider")) shouldBe Right(acme)
      registry.resolve(ProviderId("acme")) shouldBe Right(acme)
    }

    "find an embedding provider by an alias it declares" in {
      val voyage   = new EmbeddingStub("voyage", aliases = Set("voyageai"))
      val registry = ProviderRegistry.ofEmbeddings(voyage)

      registry.findEmbedding(ProviderId("VoyageAI")) shouldBe Some(voyage)
      registry.resolveEmbedding(ProviderId("voyageai")) shouldBe Right(voyage)
    }
  }

  "an alias equal to another provider's id" should {
    "lose to the id, and be reported" in {
      val beta     = new Stub("beta")
      val acme     = new Stub("acme", aliases = Set("beta"))
      val registry = ProviderRegistry.of(beta, acme)

      registry.canonicalId("beta") shouldBe ProviderId("beta")
      registry.resolve(ProviderId("beta")) shouldBe Right(beta)
      ProviderRegistry.of(acme, beta).resolve(ProviderId("beta")) shouldBe Right(beta)

      aliasCollisions(registry) shouldBe Seq(
        ProviderIdCollision(
          ProviderCollisionKind.ChatAlias,
          ProviderId("beta"),
          "provider 'beta'",
          Some("provider 'acme'")
        )
      )
      registry.report.describe should include(
        "chat alias 'beta' registered by both provider 'acme' and provider 'beta'"
      )
    }

    "not be reported when it is the provider's own id" in {
      val registry = ProviderRegistry.of(new Stub("acme", aliases = Set("ACME")))
      registry.report.hasCollisions shouldBe false
    }
  }

  "an alias two providers declare" should {
    "go to the later one, and be reported" in {
      val first    = new Stub("first", aliases = Set("shared"))
      val second   = new Stub("second", aliases = Set("shared"))
      val registry = ProviderRegistry.of(first, second)

      registry.resolve(ProviderId("shared")) shouldBe Right(second)
      aliasCollisions(registry) shouldBe Seq(
        ProviderIdCollision(
          ProviderCollisionKind.ChatAlias,
          ProviderId("shared"),
          "provider 'second'",
          Some("provider 'first'")
        )
      )
    }

    "be reported for embedding providers too" in {
      val registry = ProviderRegistry.ofEmbeddings(
        new EmbeddingStub("one", aliases = Set("shared")),
        new EmbeddingStub("two", aliases = Set("shared"))
      )
      aliasCollisions(registry) shouldBe Seq(
        ProviderIdCollision(
          ProviderCollisionKind.EmbeddingAlias,
          ProviderId("shared"),
          "provider 'two'",
          Some("provider 'one'")
        )
      )
    }

    "be reported once, however many providers are registered after it" in {
      val registry = ProviderRegistry
        .of(new Stub("first", aliases = Set("shared")), new Stub("second", aliases = Set("shared")))
        .withProvider(new Stub("third"))
        .withProvider(new Stub("fourth"))
      aliasCollisions(registry).map(_.id) shouldBe Seq("shared")
    }

    "stop being reported when the provider that declared it is replaced" in {
      val registry = ProviderRegistry
        .of(new Stub("first", aliases = Set("shared")), new Stub("second", aliases = Set("shared")))
        .withProvider(new Stub("second"))
      aliasCollisions(registry) shouldBe empty
      registry.resolve(ProviderId("shared")).map(_.id) shouldBe Right(ProviderId("first"))
    }
  }

  "discovery" should {
    def loaderFor(directory: String): ClassLoader =
      val parent = getClass.getClassLoader
      val url = Option(parent.getResource(s"provider-discovery/$directory/"))
        .getOrElse(fail(s"fixture directory 'provider-discovery/$directory/' is missing from test resources"))
      new URLClassLoader(Array(url), parent)

    "record a descriptor whose id or aliases throw, and keep the module's other descriptors" in {
      val registry = ProviderRegistry.discover(loaderFor("skewed"))

      registry.resolve(ProviderId("fixturecloud")) shouldBe Right(FixtureProvider)
      registry.resolveEmbedding(ProviderId("fixtureembed")) shouldBe Right(FixtureEmbeddings)
      registry.ids should not contain "skewed"

      val detail = registry.report.failures.map(_.detail).mkString("\n")
      detail should include("SkewedProviderModule")
      detail should include("ProviderDescriptor.aliases()")
      detail should include("EmbeddingProviderDescriptor.id()")
      detail should include("java.lang.AbstractMethodError")

      val module = registry.report.modules
        .find(_.moduleClass.endsWith("SkewedProviderModule"))
        .getOrElse(fail(registry.report.describe))
      module.providerIds.map(_.asString) shouldBe Seq("fixturecloud")
      module.embeddingProviderIds.map(_.asString) shouldBe Seq("fixtureembed")
    }

    "scan core's own loader as well when the context loader finds no module" in {
      val empty    = new URLClassLoader(Array.empty[java.net.URL], null)
      val thread   = Thread.currentThread
      val previous = thread.getContextClassLoader
      thread.setContextClassLoader(empty)
      val registry =
        try ProviderRegistry.discover()
        finally thread.setContextClassLoader(previous)

      registry.ids should contain(FixtureChatProvider.id.asString)
    }

    "count a module once when the context loader and core's loader both see it" in {
      val thread   = Thread.currentThread
      val previous = thread.getContextClassLoader
      thread.setContextClassLoader(getClass.getClassLoader)
      val registry =
        try ProviderRegistry.discover()
        finally thread.setContextClassLoader(previous)

      registry.report.hasCollisions shouldBe false
      registry.report.modules.map(_.moduleClass).distinct shouldBe registry.report.modules.map(_.moduleClass)
    }
  }
