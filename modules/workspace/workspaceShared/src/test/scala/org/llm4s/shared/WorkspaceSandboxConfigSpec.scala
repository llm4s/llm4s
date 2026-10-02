package org.llm4s.shared

import scala.concurrent.duration.*

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

  it should "keep the defaultCommandTimeoutSeconds JSON key, in whole seconds" in {
    val json = upickle.default.write(WorkspaceSandboxConfig(defaultCommandTimeout = 45.seconds))
    ujson.read(json)("defaultCommandTimeoutSeconds").num shouldBe 45
    upickle.default.read[WorkspaceSandboxConfig](json).defaultCommandTimeout shouldBe 45.seconds
  }

  it should "reject invalid defaultCommandTimeout" in {
    val bad = WorkspaceSandboxConfig.Permissive.copy(defaultCommandTimeout = Duration.Zero)
    WorkspaceSandboxConfig.validate(bad) shouldBe Left("defaultCommandTimeout must be positive")
    val tooLong = WorkspaceSandboxConfig.Permissive.copy(defaultCommandTimeout = 61.minutes)
    WorkspaceSandboxConfig.validate(tooLong) shouldBe Left("defaultCommandTimeout must be at most 1 hour")
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
