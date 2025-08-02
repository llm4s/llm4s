package org.llm4s.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnvLoaderTest extends AnyFlatSpec with Matchers {

  "EnvLoader" should "return None for non-existent variables" in {
    // Use a variable name that's very unlikely to exist
    val nonExistent = EnvLoader.get("NON_EXISTENT_VAR_12345_TEST")
    nonExistent should be(None)
  }

  it should "provide getOrElse functionality with default values" in {
    // Test with a non-existent variable to ensure default is returned
    val nonExistent = EnvLoader.getOrElse("NON_EXISTENT_VAR_12345_TEST", "default-value")
    nonExistent should equal("default-value")
  }

  it should "load system environment variables when .env file doesn't have them" in {
    // PATH should always exist as a system environment variable
    val pathVar = EnvLoader.get("PATH")
    pathVar should be(defined)
  }

  it should "handle getOrElse with existing system variables" in {
    // PATH should exist, so default should not be used
    val pathVar = EnvLoader.getOrElse("PATH", "default-path")
    (pathVar should not).equal("default-path")
  }
}
