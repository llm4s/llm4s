package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `CompressionRule`'s function field is `transform`, not `apply` (#1281), and its setter `withTransform`, not
 * `withApply`. The rule renames:
 *
 *   - a read of the field, `rule.apply` and `rule.apply(messages)`, to `rule.transform`;
 *   - `withApply(f)` to `withTransform(f)`;
 *   - the named argument `apply = f` of `CompressionRule(...)` and of `withApply` to `transform = f`.
 */
class CompressionRuleTransform extends SemanticRule("CompressionRuleTransform") {

  override def description: String =
    "Renames CompressionRule's apply field and withApply setter (llm4s 0.6.0) to transform and withTransform"

  import CompressionRuleTransform._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect { case n: Term.Name =>
      Migration.symbolOf(n).map(_.value) match {
        case Some(Field)                                    => Patch.replaceTree(n, "transform")
        case Some(Setter)                                   => Patch.replaceTree(n, "withTransform")
        case Some(s) if NamedArguments.exists(s.startsWith) => Patch.replaceTree(n, "transform")
        case _                                              => Patch.empty
      }
    }.asPatch
}

object CompressionRuleTransform {
  private val Type   = "org/llm4s/context/CompressionRule#"
  private val Field  = Type + "apply."
  private val Setter = Type + "withApply()."

  /** The `apply` parameters of `CompressionRule.apply`, its constructor and `withApply`. */
  private val NamedArguments = List(
    "org/llm4s/context/CompressionRule.apply().(apply)",
    Type + "`<init>`().(apply)",
    Type + "withApply().(apply)"
  )
}
