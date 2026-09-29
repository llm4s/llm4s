package org.llm4s.toolapi.builtin.shell

import scala.collection.mutable

/**
 * Quote-aware tokenizer for shell-style command strings.
 *
 * Splits an input string into argument tokens using the conventions LLMs
 * naturally produce when they write a "shell command":
 *
 *   - Whitespace separates tokens.
 *   - Single quotes preserve their contents literally — no escapes inside.
 *   - Double quotes preserve their contents, with `\"` and `\\` recognised as
 *     escapes for `"` and `\` respectively. Other backslashes inside double
 *     quotes are literal.
 *   - Outside any quotes, `\` escapes the following character (so `\ ` is a
 *     literal space inside a token, `\"` is a literal quote).
 *
 * This is intentionally lighter than full POSIX shell parsing — there is no
 * variable expansion, command substitution, redirection, or globbing. The
 * caller hands these tokens directly to [[ProcessBuilder]], so any
 * metacharacters that survive tokenization are passed to the target program
 * as plain argument bytes rather than interpreted by a shell.
 */
private[shell] object CommandTokenizer {

  def tokenize(input: String): Either[String, Seq[String]] = {
    val tokens  = mutable.ArrayBuffer.empty[String]
    val current = new StringBuilder

    var inSingle = false
    var inDouble = false
    var inToken  = false
    var i        = 0
    val n        = input.length

    def flush(): Unit =
      if (inToken) {
        tokens += current.toString
        current.setLength(0)
        inToken = false
      }

    while (i < n) {
      val c = input.charAt(i)
      if (inSingle) {
        if (c == '\'') inSingle = false
        else {
          current.append(c)
          inToken = true
        }
      } else if (inDouble) {
        if (c == '"') inDouble = false
        else if (c == '\\' && i + 1 < n) {
          val next = input.charAt(i + 1)
          if (next == '"' || next == '\\') {
            current.append(next)
            i += 1
          } else {
            current.append(c)
          }
          inToken = true
        } else {
          current.append(c)
          inToken = true
        }
      } else {
        c match {
          case '\'' =>
            inSingle = true
            inToken = true
          case '"' =>
            inDouble = true
            inToken = true
          case '\\' if i + 1 < n =>
            current.append(input.charAt(i + 1))
            inToken = true
            i += 1
          case w if w.isWhitespace =>
            flush()
          case _ =>
            current.append(c)
            inToken = true
        }
      }
      i += 1
    }

    if (inSingle) Left("Unclosed single quote in command")
    else if (inDouble) Left("Unclosed double quote in command")
    else {
      flush()
      Right(tokens.toSeq)
    }
  }
}
