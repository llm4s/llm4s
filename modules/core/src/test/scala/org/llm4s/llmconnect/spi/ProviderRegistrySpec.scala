package org.llm4s.llmconnect.spi

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Covers the registry that replaced core's central provider tables in #1131.
 *
 * The behaviour that matters is what a provider module depends on: a
 * descriptor it supplies is resolvable, its aliases are honoured, it can
 * override a built-in of the same id, and an id nothing claims produces an
 * error that says what to do about it.
 */
class ProviderRegistrySpec extends AnyWordSpec with Matchers:

  /** A descriptor with no client behind it: enough to exercise registration. */
  final private class StubProvider(name: String, override val aliases: Set[String] = Set.empty)
      extends ProviderDescriptor:
    val id: ProviderId                 = ProviderId(name)
    val configSpec: ProviderConfigSpec = ProviderConfigSpec()

    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] = Left(ConfigurationError(s"stub $name"))

    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
      ModelRegistryService
    ): Result[LLMClient] = Left(ConfigurationError(s"stub $name"))

  "ProviderRegistry.of" should {
    "resolve a descriptor by its id" in {
      val provider = new StubProvider("acme")
      ProviderRegistry.of(provider).get(ProviderId("acme")) shouldBe Right(provider)
    }

    "resolve an id in any spelling, because ProviderId canonicalises" in {
      val provider = new StubProvider("acme")
      ProviderRegistry.of(provider).get(ProviderId("  ACME ")) shouldBe Right(provider)
    }

    "list registered ids in sorted canonical spelling" in {
      ProviderRegistry.of(new StubProvider("zeta"), new StubProvider("acme")).ids shouldBe Seq("acme", "zeta")
    }

    "let a later registration replace an earlier one of the same id" in {
      val first  = new StubProvider("acme")
      val second = new StubProvider("acme")

      val registry = ProviderRegistry.of(first, second)
      registry.get(ProviderId("acme")) shouldBe Right(second)
      registry.ids shouldBe Seq("acme")
    }
  }

  "ProviderRegistry.withProvider" should {
    "add a provider without disturbing the others" in {
      val registry = ProviderRegistry.of(new StubProvider("acme")).withProvider(new StubProvider("beta"))
      registry.ids shouldBe Seq("acme", "beta")
    }

    "let a user descriptor win over a discovered one of the same id" in {
      // The test fixture provider is discovered through core's test META-INF/services, the same
      // path the built-ins take, so it stands in for any provider already on the classpath.
      val replacement = new StubProvider("fixturechat")
      ProviderRegistry.default.withProvider(replacement).get(ProviderId("fixturechat")) shouldBe Right(replacement)
    }
  }

  "ProviderRegistry.withModule" should {
    "add every provider the module supplies" in {
      val module = new Llm4sProviderModule:
        override def chatProviders: Seq[ProviderDescriptor] = Seq(new StubProvider("one"), new StubProvider("two"))

      ProviderRegistry.of().withModule(module).ids shouldBe Seq("one", "two")
    }
  }

  "ProviderRegistry.canonicalId" should {
    "fold a declared alias onto the id that owns it" in {
      val registry = ProviderRegistry.of(new StubProvider("acme", aliases = Set("acme-cloud")))
      registry.canonicalId("ACME-Cloud") shouldBe ProviderId("acme")
    }

    "leave an id nothing claims alone, so parsing does not depend on the classpath" in {
      ProviderRegistry.of().canonicalId(" Moonbeam ") shouldBe ProviderId("moonbeam")
    }
  }

  "an unresolvable provider" should {
    "produce an error naming the id, what is registered, and how to fix it" in {
      val registry = ProviderRegistry.of(new StubProvider("acme"))
      val message = registry
        .resolve(ProviderId("moonbeam"), Some("llm4s.providers.my-moon.provider"))
        .left
        .toOption
        .getOrElse(fail("Expected an unresolved-provider error"))
        .message

      message should include("Provider 'moonbeam' (from llm4s.providers.my-moon.provider) is not registered")
      message should include("Registered providers: acme")
      message should include("add the dependency that supplies it")
      message should include("ProviderRegistry.of(...)")
    }

    "omit the config path when the lookup did not come from config" in {
      val message = ProviderRegistry
        .of()
        .get(ProviderId("moonbeam"))
        .left
        .toOption
        .getOrElse(fail("Expected an unresolved-provider error"))
        .message

      message should include("Provider 'moonbeam' is not registered")
    }
  }

  "the default registry" should {
    "hold every provider built into llm4s-core" in {
      // Plus `fixturechat`, the test-only provider core's test classpath declares in its own
      // META-INF/services (org.llm4s.testutil.FixtureChatProvider).
      ProviderRegistry.default.ids shouldBe Seq(
        "cohere",
        "fixturechat",
        "mistral"
      )
    }
  }
