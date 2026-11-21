package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `CompletionOptions.budgetTokens` and `ReasoningEffort.None` are removed; `CompletionOptions.thinking` replaces both
 * (#2115, RFC 0008 PR 4).
 *
 * `options.withReasoning(ReasoningEffort.None)` (or `Some(ReasoningEffort.None)`) becomes
 * `options.withThinking(Thinking.Off)`, and `CompletionOptions(reasoning = Some(ReasoningEffort.None), ...)` becomes
 * `CompletionOptions(thinking = Some(Thinking.Off), ...)`; `import org.llm4s.llmconnect.model.Thinking` is added where
 * it is missing. On OpenAI `Thinking.Off` asks for the lowest effort where `None` sent none, as the migration note
 * says. `Thinking.Off` takes precedence over `reasoning`, where `None` was only a `reasoning` level, so a call whose
 * chain sets `withReasoning` again afterwards is reported instead. One reset later through a `val` is out of the
 * rule's sight: the migration note says to check it. It reports what it cannot rewrite:
 *
 *   - any other use of `ReasoningEffort.None`, the overridden one above among them;
 *   - `budgetTokens`, `withBudgetTokens` and the `budgetTokens` argument of `CompletionOptions(...)`: their
 *     replacement, `Thinking.budget(n)`, is a `Result`, so the expression changes type;
 *   - a `match` on `ReasoningEffort` with no catch-all case, which needs cases for `Minimal`, `XHigh` and `Max`.
 */
class ThinkingReplacesBudgetTokens extends SemanticRule("ThinkingReplacesBudgetTokens") {

  override def description: String =
    "Rewrites ReasoningEffort.None (llm4s 0.6.0) to Thinking.Off, and reports budgetTokens and matches on ReasoningEffort"

  import ThinkingReplacesBudgetTokens._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val rewrites  = doc.tree.collect { case t: Term.Apply if !t.fun.is[Term.Apply] => rewrite(t) }.flatten
    val rewritten = rewrites.map(_._1)
    val lints = doc.tree.collect {
      case n: Term.Name if !inImport(n) && !rewritten.exists(r => contains(r, n)) =>
        Migration.symbolOf(n).map(_.value).flatMap(removal).fold(Patch.empty) { case (id, message) =>
          Patch.lint(Diagnostic(id, message, n.pos))
        }
      case m: Term.Match            => exhaustive(m, m.cases)
      case pf: Term.PartialFunction => exhaustive(pf, pf.cases)
    }
    val patch = (rewrites.map(_._2) ++ lints).asPatch
    if (rewrites.isEmpty) patch
    else patch + Migration.importOnce("org.llm4s.llmconnect.model", "Thinking")
  }

  /** A rewrite of `withReasoning(None)` or `CompletionOptions(reasoning = Some(None))`, and the `None` it replaces. */
  private def rewrite(t: Term.Apply)(implicit doc: SemanticDocument): Option[(Tree, Patch)] =
    if (laterSetsReasoning(t)) None
    else
      Migration.callee(t).flatMap { callee =>
        val args = t.argClause.values
        if (callee.startsWith(Options + "#withReasoning("))
          (t.fun, args) match {
            case (Term.Select(_, name), List(arg)) if isNone(arg) || isSomeNone(arg) =>
              Some(arg -> (Patch.replaceTree(name, "withThinking") + Patch.replaceTree(arg, "Thinking.Off")))
            case _ => None
          }
        else if (Migration.constructs(callee, Options) && !args.exists(isNamed(_, "thinking")))
          args.collectFirst {
            case a @ Term.Assign(Term.Name("reasoning"), rhs) if isSomeNone(rhs) =>
              a -> Patch.replaceTree(a, "thinking = Some(Thinking.Off)")
          }
        else None
      }

  /**
   * Whether a later call in the same chain sets `reasoning` again. `ReasoningEffort.None` was a `reasoning` level, so
   * a later `withReasoning(High)` replaced it; `Thinking.Off` is a separate field that takes precedence over
   * `reasoning`, so the rewrite would turn thinking off where the old chain asked for high effort.
   */
  private def laterSetsReasoning(t: Term): Boolean = t.parent match {
    case Some(s @ Term.Select(qual, name)) if qual eq t =>
      name.value == "withReasoning" || name.value == "withThinking" ||
      s.parent.exists {
        case a: Term.Apply if a.fun eq s => laterSetsReasoning(a)
        case _                           => laterSetsReasoning(s)
      }
    case _ => false
  }

  /** A `match` (or partial function) on `ReasoningEffort` with no case for anything it does not name. */
  private def exhaustive(tree: Tree, cases: List[Case])(implicit doc: SemanticDocument): Patch = {
    val onEffort = cases.exists(c =>
      c.pat.collect { case n: Term.Name => Migration.symbolOf(n).map(_.value) }.flatten.exists(Levels.contains)
    )
    val catchAll = cases.exists(c => c.cond.isEmpty && isCatchAll(c.pat))
    if (onEffort && !catchAll)
      Patch.lint(
        Diagnostic(
          "reasoningEffortNewLevels",
          "ReasoningEffort gains Minimal, XHigh and Max: add cases for them, or a catch-all: " + Guide,
          tree.pos
        )
      )
    else Patch.empty
  }

  private def isCatchAll(pat: Pat): Boolean = pat match {
    case _: Pat.Wildcard | _: Pat.Var => true
    case Pat.Bind(_, inner)           => isCatchAll(inner)
    case Pat.Typed(_, _)              => true
    case _                            => false
  }

  private def isNone(t: Term)(implicit doc: SemanticDocument): Boolean =
    Migration.symbolOf(t).exists(_.value == EffortNone)

  private def isSomeNone(t: Term)(implicit doc: SemanticDocument): Boolean = t match {
    case a: Term.Apply if CopyToSetters.isSome(a) => isNone(a.argClause.values.head)
    case _                                        => false
  }

  private def isNamed(arg: Term, name: String): Boolean = arg match {
    case Term.Assign(Term.Name(n), _) => n == name
    case _                            => false
  }

  private def contains(outer: Tree, inner: Tree): Boolean =
    inner.pos.start >= outer.pos.start && inner.pos.end <= outer.pos.end

  /** An import names a removed member without using it; the uses are reported. */
  private def inImport(tree: Tree): Boolean =
    tree.parent.exists(p => p.is[Import] || inImport(p))
}

object ThinkingReplacesBudgetTokens {

  private val Model      = "org/llm4s/llmconnect/model/"
  private val Options    = Model + "CompletionOptions"
  private val Effort     = Model + "ReasoningEffort."
  private val EffortNone = Effort + "None."
  private val Levels     = Set("None.", "Low.", "Medium.", "High.").map(Effort + _)

  private val Guide = Migration.guide10("budgettokens-and-reasoningeffortnone-are-replaced-by-thinking-2115")

  private val BudgetTokens =
    ("""org/llm4s/llmconnect/model/CompletionOptions(#budgetTokens\.|#withBudgetTokens\(\+?\d*\)\.|""" +
      """[.#](apply|copy|`<init>`)\(\+?\d*\)\.\(budgetTokens\))""").r

  /** The lint id and message for a use of a removed symbol, if it is one. */
  def removal(symbol: String): Option[(String, String)] =
    if (symbol == EffortNone)
      Some(
        "reasoningEffortNoneRemoved" ->
          ("ReasoningEffort.None is removed: use CompletionOptions.withThinking(Thinking.Off); on OpenAI it asks for " +
            "the lowest effort where None sent none, so to keep the model's default set neither: " + Guide)
      )
    else if (BudgetTokens.pattern.matcher(symbol).matches())
      Some(
        "budgetTokensRemoved" ->
          ("CompletionOptions.budgetTokens is removed: Thinking.budget(n).map(options.withThinking) is a " +
            "Result[CompletionOptions], since a budget of 0 or less is a ValidationError: " + Guide)
      )
    else None
}
