package org.llm4s.shared

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorkspaceSandboxConfigSpec extends AnyFlatSpec with Matchers {

  "WorkspaceSandboxConfig" should "validate Permissive config" in {
    WorkspaceSandboxConfig.validate(WorkspaceSandboxConfig.Permissive) shouldBe Right(())
  }

  it should "validate LockedDown config" in {
    WorkspaceSandboxConfig.validate(WorkspaceSandboxConfig.LockedDown) shouldBe Right(())
  }

  it should "reject invalid maxFileSize" in {
    val bad = WorkspaceSandboxConfig.Permissive.copy(
      limits = WorkspaceLimits(maxFileSize = 0, 500, 100, 1048576)
    )
    WorkspaceSandboxConfig.validate(bad) shouldBe Left("limits.maxFileSize must be positive")
  }

  it should "reject invalid defaultCommandTimeoutSeconds" in {
    val bad = WorkspaceSandboxConfig.Permissive.copy(defaultCommandTimeoutSeconds = 0)
    WorkspaceSandboxConfig.validate(bad) shouldBe Left("defaultCommandTimeoutSeconds must be positive")
  }

  it should "have LockedDown with shellAllowed=false" in {
    WorkspaceSandboxConfig.LockedDown.shellAllowed shouldBe false
  }

  it should "have Permissive with shellAllowed=true" in {
    WorkspaceSandboxConfig.Permissive.shellAllowed shouldBe true
  }

  it should "parse known profile names" in {
    WorkspaceSandboxConfig.fromProfileName("permissive") shouldBe Right(WorkspaceSandboxConfig.Permissive)
    WorkspaceSandboxConfig.fromProfileName("") shouldBe Right(WorkspaceSandboxConfig.Permissive)
    WorkspaceSandboxConfig.fromProfileName("locked") shouldBe Right(WorkspaceSandboxConfig.LockedDown)
    WorkspaceSandboxConfig.fromProfileName("locked-down") shouldBe Right(WorkspaceSandboxConfig.LockedDown)
  }

  it should "reject unknown profile names" in {
    WorkspaceSandboxConfig.fromProfileName("strict") shouldBe Left("Unknown sandbox profile: 'strict'")
    WorkspaceSandboxConfig.fromProfileName("unknown-profile") shouldBe Left(
      "Unknown sandbox profile: 'unknown-profile'"
    )
  }
}
