package org.llm4s.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnvLoaderTest extends AnyFlatSpec with Matchers {

  "EnvLoader" should "load values from .env file" in {
    // Test loading LLM_MODEL which should exist in .env
    val llmModel = EnvLoader.get("LLM_MODEL")
    llmModel should be(defined)
    llmModel.get should not be empty
  }

  it should "return None for non-existent variables" in {
    val nonExistent = EnvLoader.get("NON_EXISTENT_VAR_12345")
    nonExistent should be(None)
  }

  it should "provide getOrElse functionality" in {
    val llmModel = EnvLoader.getOrElse("LLM_MODEL", "default-model")
    (llmModel should not).equal("default-model")

    val nonExistent = EnvLoader.getOrElse("NON_EXISTENT_VAR_12345", "default-value")
    nonExistent should equal("default-value")
  }
}
