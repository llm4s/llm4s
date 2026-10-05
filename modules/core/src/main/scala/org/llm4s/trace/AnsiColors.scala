package org.llm4s.trace

import org.llm4s.annotation.Stable

/**
 * ANSI escape codes for terminal color formatting.
 *
 * Provides a centralized set of color constants used by console-based
 * tracing implementations for improved readability during development
 * and debugging.
 *
 * @example
 * {{{
 * import org.llm4s.trace.AnsiColors._
 *
 * println(s"$${GREEN}Success!$${RESET}")
 * println(s"$${RED}$${BOLD}Error: Something failed$${RESET}")
 * }}}
 *
 * @note These codes work on most Unix terminals and Windows Terminal.
 *       They may not render correctly in non-ANSI-compatible environments.
 */
@Stable
object AnsiColors {

  /** Reset all formatting to terminal defaults */
  val RESET: String = "\u001b[0m"

  // Foreground colors
  /** Blue foreground color */
  val BLUE: String = "\u001b[34m"

  /** Green foreground color */
  val GREEN: String = "\u001b[32m"

  /** Yellow foreground color */
  val YELLOW: String = "\u001b[33m"

  /** Red foreground color */
  val RED: String = "\u001b[31m"

  /** Cyan foreground color */
  val CYAN: String = "\u001b[36m"

  /** Magenta foreground color */
  val MAGENTA: String = "\u001b[35m"

  /** Gray (bright black) foreground color */
  val GRAY: String = "\u001b[90m"

  // Text styles
  /** Bold text style */
  val BOLD: String = "\u001b[1m"

  /**
   * Creates a separator line of the specified character and length.
   *
   * @param char Character to repeat (default: '=')
   * @param length Length of the separator (default: 60)
   * @return A string of repeated characters
   */
  def separator(char: Char = '=', length: Int = 60): String = char.toString * length

  /**
   * Wraps text in color codes with automatic reset.
   *
   * @param text Text to colorize
   * @param color Color code to apply
   * @return Colorized text with reset at the end
   */
  def colorize(text: String, color: String): String = s"$color$text$RESET"

  /**
   * Wraps text in bold color codes with automatic reset.
   *
   * @param text Text to format
   * @param color Color code to apply
   * @return Bold colorized text with reset at the end
   */
  def boldColor(text: String, color: String): String = s"$BOLD$color$text$RESET"
}
