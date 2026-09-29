package org.llm4s.toolapi.builtin.shell

/**
 * Configuration for shell command tool.
 *
 * @param allowedCommands List of allowed command names (e.g., "ls", "cat", "echo").
 *                        If empty, no commands are allowed.
 * @param workingDirectory Optional working directory for command execution.
 * @param timeoutMs Maximum execution time in milliseconds.
 * @param maxOutputSize Maximum output size in characters.
 * @param environment Additional environment variables to set.
 */
case class ShellConfig(
  allowedCommands: Seq[String] = Seq.empty,
  workingDirectory: Option[String] = None,
  timeoutMs: Long = 30000,     // 30 seconds
  maxOutputSize: Int = 100000, // 100KB
  environment: Map[String, String] = Map.empty
) {

  /**
   * Check if a command is allowed.
   */
  def isCommandAllowed(command: String): Boolean = {
    val baseCommand = command.trim.split("\\s+").headOption.getOrElse("")
    allowedCommands.contains(baseCommand)
  }
}

object ShellConfig {

  /**
   * Create a read-only shell configuration that allows common read-only commands.
   */
  def readOnly(workingDirectory: Option[String] = None): ShellConfig =
    ShellConfig(
      allowedCommands = Seq("ls", "cat", "head", "tail", "pwd", "echo", "wc", "date", "whoami", "env", "which", "file"),
      workingDirectory = workingDirectory
    )

  /**
   * Create a development shell configuration with common dev tools.
   */
  def development(workingDirectory: Option[String] = None): ShellConfig =
    ShellConfig(
      allowedCommands = Seq(
        // Read-only
        "ls",
        "cat",
        "head",
        "tail",
        "pwd",
        "echo",
        "wc",
        "date",
        "whoami",
        "env",
        "which",
        "file",
        // Development tools
        "git",
        "npm",
        "yarn",
        "pnpm",
        "mvn",
        "gradle",
        "sbt",
        "make",
        "cmake",
        // Search
        "grep",
        "find",
        "rg",
        "fd",
        "ag",
        // File operations
        "cp",
        "mv",
        "mkdir",
        "touch",
        "rm",
        // Package managers
        "pip",
        "pip3",
        "poetry",
        "cargo",
        "go"
      ),
      workingDirectory = workingDirectory
    )
}
