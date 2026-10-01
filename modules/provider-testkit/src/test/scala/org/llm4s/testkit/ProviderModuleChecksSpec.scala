package org.llm4s.testkit

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, EmbeddingRequest, StreamedChunk }
import org.llm4s.testkit.fixtures.*
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

/**
 * The checks pass for a well-formed module, and fail - with a message saying what is wrong - for
 * each way a module can be broken.
 */
class ProviderModuleChecksSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private val section = NamedProviderConfig(
    provider = AcmeProvider.id,
    model = ModelName("acme-large"),
    baseUrl = None,
    apiKey = Some(ApiKey("test-key"))
  )

  private def failureOf(check: => Any): String =
    intercept[TestFailedException](check).getMessage

  "the registration checks" should {

    "pass for a module named in META-INF/services and supplying its ids alone" in {
      assertModule(new Llm4sAcmeModule)
    }

    "fail assertDiscovered for a module that is in no services file" in {
      failureOf(assertDiscovered(new UnlistedModule)) should include("was not discovered")
    }

    "report a failure at the line in the calling spec" in {
      val failure = intercept[TestFailedException](assertDiscovered(new UnlistedModule))
      failure.failedCodeFileName shouldBe Some("ProviderModuleChecksSpec.scala")
    }

    "still let that module be registered explicitly" in {
      assertRegistrableWith(new UnlistedModule)
    }

    "fail assertModule for a module that lists nothing" in {
      failureOf(assertModule(new EmptyModule)) should include("lists no chat or embedding provider")
    }

    "fail assertDiscovered for the module whose id another module's descriptor won" in {
      val failures =
        Seq(new TwinModuleA, new TwinModuleB).flatMap(module => Try(assertDiscovered(module)).failed.toOption)
      failures should have size 1
      failures.head.getMessage should include("chat id 'twin' resolves to")
    }

    "fail assertSoleSupplier when two listed modules claim the same id" in {
      val message = failureOf(assertSoleSupplier(new TwinModuleA))
      message should include("'twin'")
      message should include(classOf[TwinModuleB].getName)
    }

    "resolve aliases as well as ids" in {
      assertRegistrableWith(new Llm4sAcmeModule)
      AcmeProvider.aliases should contain("acme-ai")
      AcmeEmbeddings.aliases should contain("acme-embed")
    }
  }

  "the config-to-client checks" should {

    "build a client from a section" in {
      assertBuildsClient(AcmeProvider, section) shouldBe AcmeClient(
        AcmeConfig("test-key", "acme-large", AcmeProvider.DefaultBaseUrl)
      )
    }

    "report a section the descriptor rejects" in {
      buildClient(AcmeProvider, section.copy(apiKey = None)).isLeft shouldBe true
      failureOf(assertBuildsClient(AcmeProvider, section.copy(apiKey = None))) should include(
        "acme failed to build a client"
      )
    }

    "pass assertRefusesForeignConfig for a descriptor using expectConfig" in {
      assertRefusesForeignConfig(AcmeProvider)
    }

    "fail it for a descriptor that builds a client from any config" in {
      failureOf(assertRefusesForeignConfig(GreedyProvider)) should include("greedy accepted another provider's config")
    }
  }

  "assertStreams" should {

    "pass for a client that delivers chunks" in {
      assertStreams(assertBuildsClient(AcmeProvider, section))
    }

    "fail for a client that succeeds without a chunk" in {
      val client = assertBuildsClient(AcmeProvider, section.copy(model = ModelName(AcmeProvider.NonStreamingModel)))
      failureOf(assertStreams(client)) should include("without delivering a single chunk")
    }

    "fail for a client whose stream fails" in {
      val failing = new LLMClient:
        def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
          Left(ServiceError(503, "acme", "unavailable"))
        def streamComplete(
          conversation: Conversation,
          options: CompletionOptions,
          onChunk: StreamedChunk => Unit
        ): Result[Completion] = complete(conversation, options)
        def getContextWindow(): Int     = 8192
        def getReserveCompletion(): Int = 1024

      failureOf(assertStreams(failing)) should include("streamComplete failed")
    }
  }

  "assertBuildsEmbeddingProvider" should {

    "return the provider the descriptor builds" in {
      val provider = assertBuildsEmbeddingProvider(
        AcmeEmbeddings,
        EmbeddingProviderConfig(AcmeProvider.DefaultBaseUrl, "acme-small", "k")
      )
      provider.embed(EmbeddingRequest(Seq("a"), EmbeddingModelConfig("acme-small", 2))).isRight shouldBe true
    }

    "fail when the descriptor refuses the config" in {
      failureOf(
        assertBuildsEmbeddingProvider(
          AcmeEmbeddings,
          EmbeddingProviderConfig(AcmeProvider.DefaultBaseUrl, "acme-retired", "k")
        )
      ) should include("no longer served")
    }
  }

  "the credential binding checks" should {

    "pass when reference.conf binds what apiKeyEnv declares" in {
      assertCredentialBindings(AcmeProvider)
      assertEmbeddingCredentialBindings(AcmeEmbeddings, "acme-small")
    }

    "pass trivially for a descriptor that declares no variable" in {
      assertCredentialBindings(UnlistedProvider)
    }

    "fail, naming the missing line, when the variable is declared but not bound" in {
      val chat = failureOf(assertCredentialBindings(UnboundProvider))
      chat should include("UNBOUND_API_KEY")
      chat should include("llm4s.credentials.unbound.apiKey = ${?UNBOUND_API_KEY}")

      failureOf(assertEmbeddingCredentialBindings(UnboundEmbeddings, "m")) should include(
        "llm4s.credentials.unbound.apiKey = ${?UNBOUND_API_KEY}"
      )
    }
  }

  "the companion object" should {

    "offer the same checks without mixing the trait in" in {
      ProviderModuleChecks.assertRegistrableWith(new Llm4sAcmeModule)
      ProviderModuleChecks.defaultModelRegistry.lookup("gpt-4o").isRight shouldBe true
    }
  }
