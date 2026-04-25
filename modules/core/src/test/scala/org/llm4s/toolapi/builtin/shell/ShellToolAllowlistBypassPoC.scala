package org.llm4s.toolapi.builtin.shell

import org.llm4s.toolapi.SafeParameterExtractor

/**
 * Runnable PoC for the shell-allowlist bypass discussed in issue #871.
 *
 * Demonstrates that an LLM-supplied command with shell metacharacters cannot
 * escape the allowlist:
 *
 *   - The command is tokenized (without invoking a shell).
 *   - The first token (`echo`) is checked against the allowlist and accepted.
 *   - The remaining tokens — including `&&`, `rm`, and the temp-file path — are
 *     handed to `echo` as literal arguments.
 *   - `echo` prints them and exits; no `rm` process is ever spawned.
 *
 * The PoC verifies the side-effect that matters: the temp file still exists
 * after the call. The body is structured for dependency injection so the
 * accompanying test can drive every branch deterministically.
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
        printLine(s"Rejected: $error")
      case Right(output) =>
        printLine(s"Executed safely (no shell invoked): $output")
    }

    printLine(s"rm side-effect avoided (temp file still exists): ${tempFile.exists()}")
  }

  def main(args: Array[String]): Unit = {
    val isWindows = System.getProperty("os.name").toLowerCase.contains("win")
    run(isWindows = isWindows)
  }
}
