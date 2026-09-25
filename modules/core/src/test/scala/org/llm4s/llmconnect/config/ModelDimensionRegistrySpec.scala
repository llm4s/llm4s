package org.llm4s.llmconnect.config

import org.llm4s.llmconnect.provider.{
  BuiltinProviders,
  EmbeddingProvider,
  OpenAIEmbeddingProvider,
  VoyageAIEmbeddingProvider
}
import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, ProviderRegistry }
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
 * dimensions are checked in `llm4s-ollama`, which now owns them.
 */
class ModelDimensionRegistrySpec extends AnyWordSpec with Matchers with EitherValues {

  private given ProviderRegistry = ProviderRegistry.builtin

  "ModelDimensionRegistry" should {

    "know the documented OpenAI and Voyage models" in {
      ModelDimensionRegistry.getDimension("openai", "text-embedding-3-small").value shouldBe 1536
      ModelDimensionRegistry.getDimension("openai", "text-embedding-3-large").value shouldBe 3072
      ModelDimensionRegistry.getDimension("voyage", "voyage-3").value shouldBe 1024
    }

    "give voyage-3-large its default size, not 1536" in {
      ModelDimensionRegistry.getDimension("voyage", "voyage-3-large").value shouldBe 1024
    }

    "resolve a provider alias and ignore the provider's case" in {
      ModelDimensionRegistry.getDimension("voyageai", "voyage-3").value shouldBe 1024
      ModelDimensionRegistry.getDimension("OpenAI", "text-embedding-3-small").value shouldBe 1536
    }

    "answer the local non-text encoders without a registered provider" in {
      given ProviderRegistry = ProviderRegistry.ofEmbeddings()

      ModelDimensionRegistry.getDimension("local", "openclip-vit-b32").value shouldBe 512
      ModelDimensionRegistry.localDimension("wav2vec2-base").value shouldBe 768
      ModelDimensionRegistry.localDimension("no-such-encoder").left.value.formatted should include(
        "Unknown model 'no-such-encoder' for provider 'local'"
      )
    }

    "name the model and provider when a registered provider does not declare the model" in {
      ModelDimensionRegistry.getDimension("openai", "gpt-4o").left.value.formatted should include(
        "Unknown model 'gpt-4o' for provider 'openai'"
      )
    }

    "report an unregistered provider with the registry's error" in {
      val error = ModelDimensionRegistry.getDimension("anthropic", "claude").left.value.formatted

      error should include("Embedding provider 'anthropic'")
      error should include("is not registered")
    }

    "answer from a provider registered only by the caller" in {
      given ProviderRegistry = ProviderRegistry.ofEmbeddings(FixtureEmbeddings)

      ModelDimensionRegistry.getDimension("fixturecloud", "fixture-small").value shouldBe 256
    }

    "not answer from a provider the caller's registry lacks" in {
      given ProviderRegistry = ProviderRegistry.ofEmbeddings(FixtureEmbeddings)

      ModelDimensionRegistry.getDimension("openai", "text-embedding-3-small").left.value.formatted should include(
        "is not registered"
      )
    }
  }

  "every built-in embedding provider" should {
    "declare the dimensions of its default model" in {
      // A provider whose default model has no declared size fails
      // `textEmbeddingModel()` in its out-of-the-box configuration.
      BuiltinProviders.embeddingProviders.foreach { descriptor =>
        descriptor.configSpec.defaultModel.foreach { model =>
          withClue(s"${descriptor.id.asString}/$model: ") {
            descriptor.dimensionsOf(model) shouldBe defined
          }
        }
      }
      Seq(OpenAIEmbeddingProvider, VoyageAIEmbeddingProvider).foreach { descriptor =>
        withClue(descriptor.id.asString) {
          descriptor.modelDimensions should not be empty
        }
      }
    }
  }

  private object FixtureEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId = ProviderId("fixturecloud")

    override val modelDimensions: Map[String, Int] = Map("fixture-small" -> 256)

    def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }
}
