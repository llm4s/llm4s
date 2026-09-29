package org.llm4s.toolapi.builtin

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

  it should "not let shell metacharacters escape the allowlist" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    val tempFile = java.io.File.createTempFile("shell-tool", ".tmp")
    tempFile.deleteOnExit()

    val config = ShellConfig(allowedCommands = Seq("echo"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> s"echo hi && rm ${tempFile.getAbsolutePath}")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              shellResult => {
                // No shell is invoked, so `&&` is just an argument to echo.
                // The dangerous part — rm running — must not happen.
                shellResult.exitCode shouldBe 0
                shellResult.stdout should include("&&")
                shellResult.stdout should include(tempFile.getAbsolutePath)
                tempFile.exists() shouldBe true
              }
            )
        }
      )
  }

  it should "honour double-quoted arguments containing whitespace" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    val config = ShellConfig(allowedCommands = Seq("echo"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "echo \"hello world\"")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              shellResult => {
                shellResult.exitCode shouldBe 0
                // Single argument "hello world" — quotes consumed by the tokenizer.
                shellResult.stdout.trim shouldBe "hello world"
              }
            )
        }
      )
  }

  it should "honour single-quoted arguments containing metacharacters" in {
    assume(!isWindows, "Unix shell commands not available on Windows")
    val config = ShellConfig(allowedCommands = Seq("echo"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "echo 'a && b | c'")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => fail(s"Expected Right but got Left: $err"),
              shellResult => {
                shellResult.exitCode shouldBe 0
                shellResult.stdout.trim shouldBe "a && b | c"
              }
            )
        }
      )
  }

  it should "reject commands with unclosed quotes" in {
    val config = ShellConfig(allowedCommands = Seq("echo"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "echo \"missing close")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err.toLowerCase should include("unclosed"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  it should "reject empty commands" in {
    val config = ShellConfig(allowedCommands = Seq("echo"))

    ShellTool
      .createSafe(config)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          val params = ujson.Obj("command" -> "   ")
          tool
            .handler(SafeParameterExtractor(params))
            .fold(
              err => err.toLowerCase should include("empty"),
              result => fail(s"Expected Left but got Right: $result")
            )
        }
      )
  }

  "ShellToolAllowlistBypassPoC" should "exit early on Windows" in {
    val lines            = scala.collection.mutable.ArrayBuffer.empty[String]
    var createCalled     = false
    var executeWasCalled = false

    ShellToolAllowlistBypassPoC.run(
      isWindows = true,
      createTempFile = () => {
        createCalled = true
        java.io.File.createTempFile("unused", ".tmp")
      },
      printLine = line => lines += line,
      executePayload = _ => {
        executeWasCalled = true
        Right("ok")
      }
    )

    createCalled shouldBe false
    executeWasCalled shouldBe false
    lines should contain("PoC is intended for Unix-like systems (uses rm).")
  }

  it should "report safe execution when echo runs without shell expansion" in {
    val lines    = scala.collection.mutable.ArrayBuffer.empty[String]
    val tempFile = java.io.File.createTempFile("shell-tool-bypass-spec", ".tmp")
    tempFile.deleteOnExit()

    ShellToolAllowlistBypassPoC.run(
      isWindows = false,
      createTempFile = () => tempFile,
      printLine = line => lines += line,
      executePayload = payload => {
        payload should startWith("echo hi && rm ")
        Right("""{"exitCode":0,"stdout":"hi && rm ..."}""")
      }
    )

    lines should contain("""Executed safely (no shell invoked): {"exitCode":0,"stdout":"hi && rm ..."}""")
    lines should contain(s"rm side-effect avoided (temp file still exists): ${tempFile.exists()}")
  }

  it should "report rejection output when the tool refuses the payload" in {
    val lines    = scala.collection.mutable.ArrayBuffer.empty[String]
    val tempFile = java.io.File.createTempFile("shell-tool-bypass-spec", ".tmp")
    tempFile.deleteOnExit()

    ShellToolAllowlistBypassPoC.run(
      isWindows = false,
      createTempFile = () => tempFile,
      printLine = line => lines += line,
      executePayload = _ => Left("Command 'echo' is not allowed.")
    )

    lines should contain("Rejected: Command 'echo' is not allowed.")
    lines should contain(s"rm side-effect avoided (temp file still exists): ${tempFile.exists()}")
  }

  it should "delegate from main to run without throwing" in {
    val out = new java.io.ByteArrayOutputStream()
    Console.withOut(out) {
      ShellToolAllowlistBypassPoC.main(Array.empty)
    }

    val printed = out.toString("UTF-8")
    printed should (
      include("PoC is intended for Unix-like systems (uses rm).")
        .or(include("rm side-effect avoided (temp file still exists):"))
    )
  }

}
