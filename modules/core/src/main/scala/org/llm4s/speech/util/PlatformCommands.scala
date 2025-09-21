package org.llm4s.speech.util

import scala.util.{ Properties, Try }

/**
 * Cross-platform command utilities for testing speech adapters.
 * Automatically detects OS and provides appropriate safe commands.
 */
object PlatformCommands {

  private val isWindows = Properties.isWin

  /**
   * Get a safe echo command for the current platform.
   * Windows: cmd /c echo
   * POSIX: echo
   */
  def echo: Seq[String] =
    if (isWindows) Seq("cmd", "/c", "echo") else Seq("echo")

  /**
   * Get a safe file content command for the current platform.
   * Windows: cmd /c type
   * POSIX: cat
   */
  def cat: Seq[String] =
    if (isWindows) Seq("cmd", "/c", "type") else Seq("cat")

  /**
   * Get a safe directory listing command for the current platform.
   * Windows: cmd /c dir
   * POSIX: ls
   */
  def ls: Seq[String] =
    if (isWindows) Seq("cmd", "/c", "dir") else Seq("ls")

  /**
   * Get a command that always succeeds for testing purposes.
   * This is useful when you need a mock command that doesn't fail.
   */
  def mockSuccess: Seq[String] =
    if (isWindows) Seq("cmd", "/c", "echo", "mock output") else Seq("echo", "mock output")

  /**
   * Get a command that reads a file and outputs its content for testing.
   * This is useful for simulating Whisper output.
   */
  def mockFileReader: Seq[String] =
    if (isWindows) Seq("cmd", "/c", "type") else Seq("cat")

  /**
   * Check if a command is available on the current platform.
   */
  def isCommandAvailable(command: String): Boolean = {
    import scala.sys.process._
    Try((command :: "--version" :: Nil).!).toOption.contains(0)
  }

  /**
   * Get the current platform name for logging/debugging.
   */
  def platformName: String =
    if (isWindows) "Windows" else "POSIX"
}
