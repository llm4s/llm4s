package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{ EmbeddingBuildContext, EmbeddingProviderDescriptor, ProviderRegistry }
import org.llm4s.testutil.FixtureEmbeddingProvider
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `ModelDimensionRegistry` answers from the descriptors in the caller's registry
 * (#1131). It used to hold a central table covering openai, voyage and local, so
 * `EMBEDDING_MODEL=ollama/nomic-embed-text` - documented in the README - failed
 * with "Unknown model" at `Llm4sConfig.textEmbeddingModel()`. Ollama's own
 * dimensions are checked in `llm4s-ollama`, which now owns them, OpenAI's in
 * `llm4s-openai` and Voyage's in `llm4s-voyage` (#1132). The registry-lookup cases used
 * Voyage as their provider until it left core; they now use core's test
 * `FixtureEmbeddingProvider`, which `ProviderRegistry.default` discovers on core's test
 * classpath.
 */
class ModelDimensionRegistrySpec extends AnyWordSpec with Matchers with EitherValues {

  private given ProviderRegistry = ProviderRegistry.default

  "ModelDimensionRegistry" should {

    "know the models a registered provider declares" in {
      ModelDimensionRegistry
        .getDimension(ProviderId("fixtureembedding"), ModelName("fixture-embed-small"))
        .value shouldBe 256
    }

    "answer each of a provider's models with its own size" in {
      ModelDimensionRegistry
        .getDimension(ProviderId("fixtureembedding"), ModelName("fixture-embed-large"))
        .value shouldBe 1024
    }

    "resolve a provider alias and ignore the provider's case" in {
      ModelDimensionRegistry
        .getDimension(ProviderId(FixtureEmbeddingProvider.Alias), ModelName("fixture-embed-small"))
        .value shouldBe 256
      ModelDimensionRegistry
        .getDimension(ProviderId("FixtureEmbedding"), ModelName("fixture-embed-small"))
        .value shouldBe 256
    }

    "not answer for the local non-text encoders: `local` is a provider id like any other (#2805)" in {
      given ProviderRegistry = ProviderRegistry.ofEmbeddings()

      // Their table moved to llm4s-rag with its only consumer (`LocalEmbeddingModels.dimension`).
      ModelDimensionRegistry.getDimension(ProviderId("local"), ModelName("openclip-vit-b32")).isLeft shouldBe true
    }

    "answer for a registered provider called local as for any other" in {
      // An application may register a real embedding provider under the id `local`;
      // `EmbeddingClient.from("local", ...)` resolves it, so its dimensions must too.
      given ProviderRegistry = ProviderRegistry.ofEmbeddings(RegisteredLocalEmbeddings)

      // A model only the registered provider knows.
      ModelDimensionRegistry.getDimension(ProviderId("local"), ModelName("my-local-embedder")).value shouldBe 384
      // A name the local encoders also use: only the provider answers.
      ModelDimensionRegistry.getDimension(ProviderId("local"), ModelName("openclip-vit-b32")).value shouldBe 1024
      // And nothing stands in for the provider's own gaps.
      ModelDimensionRegistry
        .getDimension(ProviderId("local"), ModelName("wav2vec2-base"))
        .left
        .value
        .formatted should include(
        "Unknown model 'wav2vec2-base' for provider 'local'"
      )
    }

    "name the model and provider when a registered provider does not declare the model" in {
      ModelDimensionRegistry
        .getDimension(ProviderId("fixtureembedding"), ModelName("gpt-4o"))
        .left
        .value
        .formatted should include(
        "Unknown model 'gpt-4o' for provider 'fixtureembedding'"
      )
    }

    "report an unregistered provider with the registry's error" in {
      val error = ModelDimensionRegistry.getDimension(ProviderId("anthropic"), ModelName("claude")).left.value.formatted

      error should include("Embedding provider 'anthropic'")
      error should include("is not registered")
    }

    "answer from a provider registered only by the caller" in {
      given ProviderRegistry = ProviderRegistry.ofEmbeddings(FixtureEmbeddings)

      ModelDimensionRegistry.getDimension(ProviderId("fixturecloud"), ModelName("fixture-small")).value shouldBe 256
    }

    "not answer from a provider the caller's registry lacks" in {
      given ProviderRegistry = ProviderRegistry.ofEmbeddings(FixtureEmbeddings)

      ModelDimensionRegistry
        .getDimension(ProviderId("openai"), ModelName("text-embedding-3-small"))
        .left
        .value
        .formatted should include(
        "is not registered"
      )
    }
  }

  "every discovered embedding provider" should {
    "declare the dimensions of its default model" in {
      // A provider whose default model has no declared size fails
      // `textEmbeddingModel()` in its out-of-the-box configuration. This used to walk core's
      // built-in providers; core ships none now (#1132), so it walks whatever this classpath
      // discovers - in core, the test fixture; each provider module checks its own.
      ProviderRegistry.default.embeddingDescriptors should not be empty
      ProviderRegistry.default.embeddingDescriptors.foreach { descriptor =>
        descriptor.configSpec.defaultModel.foreach { model =>
          withClue(s"${descriptor.id.asString}/$model: ") {
            descriptor.dimensionsOf(model) shouldBe defined
          }
        }
        withClue(descriptor.id.asString) {
          descriptor.modelDimensions should not be empty
        }
      }
    }
  }

  "a descriptor that declares no dimensions" should {
    "know no model's size, and say so through the registry" in {
      given ProviderRegistry = ProviderRegistry.ofEmbeddings(UndeclaredEmbeddings)

      UndeclaredEmbeddings.modelDimensions shouldBe empty
      UndeclaredEmbeddings.dimensionsOf(ModelName("anything")) shouldBe None
      ModelDimensionRegistry
        .getDimension(ProviderId("undeclared"), ModelName("anything"))
        .left
        .value
        .formatted should include(
        "Unknown model 'anything' for provider 'undeclared'"
      )
    }
  }

  /** Overrides nothing dimension-related: the default a third-party provider gets. */
  private object UndeclaredEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId                                           = ProviderId("undeclared")
    val configSpec: org.llm4s.llmconnect.spi.EmbeddingConfigSpec = org.llm4s.llmconnect.spi.EmbeddingConfigSpec()

    def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }

  private object RegisteredLocalEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId                                           = ProviderId("local")
    val configSpec: org.llm4s.llmconnect.spi.EmbeddingConfigSpec = org.llm4s.llmconnect.spi.EmbeddingConfigSpec()

    override val modelDimensions: Map[ModelName, Int] =
      Map(ModelName("my-local-embedder") -> 384, ModelName("openclip-vit-b32") -> 1024)

    def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }

  private object FixtureEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId                                           = ProviderId("fixturecloud")
    val configSpec: org.llm4s.llmconnect.spi.EmbeddingConfigSpec = org.llm4s.llmconnect.spi.EmbeddingConfigSpec()

    override val modelDimensions: Map[ModelName, Int] = Map(ModelName("fixture-small") -> 256)

    def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }
}
