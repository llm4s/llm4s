package org.llm4s.llmconnect.spi

import org.llm4s.error.ConfigurationError
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `EmbeddingConfigSpec`'s resolvers and setters, called directly. The config loaders only reach them
 * through whole config loads, which leaves the precedence order and the blank-value handling unchecked.
 */
class EmbeddingConfigSpecSpec extends AnyWordSpec with Matchers with EitherValues {

  private val id = ProviderId("acme")

  private val noSection = EmbeddingProviderSection()

  /** A spec with no field at its default, so a setter that resets another field shows up. */
  private val full = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://default.acme.example"),
    defaultModel = Some("default-model"),
    defaultApiKey = Some("default-acme-key"),
    apiKeyEnv = Seq("ACME_API_KEY"),
    modelEnv = Some("ACME_MODEL")
  )

  // ==========================================================================
  // resolveModel
  // ==========================================================================

  "resolveModel" should {

    val withDefault = EmbeddingConfigSpec(defaultModel = Some("default-model"))
    val section     = EmbeddingProviderSection(model = Some(ModelName("section-model")))

    "prefer the override to the section and the default" in {
      EmbeddingConfigSpec.resolveModel(id, section, Some("override-model"), withDefault) shouldBe
        Right(ModelName("override-model"))
    }

    "prefer the section to the default" in {
      EmbeddingConfigSpec.resolveModel(id, section, None, withDefault) shouldBe Right(ModelName("section-model"))
    }

    "use the default when there is no override and no section model" in {
      EmbeddingConfigSpec.resolveModel(id, noSection, None, withDefault) shouldBe Right(ModelName("default-model"))
    }

    "skip a blank or whitespace-only override" in {
      EmbeddingConfigSpec.resolveModel(id, section, Some(""), withDefault) shouldBe Right(ModelName("section-model"))
      EmbeddingConfigSpec.resolveModel(id, section, Some("   "), withDefault) shouldBe Right(ModelName("section-model"))
    }

    "skip a blank or whitespace-only section model" in {
      val blank      = EmbeddingProviderSection(model = Some(ModelName("")))
      val whitespace = EmbeddingProviderSection(model = Some(ModelName(" \t ")))
      EmbeddingConfigSpec.resolveModel(id, blank, None, withDefault) shouldBe Right(ModelName("default-model"))
      EmbeddingConfigSpec.resolveModel(id, whitespace, None, withDefault) shouldBe Right(ModelName("default-model"))
    }

    "trim the override and the section model" in {
      EmbeddingConfigSpec.resolveModel(id, noSection, Some("  override-model "), withDefault) shouldBe
        Right(ModelName("override-model"))
      val padded = EmbeddingProviderSection(model = Some(ModelName(" section-model ")))
      EmbeddingConfigSpec.resolveModel(id, padded, None, withDefault) shouldBe Right(ModelName("section-model"))
    }

    "name EMBEDDING_MODEL and the section path when nothing is set" in {
      val error = EmbeddingConfigSpec.resolveModel(id, noSection, None, EmbeddingConfigSpec()).left.value
      error shouldBe a[ConfigurationError]
      error.message should include("EMBEDDING_MODEL=acme/<model>")
      error.message should endWith("llm4s.embeddings.acme.model)")
    }

    "also name modelEnv when the spec sets one" in {
      val spec    = EmbeddingConfigSpec(modelEnv = Some("ACME_MODEL"))
      val message = EmbeddingConfigSpec.resolveModel(id, noSection, None, spec).left.value.message
      message should include("EMBEDDING_MODEL=acme/<model>")
      message should include("llm4s.embeddings.acme.model or ACME_MODEL")
    }
  }

  // ==========================================================================
  // resolveBaseUrl
  // ==========================================================================

  "resolveBaseUrl" should {

    val withDefault = EmbeddingConfigSpec(defaultBaseUrl = Some("https://default.acme.example"))

    "prefer the section to the default" in {
      val section = EmbeddingProviderSection(baseUrl = Some(BaseUrl("https://section.acme.example")))
      EmbeddingConfigSpec.resolveBaseUrl(id, section, withDefault) shouldBe Right(
        BaseUrl("https://section.acme.example")
      )
    }

    "use the default when the section has none" in {
      EmbeddingConfigSpec.resolveBaseUrl(id, noSection, withDefault) shouldBe Right(
        BaseUrl("https://default.acme.example")
      )
    }

    "use the default when the section's is blank" in {
      val blank = EmbeddingProviderSection(baseUrl = Some(BaseUrl("  ")))
      EmbeddingConfigSpec.resolveBaseUrl(id, blank, withDefault) shouldBe Right(BaseUrl("https://default.acme.example"))
    }

    "name the baseUrl path when there is neither" in {
      EmbeddingConfigSpec.resolveBaseUrl(id, noSection, EmbeddingConfigSpec()).left.value.message should include(
        "llm4s.embeddings.acme.baseUrl"
      )
    }
  }

  // ==========================================================================
  // resolveApiKey
  // ==========================================================================

  "resolveApiKey" should {

    val withDefault = EmbeddingConfigSpec(requiresApiKey = true, defaultApiKey = Some("default-acme-key"))

    "prefer the section's key to the default, trimmed" in {
      val section = EmbeddingProviderSection(apiKey = Some(Secret("  section-acme-key ")))
      EmbeddingConfigSpec.resolveApiKey(id, section, withDefault).value.map(_.reveal) shouldBe Some("section-acme-key")
    }

    "fall through a blank section key to the default" in {
      val blank = EmbeddingProviderSection(apiKey = Some(Secret("   ")))
      EmbeddingConfigSpec.resolveApiKey(id, blank, withDefault).value.map(_.reveal) shouldBe Some("default-acme-key")
      EmbeddingConfigSpec.resolveApiKey(id, noSection, withDefault).value.map(_.reveal) shouldBe Some(
        "default-acme-key"
      )
    }

    "give no key when there is none and none is required" in {
      EmbeddingConfigSpec.resolveApiKey(id, noSection, EmbeddingConfigSpec()).value shouldBe None
    }

    "name the declared variable when a required key is missing" in {
      val spec    = EmbeddingConfigSpec(requiresApiKey = true, apiKeyEnv = Seq("ACME_API_KEY"))
      val message = EmbeddingConfigSpec.resolveApiKey(id, noSection, spec).left.value.message
      message should include("set ACME_API_KEY,")
      message should include("llm4s.embeddings.acme")
      (message should not).include("llm4s.credentials.acme.apiKey")
    }

    "name every declared variable when there are several" in {
      val spec    = EmbeddingConfigSpec(requiresApiKey = true, apiKeyEnv = Seq("ACME_API_KEY", "ACME_KEY"))
      val message = EmbeddingConfigSpec.resolveApiKey(id, noSection, spec).left.value.message
      message should include("set ACME_API_KEY or ACME_KEY")
      message should include("llm4s.embeddings.acme")
    }

    "name the shared credentials path when no variable is declared" in {
      val spec  = EmbeddingConfigSpec(requiresApiKey = true)
      val error = EmbeddingConfigSpec.resolveApiKey(id, noSection, spec).left.value
      error shouldBe a[ConfigurationError]
      val message = error.message
      message should include("set llm4s.credentials.acme.apiKey,")
      message should include("llm4s.embeddings.acme")
    }
  }

  // ==========================================================================
  // Defaults and setters
  // ==========================================================================

  "EmbeddingConfigSpec()" should {
    "require no key and declare nothing" in {
      val spec = EmbeddingConfigSpec()
      spec.requiresApiKey shouldBe false
      spec.defaultBaseUrl shouldBe None
      spec.defaultModel shouldBe None
      spec.defaultApiKey shouldBe None
      spec.apiKeyEnv shouldBe empty
      spec.modelEnv shouldBe None
    }
  }

  "EmbeddingConfigSpec setters" should {

    "set requiresApiKey" in {
      EmbeddingConfigSpec().withRequiresApiKey(true) shouldBe EmbeddingConfigSpec(requiresApiKey = true)
      full.withRequiresApiKey(false) shouldBe EmbeddingConfigSpec(
        requiresApiKey = false,
        defaultBaseUrl = full.defaultBaseUrl,
        defaultModel = full.defaultModel,
        defaultApiKey = full.defaultApiKey,
        apiKeyEnv = full.apiKeyEnv,
        modelEnv = full.modelEnv
      )
    }

    "set and clear defaultBaseUrl" in {
      full.withDefaultBaseUrl("https://other.example") shouldBe EmbeddingConfigSpec(
        requiresApiKey = true,
        defaultBaseUrl = Some("https://other.example"),
        defaultModel = full.defaultModel,
        defaultApiKey = full.defaultApiKey,
        apiKeyEnv = full.apiKeyEnv,
        modelEnv = full.modelEnv
      )
      full.withDefaultBaseUrl(Some("https://other.example")) shouldBe full.withDefaultBaseUrl("https://other.example")
      full.withDefaultBaseUrl(None).defaultBaseUrl shouldBe None
      full.withDefaultBaseUrl(None).withDefaultBaseUrl(full.defaultBaseUrl) shouldBe full
    }

    "set and clear defaultModel" in {
      full.withDefaultModel("other-model") shouldBe EmbeddingConfigSpec(
        requiresApiKey = true,
        defaultBaseUrl = full.defaultBaseUrl,
        defaultModel = Some("other-model"),
        defaultApiKey = full.defaultApiKey,
        apiKeyEnv = full.apiKeyEnv,
        modelEnv = full.modelEnv
      )
      full.withDefaultModel(Some("other-model")) shouldBe full.withDefaultModel("other-model")
      full.withDefaultModel(None).defaultModel shouldBe None
      full.withDefaultModel(None).withDefaultModel(full.defaultModel) shouldBe full
    }

    "set and clear defaultApiKey" in {
      full.withDefaultApiKey("other-acme-key") shouldBe EmbeddingConfigSpec(
        requiresApiKey = true,
        defaultBaseUrl = full.defaultBaseUrl,
        defaultModel = full.defaultModel,
        defaultApiKey = Some("other-acme-key"),
        apiKeyEnv = full.apiKeyEnv,
        modelEnv = full.modelEnv
      )
      full.withDefaultApiKey(Some("other-acme-key")) shouldBe full.withDefaultApiKey("other-acme-key")
      full.withDefaultApiKey(None).defaultApiKey shouldBe None
      full.withDefaultApiKey(None).withDefaultApiKey(full.defaultApiKey) shouldBe full
    }

    "set apiKeyEnv" in {
      full.withApiKeyEnv(Seq("A", "B")) shouldBe EmbeddingConfigSpec(
        requiresApiKey = true,
        defaultBaseUrl = full.defaultBaseUrl,
        defaultModel = full.defaultModel,
        defaultApiKey = full.defaultApiKey,
        apiKeyEnv = Seq("A", "B"),
        modelEnv = full.modelEnv
      )
      full.withApiKeyEnv(Seq.empty).apiKeyEnv shouldBe empty
    }

    "set and clear modelEnv" in {
      full.withModelEnv("OTHER_MODEL") shouldBe EmbeddingConfigSpec(
        requiresApiKey = true,
        defaultBaseUrl = full.defaultBaseUrl,
        defaultModel = full.defaultModel,
        defaultApiKey = full.defaultApiKey,
        apiKeyEnv = full.apiKeyEnv,
        modelEnv = Some("OTHER_MODEL")
      )
      full.withModelEnv(Some("OTHER_MODEL")) shouldBe full.withModelEnv("OTHER_MODEL")
      full.withModelEnv(None).modelEnv shouldBe None
      full.withModelEnv(None).withModelEnv(full.modelEnv) shouldBe full
    }
  }
}
