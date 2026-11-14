package org.llm4s.samples.sidecar

import org.llm4s.config.{ Llm4sConfig, ReferenceConfig }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * What `docs/guide/replacing-a-python-sidecar.md` shows, checked against the code.
 *
 * The page's `application.conf` for vLLM, TGI and llama.cpp is read from the page itself and loaded as a user's would
 * be, with every `reference.conf` on the classpath and no environment: each section must resolve to the generic
 * OpenAI-compatible provider with the `baseUrl`, model and context window the page's table describes, and build a
 * client without sending a request. The page's Python listing must be the committed `sentiment_tool.py`, so the
 * script the sample and `PythonToolBridgeExampleSpec` run is the one readers copy.
 */
class ReplacingAPythonSidecarGuideSpec extends AnyFlatSpec with Matchers {

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")) && Files.isDirectory(dir.resolve("docs")))
      .getOrElse(throw new IllegalStateException("the repository root was not found above the working directory"))

  // A checkout with CRLF line endings must not make the comparisons below fail.
  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private val page = read(root.resolve("docs").resolve("guide").resolve("replacing-a-python-sidecar.md"))

  /** The page's fenced blocks in a language, in order. */
  private def blocks(language: String): Seq[String] =
    s"(?s)```$language\n(.*?)\n```".r.findAllMatchIn(page).map(_.group(1)).toSeq

  private val applicationConf: String =
    blocks("hocon").find(_.contains("python-vllm")).getOrElse(fail("the page has no application.conf for python-vllm"))

  // No environment variables; only `user.home`, which termflow's `reference.conf` (on the samples' classpath) needs.
  private val source: ConfigSource =
    ReferenceConfig.withEnv(applicationConf, Map("user.home" -> sys.props.getOrElse("user.home", ".")))

  private def compatible(name: String): OpenAICompatibleConfig =
    Llm4sConfig.provider(source, name) match {
      case Right(config: OpenAICompatibleConfig) => config
      case other                                 => fail(s"$name: expected an OpenAICompatibleConfig, got $other")
    }

  "The page's application.conf" should "load each Python-served model as the generic OpenAI-compatible provider" in {
    val expected = Seq(
      ("python-vllm", "http://localhost:8000/v1", "Qwen/Qwen2.5-7B-Instruct", 32768),
      ("python-tgi", "http://localhost:8080/v1", "tgi", 8192),
      ("python-llamacpp", "http://localhost:9931/v1", "local-model", 16384)
    )
    expected.foreach { case (name, baseUrl, model, window) =>
      val config = compatible(name)
      withClue(s"$name: ") {
        config.baseUrl shouldBe baseUrl
        config.model shouldBe model
        config.contextWindow shouldBe window
        config.apiKey shouldBe None
        config.streamUsage shouldBe true
      }
    }
  }

  it should "make python-vllm the default" in {
    source.at("llm4s.providers.provider").load[String] shouldBe Right("python-vllm")
  }

  it should "build a client for each section, with the section's context window, without sending a request" in {
    Seq("python-vllm" -> 32768, "python-tgi" -> 8192, "python-llamacpp" -> 16384).foreach { case (name, window) =>
      val client =
        for {
          config   <- Llm4sConfig.provider(source, name)
          registry <- Llm4sConfig.modelRegistryService()
          given ModelRegistryService = registry
          client <- LLMConnect.getClient(config)
        } yield client
      client.map(_.getContextWindow()) shouldBe Right(window)
    }
  }

  "The page's Python listing" should "be the committed sentiment_tool.py, after its module docstring" in {
    val listing = blocks("python").headOption.getOrElse(fail("the page has no Python listing"))
    val script  = read(root.resolve("modules/samples/src/main/python/sentiment_tool.py"))
    val code    = script.substring(script.indexOf("\"\"\"", 3) + 3).trim
    listing.trim shouldBe code
  }

  it should "pin the SDK version the page names" in {
    val requirements = read(root.resolve("modules/samples/src/main/python/requirements.txt"))
    requirements.linesIterator.map(_.trim).filterNot(l => l.isEmpty || l.startsWith("#")).toSeq shouldBe Seq(
      "mcp==2.3.0"
    )
    page should include("`mcp==2.3.0`")
  }
}
