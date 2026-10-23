package org.llm4s.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** `ModelRegistryConfig` as a growth-prone type (#2346). */
class ModelRegistryConfigSpec extends AnyFunSuite with Matchers {

  test("ModelRegistryConfig.default loads the bundled resource and nothing else") {
    ModelRegistryConfig.default shouldBe ModelRegistryConfig()
    ModelRegistryConfig.default.resourcePath shouldBe Some(ModelRegistryConfig.DefaultResourcePath)
    ModelRegistryConfig.default.filePath shouldBe None
    ModelRegistryConfig.default.url shouldBe None
  }

  test("ModelRegistryConfig's setters each change one field") {
    val adjusted = ModelRegistryConfig.default
      .withResourcePath("/other.json")
      .withFilePath("/tmp/models.json")
      .withUrl("https://example.invalid/models.json")

    adjusted shouldBe ModelRegistryConfig(
      resourcePath = Some("/other.json"),
      filePath = Some("/tmp/models.json"),
      url = Some("https://example.invalid/models.json")
    )
  }

  test("ModelRegistryConfig's Option setters set or clear a field") {
    val all = ModelRegistryConfig(Some("/r.json"), Some("/f.json"), Some("https://u.invalid"))

    all.withResourcePath(None).resourcePath shouldBe None
    all.withFilePath(None).filePath shouldBe None
    all.withUrl(None).url shouldBe None
    ModelRegistryConfig(resourcePath = None).withResourcePath(Some("/r.json")).resourcePath shouldBe Some("/r.json")
    ModelRegistryConfig.default.withFilePath(Some("/f.json")).filePath shouldBe Some("/f.json")
    ModelRegistryConfig.default.withUrl(Some("https://u.invalid")).url shouldBe Some("https://u.invalid")
  }

  test("ModelRegistryConfig keeps its constructor and copy private") {
    assertTypeError("new ModelRegistryConfig(None, None, None)")
    assertTypeError("ModelRegistryConfig.default.copy(url = None)")
  }
}
