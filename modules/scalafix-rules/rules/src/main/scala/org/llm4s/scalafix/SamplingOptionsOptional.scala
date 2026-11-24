package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `CompletionOptions`' sampling parameters (`temperature`, `topP`, `presencePenalty`, `frequencyPenalty`) are
 * `Option[Double]`, `None` unless set, where they were a `Double` with a default (#2807).
 *
 * `CompletionOptions(temperature = 0.2, topP = 0.9)` becomes `CompletionOptions(temperature = Some(0.2), topP =
 * Some(0.9))`. The setters still take the value (`withTemperature(0.2)`), so a setter call is left alone. A read of
 * one of the four is not rewritten: `options.temperature` is an `Option` now, and what stands in for `None` is the
 * caller's to decide. An argument passed by position is not rewritten either.
 */
class SamplingOptionsOptional extends SemanticRule("SamplingOptionsOptional") {

  override def description: String =
    "Wraps CompletionOptions' temperature, topP and penalties (a Double up to llm4s 0.6.0) in Some"

  import SamplingOptionsOptional._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case t: Term.Apply if !t.fun.is[Term.Apply] && Migration.callee(t).exists(Migration.constructs(_, Options)) =>
        t.argClause.values.collect {
          case Term.Assign(Term.Name(field), rhs) if Sampling(field) && !optional(rhs) =>
            Patch.addLeft(rhs, "Some(") + Patch.addRight(rhs, ")")
        }.asPatch
    }.asPatch
}

object SamplingOptionsOptional {
  private val Options  = "org/llm4s/llmconnect/model/CompletionOptions"
  private val Sampling = Set("temperature", "topP", "presencePenalty", "frequencyPenalty")

  /** `None` or `Some(x)`: already an `Option`, as in code half migrated by hand. */
  private def optional(rhs: Term): Boolean = rhs match {
    case Term.Name("None") => true
    case a: Term.Apply     => CopyToSetters.isSome(a)
    case _                 => false
  }
}
