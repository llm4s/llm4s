package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `ToolFunction` and `ToolBuilder` have one type parameter, the handler's result (#2815). The first, a phantom type
 * for the arguments that nothing read, is gone. The rule drops it wherever both are written:
 *
 *   - a type: `ToolFunction[Map[String, Any], String]` to `ToolFunction[String]`, `Seq[ToolFunction[_, _]]` to
 *     `Seq[ToolFunction[_]]`, and the same in a type pattern;
 *   - a call: `ToolBuilder[Map[String, Any], String](name, description, schema)` to `ToolBuilder[String](...)`, and
 *     `ToolFunction[A, R](...)` to `ToolFunction[R](...)`.
 *
 * A type parameter of the caller's own that only named the arguments (`def tool[T, R](...): ToolFunction[T, R]`) is
 * left declared: it is unused afterwards and can be deleted by hand.
 */
class ToolFunctionOneTypeParameter extends SemanticRule("ToolFunctionOneTypeParameter") {

  override def description: String =
    "Drops the arguments type parameter of ToolFunction and ToolBuilder (llm4s 0.6.0), which keep only the result's"

  import ToolFunctionOneTypeParameter._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case t: Type.Apply if isTool(t.tpe)     => dropFirst(t.argClause.values)
      case t: Term.ApplyType if isTool(t.fun) => dropFirst(t.targClause.values)
    }.asPatch

  /** Whether `tree` names `ToolFunction` or `ToolBuilder`, the class or its companion, qualified or not. */
  private def isTool(tree: Tree)(implicit doc: SemanticDocument): Boolean = {
    val name = tree match {
      case s: Term.Select => s.name
      case s: Type.Select => s.name
      case other          => other
    }
    Migration.symbolOf(name).exists(s => Owners(Migration.owner(s)))
  }

  /** Removes the first of two type arguments, with the comma and space that follow it. */
  private def dropFirst(args: List[Type])(implicit doc: SemanticDocument): Patch = args match {
    case List(first, second) =>
      Patch.removeTokens(doc.tree.tokens.filter(t => t.pos.start >= first.pos.start && t.pos.end <= second.pos.start))
    case _ => Patch.empty
  }
}

object ToolFunctionOneTypeParameter {
  private val Owners = Set("org/llm4s/toolapi/ToolFunction", "org/llm4s/toolapi/ToolBuilder")
}
