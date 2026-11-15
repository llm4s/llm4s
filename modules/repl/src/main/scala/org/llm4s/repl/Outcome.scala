package org.llm4s.repl

import org.llm4s.annotation.Experimental
import org.llm4s.error.LLMError
import org.llm4s.types.Result

/**
 * What a REPL helper returns: the value, or the error that stopped it. A REPL prints it as the value itself, or as
 * `error: <kind>: <message and hint>`, never as a stack trace. A script that wants a `Result` calls [[toResult]], or
 * uses the helpers of [[strict]].
 *
 * {{{
 * val answer = ask("What is a monad?")
 * answer.getOrElse("")      // the text, or "" when the call failed
 * answer.toResult           // Right(text) or Left(error)
 * }}}
 */
@Experimental
enum Outcome[+A] {

  /** The helper's value. */
  case Done(value: A)

  /** The error that stopped the helper; it has already been printed. */
  case Failed(error: LLMError)

  /** `Right(value)` or `Left(error)`. */
  def toResult: Result[A] =
    this match {
      case Done(value)   => Right(value)
      case Failed(error) => Left(error)
    }

  /** The value, if the helper succeeded. */
  def toOption: Option[A] = toResult.toOption

  /** The value, or `default` when the helper failed. */
  def getOrElse[B >: A](default: => B): B = toOption.getOrElse(default)

  /** Whether the helper failed. */
  def isFailure: Boolean = toResult.isLeft

  /** The error, if the helper failed. */
  def failure: Option[LLMError] = toResult.left.toOption

  override def toString: String =
    this match {
      case Done(value)   => String.valueOf(value)
      case Failed(error) => Render.error(error)
    }
}
