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

  "WorkspaceSandboxConfig.withExtraCommands" should "add bare program names, separated by commas or whitespace" in {
    val widened = WorkspaceSandboxConfig.Permissive.withExtraCommands(" sbt, java\tscala-cli ")
    widened.map(_.allowedCommands) shouldBe Right(
      WorkspaceSandboxConfig.ReadWriteCommands ++ Set("sbt", "java", "scala-cli")
    )
    widened.map(_.copy(allowedCommands = Set.empty)) shouldBe
      Right(WorkspaceSandboxConfig.Permissive.copy(allowedCommands = Set.empty))
  }

  it should "leave the config unchanged for an empty list" in {
    WorkspaceSandboxConfig.LockedDown.withExtraCommands(" , ") shouldBe Right(WorkspaceSandboxConfig.LockedDown)
  }

  it should "refuse a path, an option or a name with shell characters" in {
    WorkspaceSandboxConfig.Permissive.withExtraCommands("/bin/sbt") shouldBe Left(
      "'/bin/sbt' is not a bare program name"
    )
    WorkspaceSandboxConfig.Permissive.withExtraCommands("..") shouldBe Left("'..' is not a bare program name")
    WorkspaceSandboxConfig.Permissive.withExtraCommands("-x") shouldBe Left("'-x' is not a bare program name")
    WorkspaceSandboxConfig.Permissive.withExtraCommands("sbt;id") shouldBe Left("'sbt;id' is not a bare program name")
    WorkspaceSandboxConfig.Permissive.withExtraCommands("C:\\sbt.bat").isLeft shouldBe true
  }

  it should "refuse a shell or a program launcher, in any case" in {
    for (name <- Seq("sh", "bash", "Bash", "env", "xargs", "cmd.exe", "pwsh", "sudo", "bash.exe", "CMD.COM", "Env.Exe"))
      withClue(name) {
        WorkspaceSandboxConfig.Permissive.withExtraCommands(s"sbt,$name") shouldBe
          Left(s"'$name' runs other programs, so it cannot be added to the allowlist")
      }
  }

  "WorkspaceSandboxConfig.programKey" should "match a Windows name without case or a .exe / .com extension (#1790)" in {
    WorkspaceSandboxConfig.programKey("sort.exe", windows = true) shouldBe "sort"
    WorkspaceSandboxConfig.programKey("SORT.COM", windows = true) shouldBe "sort"
    WorkspaceSandboxConfig.programKey("Git", windows = true) shouldBe "git"
    WorkspaceSandboxConfig.programKey("git.exe.exe", windows = true) shouldBe "git.exe" // one extension is removed
    WorkspaceSandboxConfig.programKey(".exe", windows = true) shouldBe ".exe"           // no name left: kept
    WorkspaceSandboxConfig.programKey("python3.11", windows = true) shouldBe "python3.11"
    // Elsewhere a name is matched as written
    WorkspaceSandboxConfig.programKey("sort.exe", windows = false) shouldBe "sort.exe"
    WorkspaceSandboxConfig.programKey("Git", windows = false) shouldBe "Git"
  }
}
