package org.llm4s.llmconnect.spi

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.fixtures.CollidingProvider
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URLClassLoader

/**
 * Covers reporting a same-id collision: two descriptors both registering
 * `"acme"`, which `ProviderRegistry` has always resolved by keeping the
 * last one. Before this, that resolution was silent; these tests are the
 * visibility it was missing, not a change in which descriptor wins.
 */
class ProviderRegistryCollisionSpec extends AnyWordSpec with Matchers:

  /** A descriptor with no client behind it: enough to exercise registration. */
  final private class StubProvider(name: String) extends ProviderDescriptor:
    val id: ProviderId                 = ProviderId(name)
    val configSpec: ProviderConfigSpec = ProviderConfigSpec()

    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] = Left(ConfigurationError(s"stub $name"))

    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
      ModelRegistryService
    ): Result[LLMClient] = Left(ConfigurationError(s"stub $name"))

  "ProviderRegistry.of" should {
    "report a collision when two descriptors share an id, and still let the last one win" in {
      val first  = new StubProvider("acme")
      val second = new StubProvider("acme")

      val registry = ProviderRegistry.of(first, second)

      registry.get(ProviderId("acme")) shouldBe Right(second)
      registry.report.hasCollisions shouldBe true
      registry.report.collisions shouldBe Seq(ProviderIdCollision("chat", "acme", "explicit registration", None))
    }

    "report no collision when every id is unique" in {
      ProviderRegistry.of(new StubProvider("acme"), new StubProvider("beta")).report.hasCollisions shouldBe false
    }
  }

  "ProviderRegistry.discover" should {
    "name both modules when two service entries claim the same id" in {
      val parent = getClass.getClassLoader
      def urlFor(directory: String) =
        Option(parent.getResource(s"provider-discovery/$directory/"))
          .getOrElse(fail(s"fixture directory 'provider-discovery/$directory/' is missing from test resources"))
      val loader = new URLClassLoader(Array(urlFor("good"), urlFor("colliding")), parent)

      val registry = ProviderRegistry.discover(loader)

      registry.get(ProviderId("fixturecloud")) shouldBe Right(CollidingProvider)
      registry.report.hasCollisions shouldBe true

      val collision = registry.report.collisions
        .find(_.id == "fixturecloud")
        .getOrElse(fail(s"no collision reported: ${registry.report.describe}"))

      collision.kind shouldBe "chat"
      collision.keptModule shouldBe "org.llm4s.llmconnect.spi.fixtures.CollidingProviderModule"
      collision.droppedModule shouldBe Some("org.llm4s.llmconnect.spi.fixtures.FixtureProviderModule")
    }

    "report no collision for a clean scan" in {
      val parent = getClass.getClassLoader
      val url = Option(parent.getResource("provider-discovery/good/"))
        .getOrElse(fail("fixture directory 'provider-discovery/good/' is missing from test resources"))
      val loader = new URLClassLoader(Array(url), parent)

      ProviderRegistry.discover(loader).report.hasCollisions shouldBe false
    }
  }

  "ProviderRegistryReport.describe" should {
    "include a line naming the colliding id and both modules" in {
      val description = ProviderRegistryReport(
        discovered = true,
        collisions = Seq(ProviderIdCollision("chat", "acme", "second-module", Some("first-module")))
      ).describe

      description should include("chat id 'acme'")
      description should include("first-module")
      description should include("second-module")
    }
  }
