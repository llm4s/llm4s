package org.llm4s.toolapi.builtin.shell

import org.llm4s.toolapi.builtin.filesystem.FileConfig

import scala.concurrent.duration.*

/**
 * Configuration for shell command tool.
 *
 * == Environment ==
 * A command does not inherit the process environment, which is where provider API keys and other secrets live.
 * It gets only the variables named in `inheritedEnvironment` (by default `PATH`, `LANG`, `LC_ALL`, `TERM` and
 * `SystemRoot`, whichever exist), plus everything in `environment`. [[ShellConfig.development]] inherits the whole
 * environment, because build tools need it; it is documented as not a sandbox.
 *
 * == File arguments ==
 * Without `pathPolicy` the tool does not look at the files a command names: `cat` reads any file the process can
 * read, whatever the file tools are configured to allow. Set `pathPolicy` to apply the same containment rule as
 * the file tools to every file-like argument (see [[org.llm4s.toolapi.builtin.filesystem.FileConfig]]): each
 * argument that is not a flag is resolved against the working directory and must be allowed, the working
 * directory itself must be allowed, and a flag that carries a path (`-f/etc/passwd`) is refused. `echo`, `pwd`,
 * `date`, `whoami` and `which` take no file arguments and are not checked. `ls -L` and `ls -H`, which follow
 * links while listing, are refused when a policy is set. A command that walks directories by itself (`ls -R`,
 * `grep -r`, `find`) is checked at its starting point only.
 *
 * @param allowedCommands List of allowed command names (e.g., "ls", "cat", "echo").
 *                        If empty, no commands are allowed.
 * @param workingDirectory Optional working directory for command execution.
 * @param timeout Maximum execution time.
 * @param maxOutputSize Maximum output size in characters.
 * @param environment Additional environment variables to set. They are set after the inherited ones and win.
 * @param inheritedEnvironment Names of the process variables a command receives. `None` passes the whole
 *                             environment through.
 * @param pathPolicy Containment rule for the file-like arguments of a command, or `None` for no check.
 */
case class ShellConfig(
  allowedCommands: Seq[String] = Seq.empty,
  workingDirectory: Option[String] = None,
  timeout: FiniteDuration = 30.seconds,
  maxOutputSize: Int = 100000, // 100KB
  environment: Map[String, String] = Map.empty,
  inheritedEnvironment: Option[Seq[String]] = Some(ShellConfig.DefaultInheritedEnvironment),
  pathPolicy: Option[FileConfig] = None
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

  /** The process variables a command receives unless `inheritedEnvironment` says otherwise. */
  val DefaultInheritedEnvironment: Seq[String] = Seq("PATH", "LANG", "LC_ALL", "TERM", "SystemRoot")

  /**
   * Like [[readOnly]], with the file-like arguments of every command held to `policy`, the same containment rule
   * the file tools use. This is the configuration to give a model that can read untrusted content: with plain
   * [[readOnly]], `cat` reads any file the process can read.
   */
  def readOnlyWithin(policy: FileConfig, workingDirectory: Option[String] = None): ShellConfig =
    readOnly(workingDirectory).copy(pathPolicy = Some(policy))

  /**
   * Create a read-only shell configuration that allows common read-only commands.
   *
   * '''Files are not contained.''' The commands may read any file the process can read, whatever the file tools
   * are configured to allow; use [[readOnlyWithin]] to hold their arguments to a path policy. The command's
   * environment is scrubbed (see [[ShellConfig]]).
   *
   * The programs on this list are ones whose ordinary use only reads, but this is an allowlist of program
   * names, not read-only execution: most options pass through unchecked, so `date -s` sets the clock when
   * the process may. The options that write a file or read a list of files are refused (`file -C`, `-m`, `-f`
   * and `wc --files0-from`). `env` is deliberately absent: with arguments it runs the
   * program that follows it (`env sh -c ...`), so allowing it allows every program, and without them
   * it prints the process environment, which is where API keys live. The allowlist checks the program
   * a command starts with, not the programs that program starts - keep that in mind before adding a
   * launcher such as `env`, `xargs`, `nice`, `timeout` or `nohup`. `file` runs with its flags that write a file
   * or read a list of files (`-C`, `-m`, `-f`) refused.
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
   * process can. Use [[readOnly]], or a list of your own, for anything less trusted. Commands inherit the whole
   * process environment, which the build tools need.
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
      workingDirectory = workingDirectory,
      inheritedEnvironment = None
    )
}
