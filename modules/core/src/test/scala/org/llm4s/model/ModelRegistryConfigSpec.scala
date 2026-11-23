package org.llm4s.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** `ModelRegistryConfig` names exactly one source (#2823), and is a growth-prone type (#2346). */
class ModelRegistryConfigSpec extends AnyFunSuite with Matchers {

  test("ModelRegistryConfig.default loads the bundled resource") {
    ModelRegistryConfig.default shouldBe ModelRegistryConfig()
    ModelRegistryConfig.default.source shouldBe ModelRegistrySource.Resource(ModelRegistryConfig.DefaultResourcePath)
  }

  test("each setter replaces the source, so a config never names two") {
    ModelRegistryConfig.default.withResourcePath("/other.json").source shouldBe
      ModelRegistrySource.Resource("/other.json")
    ModelRegistryConfig.default.withFilePath("/tmp/models.json").source shouldBe
      ModelRegistrySource.File("/tmp/models.json")
    ModelRegistryConfig.default.withUrl("https://example.invalid/models.json").source shouldBe
      ModelRegistrySource.Url("https://example.invalid/models.json")
    ModelRegistryConfig.default.withFilePath("/f.json").withUrl("https://u.invalid") shouldBe
      ModelRegistryConfig(ModelRegistrySource.Url("https://u.invalid"))
    ModelRegistryConfig.default.withSource(ModelRegistrySource.File("/f.json")) shouldBe
      ModelRegistryConfig.default.withFilePath("/f.json")
  }

  test("a blank source is refused when the registry is loaded") {
    ModelRegistryService.fromConfig(ModelRegistryConfig.default.withFilePath("  ")).isLeft shouldBe true
  }

  test("ModelRegistryConfig keeps its constructor and copy private") {
    assertTypeError("new ModelRegistryConfig(ModelRegistrySource.Url(\"u\"))")
    assertTypeError("ModelRegistryConfig.default.copy(source = ModelRegistrySource.Url(\"u\"))")
  }
}
