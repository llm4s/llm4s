package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * An accessor is named for the value it returns, and `ToolBuilder` has one `build` (#2908):
 *
 *   - `client.getContextWindow()` becomes `client.contextWindow` and `client.getReserveCompletion()` becomes
 *     `client.reserveCompletion`, without the parentheses; `client.getContextBudget(headroom)` becomes
 *     `client.contextBudget(headroom)`.
 *   - `def getContextWindow(): Int` and `def getReserveCompletion(): Int` in a class that extends something, which is
 *     how an `LLMClient` of your own defines them, are renamed the same way, with or without `override`, and so are
 *     the calls on that class in the same file.
 *   - `counter.getTokenBreakdown(conversation)` becomes `counter.tokenBreakdown(conversation)`, and
 *     `ModelDimensionRegistry.getDimension(provider, model)` becomes `ModelDimensionRegistry.dimension(provider, model)`.
 *   - `ToolBuilder(...).withHandler(...).buildSafe()` becomes `.build()`.
 *
 * A call of `getContextWindow()` on a client class of your own that is defined in another file is not rewritten
 * unless its type is `LLMClient` there; it fails to compile with "value getContextWindow is not a member", and
 * dropping `get` and the parentheses fixes it. A client whose constructor has a parameter named `contextWindow` or
 * `reserveCompletion` then defines the name twice: make the parameter the member, `override val contextWindow: Int`,
 * and delete the method.
 */
class AccessorRenames extends SemanticRule("AccessorRenames") {

  override def description: String =
    "Renames LLMClient.getContextWindow()/getReserveCompletion()/getContextBudget, getTokenBreakdown, getDimension and ToolBuilder.buildSafe"

  import AccessorRenames._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val definitions = doc.tree.collect { case d: Defn.Def if overridesAccessor(d) => d }
    val local       = definitions.flatMap(d => Migration.symbolOf(d.name).map(_.value)).toSet

    def renamed(name: Term.Name): Option[String] =
      Migration.symbolOf(name).map(_.value).flatMap { symbol =>
        Members
          .get(symbol)
          .orElse(Accessors.get(name.value).filter(_ => local(symbol) || inherited(symbol, name.value)))
      }

    def parameterless(name: Term.Name): Boolean = Parameterless(name.value) && renamed(name).isDefined

    doc.tree.collect {
      case n: Term.Name => renamed(n).fold(Patch.empty)(to => Patch.replaceTree(n, to))
      // `client.getContextWindow()`: the accessor has no parameter list
      case call: Term.Apply if call.argClause.values.isEmpty && calledName(call.fun).exists(parameterless) =>
        Patch.removeTokens(call.argClause.tokens)
      case d: Defn.Def if definitions.contains(d) =>
        d.paramClauseGroups.flatMap(_.paramClauses).map(clause => Patch.removeTokens(clause.tokens)).asPatch
    }.asPatch
  }

  private def calledName(fun: Term): Option[Term.Name] = fun match {
    case n: Term.Name        => Some(n)
    case select: Term.Select => Some(select.name)
    case _                   => None
  }

  /** `def getContextWindow(): Int` or `def getReserveCompletion(): Int` in a class, object or `new` that extends something. */
  private def overridesAccessor(d: Defn.Def): Boolean =
    Parameterless(d.name.value) &&
      d.paramClauseGroups.flatMap(_.paramClauses).forall(_.values.isEmpty) &&
      enclosingTemplate(d).exists(_.inits.nonEmpty)

  /** The template a member is in: its parent, or, where the tree has a template body, that body's. */
  private def enclosingTemplate(member: Tree): Option[Template] =
    member.parent.flatMap {
      case template: Template => Some(template)
      case body               => body.parent.collect { case template: Template => template }
    }
}

object AccessorRenames {

  /** `LLMClient`'s accessors that lost their parentheses with their `get`. */
  private val Parameterless = Set("getContextWindow", "getReserveCompletion")

  /** `LLMClient`'s accessors, which SemanticDB names by the class that defines or overrides them. */
  private val Accessors = Map(
    "getContextWindow"     -> "contextWindow",
    "getReserveCompletion" -> "reserveCompletion",
    "getContextBudget"     -> "contextBudget"
  )

  /** The renamed members that have one owner. */
  private val Members = Map(
    "org/llm4s/context/ConversationTokenCounter#getTokenBreakdown()."    -> "tokenBreakdown",
    "org/llm4s/llmconnect/config/ModelDimensionRegistry.getDimension()." -> "dimension",
    "org/llm4s/toolapi/ToolBuilder#buildSafe()."                         -> "build"
  )

  /** `LLMClient`'s accessor, or the one of an llm4s client or wrapper. */
  private def inherited(symbol: String, name: String): Boolean =
    symbol.startsWith("org/llm4s/") && symbol.endsWith(s"#$name().")
}
