package org.llm4s.tapir

import sttp.tapir.DecodeResult

/** Decode failures as sentences a model can act on. */
private[tapir] object Failures {

  def describe(failure: DecodeResult.Failure): String = failure match {
    case DecodeResult.Missing                    => "is required"
    case DecodeResult.Multiple(values)           => s"takes one value, not ${values.size}"
    case DecodeResult.Mismatch(expected, actual) => s"must be $expected, not $actual"
    case DecodeResult.InvalidValue(errors) =>
      errors
        .map { error =>
          val at    = if (error.path.isEmpty) "" else s" at ${error.path.map(_.encodedName).mkString(".")}"
          val shown = error.customMessage.orElse(error.validator.show).getOrElse("valid")
          s"is invalid$at: expected $shown, got ${error.invalidValue}"
        }
        .mkString("; ")
    case DecodeResult.Error(original, error) =>
      s"cannot be read (${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}): ${Text.truncate(original)}"
  }
}

/** Text shown to a model, kept short. */
private[tapir] object Text {

  /** The most characters of a response or argument a tool error quotes. */
  val Limit = 2000

  def truncate(text: String): String =
    if (text.length <= Limit) text else s"${text.take(Limit)}... (${text.length - Limit} more characters)"
}
