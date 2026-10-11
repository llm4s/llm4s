package org.llm4s.shared

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorkspaceSandboxConfigSpec extends AnyFlatSpec with Matchers {
  import WorkspaceSandboxConfigSpec._

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
    val widened = WorkspaceSandboxConfig.Permissive.withExtraCommands(" sbt, mvn\tscala-cli ")
    widened.map(_.allowedCommands) shouldBe Right(
      WorkspaceSandboxConfig.ReadWriteCommands ++ Set("sbt", "mvn", "scala-cli")
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

  it should "refuse every interpreter and launcher, in any case and with a .exe / .com extension (#1761)" in {
    for {
      base <- NewlyRefused
      name <- Seq(base, base.toUpperCase, base.capitalize, s"$base.exe", s"${base.toUpperCase}.EXE", s"$base.com")
    } withClue(name) {
      WorkspaceSandboxConfig.Permissive.withExtraCommands(s"sbt,$name") shouldBe
        Left(s"'$name' runs other programs, so it cannot be added to the allowlist")
    }
  }

  it should "refuse the Windows forms of the shells: bash.exe, sh.exe, powershell.exe, wsl.exe (#1761)" in {
    for (name <- Seq("bash.exe", "sh.exe", "SH.EXE", "powershell.exe", "PowerShell.Exe", "pwsh.exe", "wsl.exe"))
      withClue(name) {
        WorkspaceSandboxConfig.Permissive.withExtraCommands(name) shouldBe
          Left(s"'$name' runs other programs, so it cannot be added to the allowlist")
      }
  }

  it should "refuse a versioned interpreter (python3.12, perl5.36, gdb-12)" in {
    for (name <- Seq("python3.12", "Python3.12.exe", "perl5.36", "lua5.4", "php8.2", "ruby3.3", "java21", "gdb-12"))
      withClue(name) {
        WorkspaceSandboxConfig.Permissive.withExtraCommands(name) shouldBe
          Left(s"'$name' runs other programs, so it cannot be added to the allowlist")
      }
  }

  it should "refuse a .bat or .cmd name, which Windows runs through cmd.exe (BatBadBut, #1761)" in {
    for (name <- Seq("build.bat", "build.cmd", "BUILD.BAT", "Build.Cmd", "sbt.bat", "gradlew.bat.exe"))
      withClue(name) {
        WorkspaceSandboxConfig.Permissive.withExtraCommands(s"sbt $name") shouldBe Left(
          s"'$name' is a Windows batch file, which Windows runs through cmd.exe, so it cannot be added to the allowlist"
        )
      }
  }

  it should "still accept sbt and other build tools that are not on the denylist" in {
    for (name <- Seq("sbt", "SBT.EXE", "mvn", "gradle", "scala-cli", "cargo", "scala3", "sbt-1.10"))
      withClue(name) {
        WorkspaceSandboxConfig.Permissive.withExtraCommands(name).map(_.allowedCommands.contains(name)) shouldBe
          Right(true)
      }
  }

  it should "refuse any name for the locked profile, which runs no command (#1761)" in {
    val refused = WorkspaceSandboxConfig.LockedDown.withExtraCommands("sbt, mvn")
    refused.isLeft shouldBe true
    refused.left.toOption.get should (include("WORKSPACE_EXTRA_COMMANDS names mvn, sbt")
      .and(include("shellAllowed=false"))
      .and(include("could never run")))
    WorkspaceSandboxConfig.Permissive.copy(shellAllowed = false).withExtraCommands("sbt").isLeft shouldBe true
    // Nothing named: nothing to refuse
    WorkspaceSandboxConfig.LockedDown.withExtraCommands("") shouldBe Right(WorkspaceSandboxConfig.LockedDown)
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

object WorkspaceSandboxConfigSpec {

  // #1761, #1792: interpreters and launchers run arbitrary code with no forbidden character
  // (`python3 -c "__import__('os').system('id')"`), so none of them may be added either
  val NewlyRefused = Seq(
    // interpreters
    "python",
    "python3",
    "perl",
    "ruby",
    "node",
    "php",
    "lua",
    "tclsh",
    "awk",
    "gawk",
    "mawk",
    "nawk",
    "java",
    "jshell",
    "make",
    "expect",
    // launchers
    "setsid",
    "stdbuf",
    "chroot",
    "ionice",
    "flock",
    "watch",
    "script",
    "time",
    "systemd-run",
    "parallel",
    "toybox",
    "busybox",
    // debuggers, remote and container tools
    "strace",
    "ltrace",
    "gdb",
    "ssh",
    "docker",
    "podman",
    // privilege and namespace tools
    "runuser",
    "doas",
    "pkexec",
    "nsenter",
    "unshare",
    "taskset",
    "chrt",
    // Windows hosts and shells
    "wsl",
    "cscript",
    "wscript",
    "mshta",
    "rundll32",
    "powershell",
    "pwsh",
    // cmd.exe built-ins that run a file (#1792)
    "call",
    "start"
  )
}
