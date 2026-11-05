package org.llm4s.javaapi

import org.llm4s.error.ConfigurationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ URL, URLClassLoader }

/**
 * The provider modules are optional dependencies of `llm4s-java-api` (#1496): a builder or starter provider whose
 * module is absent fails with a `ConfigurationError` naming the artifact to add.
 */
class ProviderModulesSpec extends AnyFlatSpec with Matchers {

  private val allModules = Seq(
    ProviderModules.OpenAI,
    ProviderModules.Anthropic,
    ProviderModules.Gemini,
    ProviderModules.Ollama,
    ProviderModules.OpenAICompatible
  )

  /** A loader that sees only the JDK, as llm4s does when no provider module was declared beside it. */
  private def jdkOnly: ClassLoader = new URLClassLoader(Array.empty[URL], ClassLoader.getPlatformClassLoader)

  "ProviderModules.require" should "find every provider module java-api compiles against on this classpath" in {
    allModules.foreach(m => ProviderModules.require(m, "test") shouldBe Right(()))
  }

  it should "name the artifact to add when the module is absent" in {
    val Left(error: ConfigurationError) =
      ProviderModules.require(ProviderModules.Ollama, "OllamaClientBuilder", jdkOnly): @unchecked
    error.message shouldBe "OllamaClientBuilder needs llm4s-ollama, which is not on the classpath: add the dependency " +
      "org.llm4s:llm4s-ollama_3 at the same version as llm4s-java-api"
  }

  it should "mark each module by a class that module alone contains" in {
    allModules.map(_.marker).distinct should have size allModules.size.toLong
    allModules.foreach(m => m.marker should startWith("org.llm4s.llmconnect.provider.Llm4s"))
  }

  "ProviderModules.guard" should "evaluate the body when the module is present" in {
    ProviderModules.guard(ProviderModules.OpenAI, "test")(LlmResult.success("built")).get() shouldBe "built"
  }

  it should "fail without evaluating the body when the module is absent" in {
    var evaluated = false
    val result = ProviderModules.guard(ProviderModules.Anthropic, "llm4s.provider=anthropic", jdkOnly) {
      evaluated = true
      LlmResult.success("built")
    }
    evaluated shouldBe false
    result.isFailure shouldBe true
    result.getError().getMessage should include("llm4s.provider=anthropic needs llm4s-anthropic")
  }

  "The Java guide's dependency blocks" should "declare a provider module beside llm4s-java-api" in {
    val guide = GuideDocs.read("docs/guide/java.md", "docs/guide/java.md")
    GuideDocs.blocksIn(guide, "xml").mkString should include("<artifactId>llm4s-ollama_3</artifactId>")
    GuideDocs.blocksIn(guide, "kotlin").mkString should include("org.llm4s:llm4s-ollama_3:")
    GuideDocs.blocksIn(guide, "scala").mkString should include(""""org.llm4s" %% "llm4s-ollama"""")
  }
}
