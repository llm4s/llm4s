package org.llm4s.model

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** The registry's source is set as documented, and `lookup` never returns another model (#2823). */
class ModelRegistryExactLookupSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def model(id: String, provider: String) =
    ModelMetadata(modelId = ModelName(id), provider = ProviderId(provider))

  private val registry = ModelRegistryService.fromModels(
    List(model("acme/model-a", "acme"), model("acme/model-a-pro", "acme"), model("gpt-4o", "openai"))
  )

  "ModelRegistryConfig.default.withFilePath" should "load that file" in:
    val file = Files.createTempFile("model-registry-", ".json")
    Files.writeString(file, """{ "file-model": { "litellm_provider": "custom", "mode": "chat" } }""")
    file.toFile.deleteOnExit()

    val service = ModelRegistryService.fromConfig(ModelRegistryConfig.default.withFilePath(file.toString)).value
    service.lookup(ModelName("file-model")).value.provider shouldBe ProviderId("custom")

  "lookup" should "not resolve an unknown id to the one model whose id contains it" in:
    registry.lookup(ModelName("model-a-p")).left.value shouldBe a[ValidationError]
    registry.lookup(ProviderId("openai"), ModelName("4o")).left.value shouldBe a[ValidationError]

  it should "still resolve exact ids, case and a provider prefix" in:
    registry.lookup(ModelName("acme/model-a")).value.modelId shouldBe ModelName("acme/model-a")
    registry.lookup(ModelName("ACME/MODEL-A-PRO")).value.modelId shouldBe ModelName("acme/model-a-pro")
    registry.lookup(ModelName("openai/gpt-4o")).value.modelId shouldBe ModelName("gpt-4o")
    registry.lookup(ProviderId("openai"), ModelName("gpt-4o")).value.modelId shouldBe ModelName("gpt-4o")

  "ModelMetadata" should "read a deprecation date as a LocalDate, and refuse one that is not a date" in:
    val dated = ModelMetadata.fromJson(ModelName("m"), ujson.Obj("deprecation_date" -> "2000-01-01")).value
    dated.deprecationDate shouldBe Some(java.time.LocalDate.of(2000, 1, 1))
    dated.isDeprecated shouldBe true
    dated.isDeprecatedOn(java.time.LocalDate.of(1999, 12, 31)) shouldBe false
    ModelMetadata.fromJson(ModelName("m"), ujson.Obj("deprecation_date" -> "soon")).isLeft shouldBe true

  it should "default everything but the id and provider" in:
    val m = ModelMetadata(ModelName("m"), ProviderId("p"))
    (m.mode, m.contextWindow, m.pricing, m.deprecationDate) shouldBe ((ModelMode.Chat, None, ModelPricing(), None))

  "search" should "return every model whose id contains the query, ignoring case" in:
    registry.search("MODEL-A").map(_.modelId) shouldBe List("acme/model-a", "acme/model-a-pro")
    registry.search("nothing") shouldBe Nil
