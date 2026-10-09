package org.llm4s.toolapi.builtin.shell

import scala.concurrent.duration.*

/**
 * Configuration for shell command tool.
 *
 * @param allowedCommands List of allowed command names (e.g., "ls", "cat", "echo").
 *                        If empty, no commands are allowed.
 * @param workingDirectory Optional working directory for command execution.
 * @param timeout Maximum execution time.
 * @param maxOutputSize Maximum output size in characters.
 * @param environment Additional environment variables to set.
 */
case class ShellConfig(
  allowedCommands: Seq[String] = Seq.empty,
  workingDirectory: Option[String] = None,
  timeout: FiniteDuration = 30.seconds,
  maxOutputSize: Int = 100000, // 100KB
  environment: Map[String, String] = Map.empty
) {

  /**
   * Check if a program is allowed.
   *
   * `command` is the program name - the first token of a tokenized command line, which is how
   * [[ShellTool]] calls this - not a whole command line: `"ls"` is allowed when `ls` is, `"ls -la"`
   * is not a program name and is not.
   */
  def isCommandAllowed(command: String): Boolean =
    allowedCommands.contains(command.trim)
}

object ShellConfig {

  /**
   * Create a read-only shell configuration that allows common read-only commands.
   *
   * The programs on this list are ones whose ordinary use only reads, but this is an allowlist of program
   * names, not read-only execution: their options pass through unchecked, so `date -s` sets the clock when
   * the process may, and `file -C` writes a compiled magic file. `env` is deliberately absent: with arguments it runs the
   * program that follows it (`env sh -c ...`), so allowing it allows every program, and without them
   * it prints the process environment, which is where API keys live. The allowlist checks the program
   * a command starts with, not the programs that program starts - keep that in mind before adding a
   * launcher such as `env`, `xargs`, `nice`, `timeout` or `nohup`.
   */
  def readOnly(workingDirectory: Option[String] = None): ShellConfig =
    ShellConfig(
      allowedCommands = Seq("ls", "cat", "head", "tail", "pwd", "echo", "wc", "date", "whoami", "which", "file"),
      workingDirectory = workingDirectory
    )

  /**
   * Create a development shell configuration with common dev tools.
   *
   * '''This is not a sandbox.''' Build tools (`sbt`, `make`, `npm`, `gradle`, ...), `git`, `find -exec` and
   * `env` run arbitrary programs by design, so a model given this configuration can run anything the
   * process can. Use [[readOnly]], or a list of your own, for anything less trusted.
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
