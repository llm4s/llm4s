package org.llm4s.llmconnect.spi

import org.llm4s.llmconnect.spi.fixtures.{ FixtureEmbeddings, FixtureProvider }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URLClassLoader

/**
 * Covers `META-INF/services` discovery — the point at which adding a provider
 * stops being a code change and becomes adding a dependency (#1131, PR 3).
 *
 * Each case builds a class loader over one fixture directory under
 * `src/test/resources/provider-discovery`, each holding a services file. They
 * live in subdirectories rather than at the resource root deliberately: a
 * services file at the root would be on every test's classpath and would
 * change what `ProviderRegistry.default` holds for the whole suite.
 *
 * The loaders delegate to the test class loader, so core's own services entry
 * is visible too. That is the realistic arrangement — a real classpath has the
 * built-ins and the extra module together — and it is what makes the
 * "one broken jar must not take out the others" cases meaningful.
 */
class ProviderDiscoverySpec extends AnyWordSpec with Matchers:

  /** A loader that sees the fixture services file in `directory`, plus the real classpath. */
  private def loaderFor(directory: String): ClassLoader =
    val parent = getClass.getClassLoader
    val url = Option(parent.getResource(s"provider-discovery/$directory/"))
      .getOrElse(fail(s"fixture directory 'provider-discovery/$directory/' is missing from test resources"))
    new URLClassLoader(Array(url), parent)

  private val builtinIds = ProviderRegistry.builtin.ids

  "discovery" should {

    "find the built-in providers through core's own services file" in {
      val registry = ProviderRegistry.discover(getClass.getClassLoader)

      registry.ids should contain allElementsOf builtinIds
      registry.report.discovered shouldBe true
      registry.report.modules.map(_.moduleClass) should contain(
        "org.llm4s.llmconnect.provider.BuiltinProviderModule"
      )
      registry.report.failures shouldBe empty
    }

    "find a provider module that llm4s-core knows nothing about" in {
      val registry = ProviderRegistry.discover(loaderFor("good"))

      registry.get(ProviderId("fixturecloud")) shouldBe Right(FixtureProvider)
      // The point of the SPI: the new provider arrives without displacing anything.
      registry.ids should contain allElementsOf builtinIds

      val fixtureModule = registry.report.modules
        .find(_.moduleClass == "org.llm4s.llmconnect.spi.fixtures.FixtureProviderModule")
        .getOrElse(fail(s"fixture module was not reported: ${registry.report.describe}"))

      fixtureModule.providerIds shouldBe Seq("fixturecloud")
      fixtureModule.source should not be empty
    }

    "record an unloadable service entry instead of throwing" in {
      // `ServiceLoader`'s own iterator throws ServiceConfigurationError here.
      val registry = ProviderRegistry.discover(loaderFor("broken"))

      registry.report.hasFailures shouldBe true
      registry.report.failures.map(_.detail).mkString should include("NoSuchProviderModule")
      registry.report.failures.flatMap(_.failure).head shouldBe a[Throwable]
    }

    "keep every working provider when one service entry is broken" in {
      val registry = ProviderRegistry.discover(loaderFor("mixed"))

      registry.get(ProviderId("fixturecloud")) shouldBe Right(FixtureProvider)
      registry.ids should contain allElementsOf builtinIds
      registry.report.hasFailures shouldBe true
    }

    "record a module that throws a LinkageError, not just an exception" in {
      // A jar compiled against another llm4s throws `AbstractMethodError` when called, and
      // `Try` does not catch it: unguarded it escapes `discover` and leaves
      // `ProviderRegistry.default` uninitialisable, losing every working provider.
      val registry = ProviderRegistry.discover(loaderFor("linkage"))

      val detail = registry.report.failures.map(_.detail).mkString
      detail should include("LinkageErrorProviderModule")
      detail should include("java.lang.AbstractMethodError")

      // The module behind the broken one, and the built-ins, are unaffected.
      registry.get(ProviderId("fixturecloud")) shouldBe Right(FixtureProvider)
      registry.ids should contain allElementsOf builtinIds
    }

    "find an embedding-only provider module" in {
      // Voyage's shape: embeddings and no chat client. The module never overrides
      // `chatProviders`, so this is also the test that the default is usable.
      val registry = ProviderRegistry.discover(loaderFor("embeddings"))

      registry.resolveEmbedding(ProviderId("fixtureembed")) shouldBe Right(FixtureEmbeddings)
      registry.embeddingIds should contain("fixtureembed")
      // It contributes nothing to the chat namespace.
      registry.ids shouldBe builtinIds

      val module = registry.report.modules
        .find(_.moduleClass == "org.llm4s.llmconnect.spi.fixtures.FixtureEmbeddingModule")
        .getOrElse(fail(s"embedding module was not reported: ${registry.report.describe}"))

      module.providerIds shouldBe empty
      module.embeddingProviderIds shouldBe Seq("fixtureembed")
      module.contribution should include("embeddings: fixtureembed")
    }

    "find both halves of a module that supplies chat and embeddings" in {
      // llm4s-ollama's shape, and the reason discovery asks for both lists.
      val registry = ProviderRegistry.discover(loaderFor("both-halves"))

      registry.get(ProviderId("fixturecloud")) shouldBe Right(FixtureProvider)
      registry.resolveEmbedding(ProviderId("fixtureembed")) shouldBe Right(FixtureEmbeddings)
    }

    "keep the working half of a module whose embedding half throws" in {
      val registry = ProviderRegistry.discover(loaderFor("half-broken"))

      registry.get(ProviderId("fixturecloud")) shouldBe Right(FixtureProvider)

      val detail = registry.report.failures.map(_.detail).mkString
      detail should include("embedding providers")
      detail should include("the embedding half of this module is broken")
      // The failure is attributed to the embedding half specifically, not the whole module.
      (detail should not).include("for its chat providers")
    }

    "record a module that throws when asked for its providers" in {
      val registry = ProviderRegistry.discover(loaderFor("throwing"))

      registry.report.failures.map(_.detail).mkString should include("ThrowingProviderModule")
      registry.report.failures.map(_.detail).mkString should include("this module is broken")
      // The built-ins, discovered from the parent loader, are unaffected.
      registry.ids should contain allElementsOf builtinIds
    }
  }

  "an explicitly registered provider" should {
    "override one that was discovered" in {
      val replacement = new ProviderDescriptor:
        val id: ProviderId                 = ProviderId("fixturecloud")
        val configSpec: ProviderConfigSpec = ProviderConfigSpec()

        def buildConfig(providerName: String, section: org.llm4s.config.ProvidersConfigModel.NamedProviderConfig)(using
          org.llm4s.llmconnect.config.ContextWindowResolver
        ): org.llm4s.types.Result[org.llm4s.llmconnect.config.ProviderConfig] =
          Left(org.llm4s.error.ConfigurationError("replacement"))

        def buildClient(
          config: org.llm4s.llmconnect.config.ProviderConfig,
          options: org.llm4s.llmconnect.LlmClientOptions
        )(using org.llm4s.model.ModelRegistryService): org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
          Left(org.llm4s.error.ConfigurationError("replacement"))

      val registry = ProviderRegistry.discover(loaderFor("good")).withProvider(replacement)

      registry.get(ProviderId("fixturecloud")) shouldBe Right(replacement)
      registry.ids.count(_ == "fixturecloud") shouldBe 1
      // Overriding must not discard the diagnostics from the scan.
      registry.report.discovered shouldBe true
    }
  }

  "the discovery report" should {

    "summarise a clean scan for the error message that names it" in {
      val registry = ProviderRegistry.discover(loaderFor("good"))

      registry.report.summary should include(s"Discovery scanned ${registry.report.modules.size} modules")
      registry.report.summary should include("0 failed")
    }

    "name the failures in the summary when there are any" in {
      val summary = ProviderRegistry.discover(loaderFor("broken")).report.summary

      summary should include("1 failed")
      summary should include("NoSuchProviderModule")
    }

    "say nothing about a scan for a registry that was built explicitly" in {
      ProviderRegistry.of().report.discovered shouldBe false
      ProviderRegistry.of().report.summary shouldBe ""
      ProviderRegistry.of().report.describe should include("no classpath discovery ran")
    }
  }

  "an unresolvable provider" should {
    "have the discovery summary in its error, so a mangled fat jar is visible" in {
      val message = ProviderRegistry
        .discover(loaderFor("broken"))
        .resolve(ProviderId("moonbeam"), Some("llm4s.providers.my-moon.provider"))
        .left
        .toOption
        .getOrElse(fail("expected an unresolved-provider error"))
        .message

      message should include("Provider 'moonbeam' (from llm4s.providers.my-moon.provider) is not registered")
      message should include("Discovery scanned")
      message should include("1 failed")
    }

    "say nothing about discovery when the registry was built explicitly" in {
      val message = ProviderRegistry
        .of()
        .get(ProviderId("moonbeam"))
        .left
        .toOption
        .getOrElse(fail("expected an unresolved-provider error"))
        .message

      (message should not).include("Discovery scanned")
    }
  }

  "the default registry" should {
    "be the discovered one, and hold every built-in provider" in {
      ProviderRegistry.default.report.discovered shouldBe true
      ProviderRegistry.default.ids shouldBe builtinIds
      ProviderRegistry.default.embeddingIds shouldBe ProviderRegistry.builtin.embeddingIds
    }
  }

  "the two provider namespaces" should {

    "be independent, so one id can name a chat and an embedding provider" in {
      val registry = ProviderRegistry.builtin

      // Ollama supplies both; Anthropic only chat; Voyage only embeddings. That overlap
      // without containment is why the embedding descriptor is a separate trait.
      registry.ids should contain("ollama")
      registry.embeddingIds should contain("ollama")
      registry.ids should contain("anthropic")
      (registry.embeddingIds should not).contain("anthropic")
      registry.embeddingIds should contain("voyage")
      (registry.ids should not).contain("voyage")
    }

    "not resolve a chat provider as an embedding one" in {
      val error = ProviderRegistry.builtin
        .resolveEmbedding(ProviderId("anthropic"), Some("llm4s.embeddings.model"))
        .left
        .toOption
        .getOrElse(fail("expected anthropic to supply no embedding provider"))
        .message

      error should include("Embedding provider 'anthropic'")
      error should include("(from llm4s.embeddings.model)")
      // It lists the embedding providers, not the chat ones - the point of separate namespaces.
      error should include("voyage")
      (error should not).include("anthropic,")
    }

    "each point at the registration call that accepts their own descriptor type" in {
      // `of` takes chat descriptors and `ofEmbeddings` embedding ones, so an error naming the
      // wrong one hands the reader a compile error as their next step.
      val chat = ProviderRegistry.builtin
        .resolve(ProviderId("moonbeam"))
        .left
        .toOption
        .getOrElse(fail("expected an unresolved-provider error"))
        .message

      val embedding = ProviderRegistry.builtin
        .resolveEmbedding(ProviderId("moonbeam"))
        .left
        .toOption
        .getOrElse(fail("expected an unresolved-provider error"))
        .message

      chat should include("ProviderRegistry.of(...)")
      embedding should include("ProviderRegistry.ofEmbeddings(...)")
      (embedding should not).include("ProviderRegistry.of(...)")
    }

    "fold an embedding alias onto its canonical id" in {
      ProviderRegistry.builtin.canonicalEmbeddingId("voyageai").asString shouldBe "voyage"
      // An id nothing claims is returned canonicalised but unchanged.
      ProviderRegistry.builtin.canonicalEmbeddingId("Moonbeam").asString shouldBe "moonbeam"
    }

    "let an explicitly registered embedding provider override a discovered one" in {
      val registry = ProviderRegistry.builtin.withEmbeddingProvider(FixtureEmbeddings)

      registry.resolveEmbedding(ProviderId("fixtureembed")) shouldBe Right(FixtureEmbeddings)
      // Registering an embedding provider leaves the chat namespace alone.
      registry.ids shouldBe builtinIds
    }
  }
