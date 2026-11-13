package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.JlamaConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Paths

/**
 * [[JlamaConfig]]: its validation, and the default model directory under the user's home - a path built with the
 * platform's separators, so this suite runs on Windows too (`PlatformTests.suites`).
 */
class JlamaConfigSpec extends AnyWordSpec with Matchers:

  "JlamaConfig.fromValues" should {

    "accept a Hugging Face id, trimmed, with the defaults" in {
      val config = JlamaConfig.fromValues("  tjake/Llama-3.2-1B-Instruct-JQ4 ").fold(e => fail(e.message), identity)
      config.model shouldBe "tjake/Llama-3.2-1B-Instruct-JQ4"
      config.providerId.asString shouldBe "jlama"
      config.modelDir shouldBe JlamaConfig.defaultModelDir
      config.threads shouldBe None
      config.contextWindow shouldBe JlamaConfig.DEFAULT_CONTEXT_WINDOW
      config.reserveCompletion shouldBe JlamaConfig.DEFAULT_RESERVE_COMPLETION
    }

    "refuse a model that is not owner/name" in {
      Seq("", "llama3", "/name", "owner/", "a/b/c").foreach { model =>
        JlamaConfig.fromValues(model).isLeft shouldBe true
      }
    }

    "refuse a thread count below 1" in {
      JlamaConfig.fromValues("tjake/x", threads = Some(0)).isLeft shouldBe true
      JlamaConfig.fromValues("tjake/x", threads = Some(3)).map(_.threads) shouldBe Right(Some(3))
    }
  }

  "a JlamaConfig" should {

    "change its model, directory and threads through its setters" in {
      val config = JlamaConfig.fromValues("tjake/x").fold(e => fail(e.message), identity)
      val dir    = Paths.get("elsewhere")
      config.withModel("tjake/y").model shouldBe "tjake/y"
      config.withModelDir(dir).modelDir shouldBe dir
      config.withThreads(4).threads shouldBe Some(4)
      config.withThreads(None).threads shouldBe None
    }
  }

  "JlamaConfig.defaultModelDir" should {

    "be .cache/llm4s/jlama under the user's home" in {
      val home = Paths.get(System.getProperty("user.home"))
      JlamaConfig.defaultModelDir shouldBe home.resolve(".cache").resolve("llm4s").resolve("jlama")
      JlamaConfig.defaultModelDir.startsWith(home) shouldBe true
    }
  }
