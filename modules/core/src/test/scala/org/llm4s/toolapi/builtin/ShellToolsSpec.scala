package org.llm4s.toolapi.builtin

import scala.annotation.nowarn

import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.builtin.shell._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ShellToolsSpec extends AnyFlatSpec with Matchers {

  private val isWindows: Boolean = System.getProperty("os.name").toLowerCase.contains("win")

  "ShellConfig" should "allow configured commands" in {
    val config = ShellConfig(allowedCommands = Seq("ls", "cat", "echo"))

    config.isCommandAllowed("ls") shouldBe true
    config.isCommandAllowed("ls -la") shouldBe true
    config.isCommandAllowed("cat file.txt") shouldBe true
    config.isCommandAllowed("echo hello") shouldBe true
  }

  it should "reject non-allowed commands" in {
    val config = ShellConfig(allowedCommands = Seq("ls", "cat"))

    config.isCommandAllowed("rm -rf /") shouldBe false
    config.isCommandAllowed("wget") shouldBe false
    config.isCommandAllowed("curl") shouldBe false
  }

  it should "reject all commands when allowlist is empty" in {
    val config = ShellConfig(allowedCommands = Seq.empty)

    config.isCommandAllowed("ls") shouldBe false
    config.isCommandAllowed("echo") shouldBe false
    config.isCommandAllowed("pwd") shouldBe false
  }

  "ShellConfig.readOnly" should "allow safe read-only commands" in {
    val config = ShellConfig.readOnly()

    config.isCommandAllowed("ls") shouldBe true
    config.isCommandAllowed("cat") shouldBe true
    config.isCommandAllowed("pwd") shouldBe true
    config.isCommandAllowed("echo") shouldBe true
    config.isCommandAllowed("wc") shouldBe true
    config.isCommandAllowed("date") shouldBe true
    config.isCommandAllowed("whoami") shouldBe true
  }

  it should "not allow write commands" in {
    val config = ShellConfig.readOnly()

    config.isCommandAllowed("rm") shouldBe false
    config.isCommandAllowed("mv") shouldBe false
    config.isCommandAllowed("cp") shouldBe false
    config.isCommandAllowed("mkdir") shouldBe false
  }

  "ShellConfig.development" should "allow dev tools" in {
    val config = ShellConfig.development()

    // Read-only
    config.isCommandAllowed("ls") shouldBe true
    config.isCommandAllowed("cat") shouldBe true

    // Dev tools
    config.isCommandAllowed("git") shouldBe true
    config.isCommandAllowed("npm") shouldBe true
    config.isCommandAllowed("sbt") shouldBe true

    // File operations
    config.isCommandAllowed("cp") shouldBe true
    config.isCommandAllowed("mv") shouldBe true
    config.isCommandAllowed("mkdir") shouldBe true
  }

  "ShellTool" should "execute allowed commands" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    val config = ShellConfig(allowedCommands = Seq("echo", "pwd"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "echo hello world")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              shellResult => {
                shellResult.exitCode shouldBe 0
                shellResult.stdout.trim shouldBe "hello world"
              }
            )
        }
      )
  }

  it should "reject non-allowed commands" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    val config = ShellConfig(allowedCommands = Seq("ls"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "rm -rf /tmp/test")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err should include("not allowed"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  it should "capture stderr" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    val config = ShellConfig(allowedCommands = Seq("ls"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "ls /nonexistent/path/12345")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              shellResult => {
                shellResult.exitCode should not be 0
                shellResult.stderr.nonEmpty shouldBe true
              }
            )
        }
      )
  }

  it should "respect timeout" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    val config = ShellConfig(allowedCommands = Seq("sleep"), timeoutMs = 100)

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "sleep 10")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              shellResult => {
                shellResult.timedOut shouldBe true
                shellResult.exitCode shouldBe -1
              }
            )
        }
      )
  }

  it should "use configured working directory" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    val config = ShellConfig(allowedCommands = Seq("pwd"), workingDirectory = Some("/tmp"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "pwd")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              shellResult => {
                // On macOS, /tmp is a symlink to /private/tmp
                val output = shellResult.stdout.trim
                (output == "/tmp" || output == "/private/tmp") shouldBe true
              }
            )
        }
      )
  }

  it should "truncate large output" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    // Use seq which generates output immediately, and a longer timeout to ensure
    // truncation happens before timeout (avoiding race condition on slow CI)
    val config = ShellConfig(allowedCommands = Seq("seq"), maxOutputSize = 100, timeoutMs = 5000)

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          // seq 1 10000 generates ~50KB of output immediately
          val params = ujson.Obj("command" -> "seq 1 10000")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              shellResult => {
                shellResult.truncated shouldBe true
                // 100 bytes of content + "\n... (truncated)" suffix
                shellResult.stdout.length should be <= 120
              }
            )
        }
      )
  }

  // ============ Deprecated API tests ============

  "ShellTool.create() (deprecated)" should "return a valid tool" in {
    @nowarn("cat=deprecation") val tool = ShellTool.create(ShellConfig.readOnly())
    tool.name shouldBe "shell_command"
  }
}
