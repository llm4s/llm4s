package org.llm4s.llmconnect.provider

import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.config.JlamaConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

/**
 * [[JlamaEngine.load]] on the paths that need no network and no real model: an id Jlama refuses before downloading,
 * and a model directory Jlama takes as already downloaded but cannot load. Both must come back as a `Left`, never a
 * throw. Loading and running a real model is the nightly sample's (`jlama-nightly.yml`).
 */
class JlamaEngineSpec extends AnyWordSpec with Matchers:

  private def config(model: String, dir: Path): JlamaConfig =
    JlamaConfig
      .fromValues("tjake/Llama-3.2-1B-Instruct-JQ4")
      .fold(e => fail(e.message), identity)
      .withModel(model)
      .withModelDir(dir)
      .withThreads(1)

  "JlamaEngine.load" should {

    "return a ProcessingError naming the model for an id Jlama refuses, without downloading anything" in {
      val dir    = Files.createTempDirectory("jlama-engine-spec")
      val result = JlamaEngine.load(config("owner/name/extra", dir))
      result.isLeft shouldBe true
      val error = result.left.toOption.get
      error shouldBe a[ProcessingError]
      error.message should include("owner/name/extra")
      error.message should include("Model must be in the form owner/name")
      Files.list(dir).count() shouldBe 0L
    }

    "return a ProcessingError for a model directory that is marked downloaded but holds no loadable model" in {
      val dir   = Files.createTempDirectory("jlama-engine-spec")
      val local = Files.createDirectories(dir.resolve("owner_broken"))
      Files.write(local.resolve(".finished"), Array.emptyByteArray)
      Files.write(local.resolve("model.safetensors"), "not a model".getBytes(StandardCharsets.UTF_8))
      val result = JlamaEngine.load(config("owner/broken", dir))
      result.isLeft shouldBe true
      val error = result.left.toOption.get
      error shouldBe a[ProcessingError]
      error.message should include("could not load Jlama model owner/broken")
    }
  }

  "JlamaClient.apply" should {
    "build a client without loading the model" in {
      val dir = Files.createTempDirectory("jlama-engine-spec")
      JlamaClient(config("owner/never-loaded", dir)).isRight shouldBe true
      Files.list(dir).count() shouldBe 0L
    }
  }
