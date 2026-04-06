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
  private def defaultExecutePayload(payload: String): Either[String, String] =
    ShellTool
      .createSafe(ShellConfig(allowedCommands = Seq("echo")))
      .fold(
        err => Left(s"Tool creation failed: ${err.formatted}"),
        tool => tool.handler(SafeParameterExtractor(ujson.Obj("command" -> payload))).map(_.toString())
      )

  def run(
    isWindows: Boolean,
    createTempFile: () => java.io.File = () => java.io.File.createTempFile("shell-tool-bypass", ".tmp"),
    printLine: String => Unit = println,
    executePayload: String => Either[String, String] = defaultExecutePayload
  ): Unit = {
    if (isWindows) {
      printLine("PoC is intended for Unix-like systems (uses rm).")
      return
    }

    val tempFile = createTempFile()
    tempFile.deleteOnExit()

    val payload = s"echo hi && rm ${tempFile.getAbsolutePath}"
    val result  = executePayload(payload)

    result match {
      case Left(error) =>
        printLine(s"Rejected as expected: $error")
      case Right(value) =>
        printLine(s"Unexpected success: $value")
    }

    printLine(s"Temp file still exists: ${tempFile.exists()}")
  }

  def main(args: Array[String]): Unit = {
    val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
    run(isWindows = isWindows)
  }
}
