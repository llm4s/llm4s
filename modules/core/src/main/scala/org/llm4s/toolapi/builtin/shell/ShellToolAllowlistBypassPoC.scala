package org.llm4s.toolapi.builtin.shell

import org.llm4s.toolapi.SafeParameterExtractor

/**
 * Runnable PoC for shell allowlist bypass hardening.
 *
 * Expected behavior:
 * - command is rejected due to shell metacharacters
 * - temp file still exists because rm never executes
 */
object ShellToolAllowlistBypassPoC {
  def main(args: Array[String]): Unit = {
    val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
    if (isWindows) {
      println("PoC is intended for Unix-like systems (uses rm).")
      return
    }

    val tempFile = java.io.File.createTempFile("shell-tool-bypass", ".tmp")
    tempFile.deleteOnExit()

    val config  = ShellConfig(allowedCommands = Seq("echo"))
    val payload = s"echo hi && rm ${tempFile.getAbsolutePath}"

    val result = ShellTool
      .createSafe(config)
      .fold(
        err => Left(s"Tool creation failed: ${err.formatted}"),
        tool => tool.handler(SafeParameterExtractor(ujson.Obj("command" -> payload)))
      )

    result match {
      case Left(error) =>
        println(s"Rejected as expected: $error")
      case Right(value) =>
        println(s"Unexpected success: $value")
    }

    println(s"Temp file still exists: ${tempFile.exists()}")
  }
}
