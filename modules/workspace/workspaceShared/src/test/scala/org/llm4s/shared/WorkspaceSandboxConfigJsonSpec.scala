package org.llm4s.shared

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorkspaceSandboxConfigJsonSpec extends AnyFlatSpec with Matchers {

  "WorkspaceSandboxConfig.fromJson" should "read what upickle writes" in {
    val cfg = WorkspaceSandboxConfig.Permissive.copy(
      readOnlyPaths = List("ro"),
      allowedPaths = List("src", "ro"),
      networkAllowed = true
    )
    WorkspaceSandboxConfig.fromJson(upickle.default.write(cfg)) shouldBe Right(cfg)
  }

  it should "fill missing keys from the defaults" in {
    WorkspaceSandboxConfig.fromJson("""{"networkAllowed":true}""").map(_.networkAllowed) shouldBe Right(true)
  }

  it should "refuse malformed JSON, naming the variable" in {
    val left = WorkspaceSandboxConfig.fromJson("{not json").swap.getOrElse("")
    left should include(WorkspaceSandboxConfig.ConfigEnvVar)
  }

  it should "refuse a config validate refuses" in {
    WorkspaceSandboxConfig.fromJson("""{"defaultCommandTimeoutSeconds":0}""").isLeft shouldBe true
  }
}
