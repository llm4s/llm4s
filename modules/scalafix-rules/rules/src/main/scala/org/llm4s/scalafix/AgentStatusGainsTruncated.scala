package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `AgentStatus` gains `Truncated(partialAnswer)`: a turn whose answer the model's output limit cut off
 * (`FinishReason.Length`) no longer ends `Completed` (RFC 0009, open question 6).
 *
 * It reports, and cannot rewrite, a `match` (or partial function) that names a case of `AgentStatus` and has no
 * catch-all case: it needs a `Truncated` case, or a catch-all, and only its author knows whether a cut-off answer is
 * to be shown, retried or refused. A match that already names `Truncated` is left alone.
 */
class AgentStatusGainsTruncated extends SemanticRule("AgentStatusGainsTruncated") {

  override def description: String =
    "Reports a match on AgentStatus with no catch-all, which needs a case for AgentStatus.Truncated"

  import AgentStatusGainsTruncated._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case m: Term.Match            => exhaustive(m, m.cases)
      case pf: Term.PartialFunction => exhaustive(pf, pf.cases)
    }.asPatch

  private def exhaustive(tree: Tree, cases: List[Case])(implicit doc: SemanticDocument): Patch = {
    val named    = cases.flatMap(c => statusCases(c.pat))
    val catchAll = cases.exists(c => c.cond.isEmpty && isCatchAll(c.pat))
    if (named.nonEmpty && !named.contains("Truncated") && !catchAll)
      Patch.lint(
        Diagnostic(
          "agentStatusTruncated",
          "AgentStatus gains Truncated(partialAnswer), for an answer cut off at the model's output limit, which " +
            "ended Completed before: add a case for it, or a catch-all: " + Guide,
          tree.pos
        )
      )
    else Patch.empty
  }

  /** The `AgentStatus` cases a pattern names, by their simple names. */
  private def statusCases(pat: Pat)(implicit doc: SemanticDocument): List[String] =
    pat.collect {
      case n: Term.Name => caseOf(n)
      case t: Type.Name => caseOf(t)
    }.flatten

  private def caseOf(tree: Tree)(implicit doc: SemanticDocument): Option[String] =
    Migration.symbolOf(tree).map(_.value).collect { case CaseSymbol(statusCase) => statusCase }

  private def isCatchAll(pat: Pat)(implicit doc: SemanticDocument): Boolean = pat match {
    case _: Pat.Wildcard | _: Pat.Var => true
    case Pat.Bind(_, inner)           => isCatchAll(inner)
    case Pat.Alternative(l, r)        => isCatchAll(l) || isCatchAll(r)
    // `s: AgentStatus` matches every case; `_: AgentStatus.Suspended` only one
    case Pat.Typed(_, tpe) =>
      tpe.collect { case t: Type.Name => Migration.symbolOf(t).map(_.value) }.flatten.contains(Status + "#")
    case _ => false
  }
}

object AgentStatusGainsTruncated {

  private val Status = "org/llm4s/agent/AgentStatus"

  /** A case of `AgentStatus`: its companion or class symbol, as a pattern or a type names it. */
  private val CaseSymbol = (java.util.regex.Pattern.quote(Status + ".") + """(\w+)[.#]""").r

  private val Guide = Migration.guide10("a-cut-off-answer-ends-truncated-not-completed-2613")
}
