package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * A `Completion` reads its `content` and `toolCalls` from its `message` instead of storing them beside it, and its
 * `estimatedCost` is a `BigDecimal` (#2807).
 *
 *   - `Completion(id = ..., created = ..., content = text, model = ..., message = m, toolCalls = calls)` loses its
 *     `content` and `toolCalls` arguments, named or positional (the third and the sixth). '''Check each one:''' the
 *     text and the calls must be on `message`, since that is now the only place they are read from.
 *   - `case Completion(id, _, _, model, _, _, ...)` loses its third and sixth patterns and gains a `_` for each field
 *     added since. A pattern that binds either of the two is reported instead: read `completion.content` or
 *     `completion.toolCalls`.
 *   - `completion.asText` becomes `completion.content`.
 *   - `withEstimatedCost(cost)` becomes `withEstimatedCost(BigDecimal.decimal(cost))`, and
 *     `withEstimatedCost(Some(cost))` `withEstimatedCost(Some(BigDecimal.decimal(cost)))`.
 *   - `completion.withContent(...)` and `completion.withToolCalls(...)` are reported: use
 *     `completion.withMessage(completion.message.withContent(...))`.
 */
class CompletionFromMessage extends SemanticRule("CompletionFromMessage") {

  override def description: String =
    "Drops Completion's content and toolCalls arguments (read from its message since llm4s 0.6.0), asText to content"

  import CompletionFromMessage._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case t: Term.Apply if !t.fun.is[Term.Apply] => construction(t) + cost(t)
      case p: Pat.Extract if Migration.symbolOf(p.fun).exists(s => Migration.owner(s) == Type) => pattern(p)
      case n: Term.Name =>
        Migration.symbolOf(n).map(_.value) match {
          case Some(AsText)                 => Patch.replaceTree(n, "content")
          case Some(s) if RemovedSetters(s) => Patch.lint(setterRemoved(n))
          case _                            => Patch.empty
        }
    }.asPatch

  /** `Completion(...)` without its `content` and `toolCalls` arguments. */
  private def construction(t: Term.Apply)(implicit doc: SemanticDocument): Patch =
    if (!Migration.callee(t).exists(Migration.constructs(_, Type))) Patch.empty
    else {
      val args       = t.argClause.values
      val positional = args.takeWhile(!_.is[Term.Assign]).size
      val dropped = args.zipWithIndex.collect {
        case (Term.Assign(Term.Name("content" | "toolCalls"), _), i)      => i
        case (_, i) if i < positional && (i == ContentAt || i == CallsAt) => i
      }
      remove(args, dropped)
    }

  /** `withEstimatedCost(double)` and `withEstimatedCost(Some(double))` with the cost as a `BigDecimal`. */
  private def cost(t: Term.Apply)(implicit doc: SemanticDocument): Patch =
    (Migration.symbolOf(t.fun).map(_.value), t.argClause.values) match {
      case (Some(WithCost), List(arg)) => Patch.addLeft(arg, "BigDecimal.decimal(") + Patch.addRight(arg, ")")
      case (Some(WithCostOption), List(some: Term.Apply)) if CopyToSetters.isSome(some) =>
        val inner = some.argClause.values.head
        Patch.addLeft(inner, "BigDecimal.decimal(") + Patch.addRight(inner, ")")
      case _ => Patch.empty
    }

  /** A positional pattern of 0.5.1's or 0.6.0's arity without the two fields, with a `_` for each field added. */
  private def pattern(p: Pat.Extract)(implicit doc: SemanticDocument): Patch = {
    val args = p.argClause.values
    if (!OldArities(args.size) || args.exists(_.is[Pat.SeqWildcard])) Patch.empty
    else if (args(ContentAt).is[Pat.Wildcard] && args(CallsAt).is[Pat.Wildcard])
      remove(args, List(ContentAt, CallsAt)) + Patch.addRight(args.last, ", _" * (Arity - (args.size - 2)))
    else
      Patch.lint(
        Diagnostic(
          "patternBindsRemovedField",
          "Completion no longer has content and toolCalls fields: bind the completion and read completion.content " +
            s"or completion.toolCalls, which come from its message. See ${Migration.guide10(Anchor)}",
          p.pos
        )
      )
  }

  /** Removes the arguments at `indices`, each with the comma that separated it from its neighbour. */
  private def remove(args: List[Tree], indices: List[Int])(implicit doc: SemanticDocument): Patch = {
    val ranges = indices.map { i =>
      if (i + 1 < args.size) (args(i).pos.start, args(i + 1).pos.start)
      else if (i > 0) (args(i - 1).pos.end, args(i).pos.end)
      else (args(i).pos.start, args(i).pos.end)
    }
    val tokens = doc.tokens.filter(tk => ranges.exists { case (from, to) => tk.pos.start >= from && tk.pos.end <= to })
    // a zero-width token (the file's BOF/EOF) is in no argument
    Patch.removeTokens(tokens.filter(tk => tk.pos.end > tk.pos.start))
  }
}

object CompletionFromMessage {
  private val Type = "org/llm4s/llmconnect/model/Completion"

  private val AsText         = Type + "#asText()."
  private val WithCost       = Type + "#withEstimatedCost()."
  private val WithCostOption = Type + "#withEstimatedCost(+1)."
  private val RemovedSetters = Set(Type + "#withContent().", Type + "#withToolCalls().")

  /** Where `content` and `toolCalls` were, in `Completion.apply` and in its extractor. */
  private val ContentAt = 2
  private val CallsAt   = 5

  /** The extractor's arity in 0.5.1 and in 0.6.0, and now. */
  private val OldArities = Set(9, 10)
  private val Arity      = 10

  private val Anchor = "completion-reads-content-and-toolcalls-from-its-message-2807"

  private def setterRemoved(n: Term.Name): Diagnostic =
    Diagnostic(
      "setterRemoved",
      s"Completion.${n.value} is removed: a Completion's content and toolCalls are its message's. Use " +
        s"completion.withMessage(completion.message.${n.value}(...)). See ${Migration.guide10(Anchor)}",
      n.pos
    )
}
