package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `Completion.created` is a `java.time.Instant`, not epoch seconds as a `Long` (#2262).
 *
 * `Completion(created = 1700000000L, ...)` becomes `Completion(created = Instant.ofEpochSecond(1700000000L), ...)`, and
 * so do `completion.withCreated(seconds)` and `accumulator.toCompletion(seconds)`; a `0L` becomes `Instant.EPOCH`.
 * Reading `completion.created` becomes `completion.created.getEpochSecond`, so the expression keeps its type.
 * `import java.time.Instant` is added where it is missing.
 */
class CompletionCreatedToInstant extends SemanticRule("CompletionCreatedToInstant") {

  override def description: String =
    "Rewrites the epoch-seconds Long of Completion.created (0.5.1) to a java.time.Instant"

  import CompletionCreatedToInstant._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val seconds = doc.tree.collect { case t: Term.Apply => arguments(t) }.flatten
    val wrapped = seconds.filterNot(isCreated).map(arg => Patch.replaceTree(arg, instant(arg)))
    // A read of `created` passed straight back in is an Instant on both sides: neither is rewritten.
    val reads = doc.tree.collect {
      case s @ Term.Select(_, _) if isCreated(s) && !seconds.contains(s) => Patch.addRight(s, ".getEpochSecond")
    }
    val patch = (wrapped ++ reads).asPatch
    if (wrapped.isEmpty) patch else patch + Migration.importOnce("java.time", "Instant")
  }

  /** The arguments of a call that are a creation time in epoch seconds. */
  private def arguments(t: Term.Apply)(implicit doc: SemanticDocument): List[Term] =
    Migration.callee(t).fold(List.empty[Term]) { callee =>
      val args = t.argClause.values
      if (Migration.constructs(callee, Completion))
        args.zipWithIndex.collect {
          case (Term.Assign(Term.Name("created"), rhs), _)                                    => rhs
          case (arg, 1) if !arg.is[Term.Assign] && args.headOption.exists(!_.is[Term.Assign]) => arg
        }
      else if (callee == Completion + "#withCreated()." || callee.startsWith(Accumulator + "#toCompletion("))
        args match {
          case List(arg) if !arg.is[Term.Assign] => List(arg)
          case _                                 => Nil
        }
      else Nil
    }

  private def isCreated(t: Term)(implicit doc: SemanticDocument): Boolean = t match {
    case Term.Select(_, field @ Term.Name("created")) => Migration.symbolOf(field).exists(_.value == Created)
    case _                                            => false
  }
}

object CompletionCreatedToInstant {

  private val Completion  = "org/llm4s/llmconnect/model/Completion"
  private val Created     = Completion + "#created."
  private val Accumulator = "org/llm4s/llmconnect/streaming/StreamingAccumulator"

  /** `0L` is `Instant.EPOCH`; any other count of seconds `Instant.ofEpochSecond(seconds)`. */
  private def instant(seconds: Term): String = seconds match {
    case Lit.Long(0L) | Lit.Int(0) => "Instant.EPOCH"
    case other                     => s"Instant.ofEpochSecond(${other.pos.text})"
  }
}
