package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `StreamedChunk.finishReason` is an `Option[FinishReason]`, not an `Option[String]` (#2613, RFC 0009 PR 1).
 *
 * The danger is a comparison that still compiles: `chunk.finishReason.contains("stop")` and
 * `chunk.finishReason == Some("stop")` are always `false` now. The rule rewrites:
 *
 *   - a comparison with a string literal to one with the case: `contains("stop")` to `contains(FinishReason.Stop)`,
 *     `== Some("length")` to `== Some(FinishReason.Length)`, `exists(_ == "tool_calls")` to
 *     `exists(_ == FinishReason.ToolCalls)`, and `case Some("stop")` in a `match` on the reason;
 *   - setting one from a literal: `withFinishReason("stop")` and `StreamedChunk(..., finishReason = Some("stop"))` to
 *     `FinishReason.Stop`;
 *   - every other read to `chunk.finishReason.map(_.name)`, which keeps its type and the chat-completions string.
 *
 * `stop`, `tool_calls` and `length` become their cases. A comparison with any other literal, such as
 * `"MALFORMED_FUNCTION_CALL"`, compares `.map(_.name)` instead, as `FinishReason.Other(raw).name` is the raw value; a
 * reason set from one becomes `FinishReason.Other(...)`.
 *
 * Three kinds of literal are reported instead, as any rewrite could compile and never match:
 *   - a raw value 0.6.0's Bedrock and Gemini chunks carried (`end_turn`, `tool_use`, `max_tokens`, `MAX_TOKENS`, ...),
 *     which RFC 0009 PR 2 maps to a case while a Spring AI model still passes it through as `Other(raw)`;
 *   - a content filter's (`content_filter`, `refusal`, `SAFETY`, ...), a `ContentFilteredError` now on most clients;
 *   - Gemini's `STOP`, which is `Stop` or `ToolCalls` by whether the reply called a tool.
 * A literal like these in a shape the rule does not read (an `||`, a `getOrElse`) is reported too. A reason set from
 * a `String` that is not a literal is reported: no public function maps a string to its case. A reason copied from one
 * chunk to another is left as it is.
 * `import org.llm4s.llmconnect.model.FinishReason` is added where a case is written.
 */
class FinishReasonTyped extends SemanticRule("FinishReasonTyped") {

  override def description: String =
    "Rewrites the Option[String] StreamedChunk.finishReason (llm4s 0.6.0) to the typed FinishReason"

  import FinishReasonTyped._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val sets       = doc.tree.collect { case t: Term.Apply if !t.fun.is[Term.Apply] => setArguments(t) }.flatten
    val inSet      = (t: Tree) => sets.exists(s => within(s, t))
    val setPatches = sets.map(set)
    val readPatches = doc.tree.collect {
      case s: Term.Select if isRead(s) && !inSet(s) => read(s)
    }
    val all     = setPatches ++ readPatches
    val patch   = all.map(_._1).asPatch
    val written = all.exists(_._2)
    if (written) patch + Migration.importOnce("org.llm4s.llmconnect.model", "FinishReason") else patch
  }

  /** The arguments of a call that set a chunk's finish reason. */
  private def setArguments(t: Term.Apply)(implicit doc: SemanticDocument): List[Term] =
    Migration.callee(t).fold(List.empty[Term]) { callee =>
      val args = t.argClause.values
      if (callee.startsWith(Chunk + "#withFinishReason("))
        args match {
          case List(arg) if !arg.is[Term.Assign] => List(arg)
          case _                                 => Nil
        }
      else if (Migration.constructs(callee, Chunk))
        args.zipWithIndex.collect {
          case (Term.Assign(Term.Name("finishReason"), rhs), _)                                  => rhs
          case (arg, i) if i == FinishReasonIndex && args.take(i + 1).forall(!_.is[Term.Assign]) => arg
        }
      else Nil
    }

  /** A reason set from a literal (or `Some` of one) as its case; one set from another chunk's is left alone. */
  private def set(arg: Term)(implicit doc: SemanticDocument): (Patch, Boolean) = arg match {
    case lit: Lit.String => lint(lit).map(_ -> false).getOrElse(Patch.replaceTree(lit, typed(lit)) -> true)
    case a: Term.Apply if CopyToSetters.isSome(a) =>
      a.argClause.values.head match {
        case lit: Lit.String => set(lit)
        case inner           => set(inner)
      }
    case Term.Name("None")           => Patch.empty -> false
    case s: Term.Select if isRead(s) => Patch.empty -> false
    case other =>
      Patch.lint(
        Diagnostic(
          "finishReasonFromString",
          "a finish reason is a FinishReason now (Stop, ToolCalls, Length or Other(raw)), not a String: " + Guide,
          other.pos
        )
      ) -> false
  }

  /** A read of a chunk's reason: its comparisons with the literals of a case typed, or else `.map(_.name)`. */
  private def read(s: Term.Select): (Patch, Boolean) = {
    val literals: Option[List[Lit.String]] = s.parent match {
      // reason.contains("stop")
      case Some(Term.Select(_, Term.Name("contains"))) =>
        s.parent.flatMap(_.parent).collect {
          case a: Term.Apply if a.argClause.values.size == 1 && a.argClause.values.head.is[Lit.String] =>
            List(a.argClause.values.head.asInstanceOf[Lit.String])
        }
      // reason.exists(_ == "stop"), reason.forall(r => r != "stop")
      case Some(Term.Select(_, Term.Name("exists" | "forall"))) =>
        s.parent
          .flatMap(_.parent)
          .collect {
            case a: Term.Apply if a.argClause.values.size == 1 => comparedInLambda(a.argClause.values.head)
          }
          .flatten
      // reason == Some("stop"), Some("stop") != reason
      case Some(i: Term.ApplyInfix) if isEquality(i) =>
        (if (i.lhs eq s) i.argClause.values.head else i.lhs) match {
          case a: Term.Apply if CopyToSetters.isSome(a) =>
            a.argClause.values.head match {
              case lit: Lit.String => Some(List(lit))
              case _               => None
            }
          case Term.Name("None") => Some(Nil)
          case _                 => None
        }
      // reason match { case Some("stop") => ...; case None => ... }
      case Some(m: Term.Match) if m.expr eq s => matchLiterals(m.cases)
      // reason.isDefined: the type does not matter
      case Some(Term.Select(_, Term.Name("isDefined" | "isEmpty" | "nonEmpty"))) => Some(Nil)
      case _                                                                     => None
    }
    literals match {
      // A literal no rewrite gets right: report it, and leave the read alone.
      case Some(lits) if lits.exists(l => lint(l).isDefined) => lits.flatMap(lint).asPatch -> false
      // Every literal has a case of its own: compare with the cases.
      case Some(lits) if lits.forall(l => Cases.contains(l.value)) =>
        lits.map(l => Patch.replaceTree(l, typed(l))).asPatch -> lits.nonEmpty
      // Any other shape: a reported literal in the same expression is reported, not compared by name, which would
      // never match it; otherwise compare the name, as the string did.
      case _ =>
        val reported = statementOf(s).collect { case l: Lit.String => l }.flatMap(lint)
        if (reported.nonEmpty) reported.asPatch -> false else Patch.addRight(s, ".map(_.name)") -> false
    }
  }

  /** The literals a one-argument lambda compares its argument with, if that is all its body does. */
  private def comparedInLambda(f: Term): Option[List[Lit.String]] = {
    def compares(body: Term, isArg: Term => Boolean): Option[List[Lit.String]] = body match {
      case i: Term.ApplyInfix if isEquality(i) =>
        (i.lhs, i.argClause.values.head) match {
          case (lit: Lit.String, other) if isArg(other) => Some(List(lit))
          case (other, lit: Lit.String) if isArg(other) => Some(List(lit))
          case _                                        => None
        }
      case _ => None
    }
    f match {
      case Term.AnonymousFunction(body) => compares(body, _.is[Term.Placeholder])
      case fn: Term.Function if fn.paramClause.values.size == 1 =>
        val param = fn.paramClause.values.head
        compares(
          fn.body,
          {
            case Term.Name(n) => n == param.name.value
            case _            => false
          }
        )
      case b: Term.Block if b.stats.size == 1 =>
        b.stats.head match {
          case inner: Term => comparedInLambda(inner)
          case _           => None
        }
      case _ => None
    }
  }

  /** The literals of a match on the reason, if every case is `Some("...")`, `None` or `_`. */
  private def matchLiterals(cases: List[Case]): Option[List[Lit.String]] = {
    val each = cases.map(_.pat).map {
      case e: Pat.Extract if e.fun.syntax == "Some" && e.argClause.values.size == 1 =>
        e.argClause.values.head match {
          case lit: Lit.String => Some(List(lit))
          case _               => None
        }
      case Term.Name("None") | Pat.Wildcard() => Some(Nil)
      case _                                  => None
    }
    if (each.forall(_.isDefined)) Some(each.flatten.flatten) else None
  }

  private def isEquality(i: Term.ApplyInfix): Boolean =
    (i.op.value == "==" || i.op.value == "!=") && i.argClause.values.size == 1

  private def isRead(s: Term.Select)(implicit doc: SemanticDocument): Boolean =
    s.name.value == "finishReason" && Migration.symbolOf(s.name).exists(_.value == Chunk + "#finishReason.")

  /** The expression `t` is part of, up to the enclosing statement, definition body or case. */
  private def statementOf(t: Tree): Tree = t.parent match {
    case Some(_: Template | _: Source | _: Pkg | _: Defn | _: Case | _: Term.Block) | None => t
    case Some(p)                                                                           => statementOf(p)
  }

  private def within(outer: Tree, inner: Tree): Boolean =
    inner.pos.start >= outer.pos.start && inner.pos.end <= outer.pos.end
}

object FinishReasonTyped {

  private val Chunk = "org/llm4s/llmconnect/model/StreamedChunk"

  /** `finishReason`'s place among `StreamedChunk.apply`'s parameters. */
  private val FinishReasonIndex = 3

  private val Guide = Migration.guide10("finish-reasons-are-typed-2613")

  /** The chat-completions names, each with a case of its own. */
  private val Cases: Map[String, String] =
    Map("stop" -> "FinishReason.Stop", "tool_calls" -> "FinishReason.ToolCalls", "length" -> "FinishReason.Length")

  /**
   * The raw values 0.6.0's Bedrock and Gemini chunks carried, and the case RFC 0009 PR 2 maps each to. A Spring AI
   * model still passes its reason through as `FinishReason.Other(raw)`, so no one rewrite is right for both.
   */
  private val Vendor: Map[String, String] = Map(
    "end_turn"                      -> "FinishReason.Stop",
    "stop_sequence"                 -> "FinishReason.Stop",
    "tool_use"                      -> "FinishReason.ToolCalls",
    "max_tokens"                    -> "FinishReason.Length",
    "MAX_TOKENS"                    -> "FinishReason.Length",
    "model_context_window_exceeded" -> "FinishReason.Length"
  )

  /**
   * The content filters' values: a `ContentFilteredError` since RFC 0009 PR 2 from the chat-completions clients,
   * Anthropic, Bedrock and Gemini (the filter reasons #2664 lists), never a finish reason.
   */
  private val Filters: Set[String] = Set(
    "content_filter",
    "refusal",
    "guardrail_intervened",
    "content_filtered",
    "SAFETY",
    "RECITATION",
    "LANGUAGE",
    "BLOCKLIST",
    "PROHIBITED_CONTENT",
    "SPII",
    "IMAGE_SAFETY",
    "IMAGE_PROHIBITED_CONTENT",
    "IMAGE_RECITATION",
    "ESCALATION"
  )

  /** Gemini's `STOP`: `Stop`, or `ToolCalls` when the reply called a tool. */
  private val Ambiguous: Set[String] = Set("STOP")

  /** The report for a literal that no rewrite gets right, if it is one. */
  private def lint(lit: Lit.String): Option[Patch] = {
    def report(id: String, message: String) =
      Some(Patch.lint(Diagnostic(id, s"${lit.syntax} ${message} ${Guide}", lit.pos)))
    if (Filters(lit.value))
      report(
        "finishReasonIsAnError",
        "is a content filter's: a Left(ContentFilteredError) now from the OpenAI-format clients, Anthropic, Bedrock " +
          "and Gemini, never a finish reason, so match the error; a Spring AI model or watsonx still reports it as " +
          "FinishReason.Other(raw)."
      )
    else if (Ambiguous(lit.value))
      report(
        "finishReasonAmbiguous",
        "from Gemini or Vertex AI is FinishReason.Stop, or FinishReason.ToolCalls when the reply called a tool; from a " +
          "Spring AI model it is FinishReason.Other(\"STOP\"): compare with the one your client gives."
      )
    else
      Vendor.get(lit.value).flatMap { mapped =>
        report(
          "finishReasonVendorValue",
          s"from Bedrock or Gemini is ${mapped} now; a Spring AI model still gives FinishReason.Other(${lit.syntax}): " +
            "compare with the one your client gives."
        )
      }
  }

  private def typed(lit: Lit.String): String = Cases.getOrElse(lit.value, s"FinishReason.Other(${lit.syntax})")
}
