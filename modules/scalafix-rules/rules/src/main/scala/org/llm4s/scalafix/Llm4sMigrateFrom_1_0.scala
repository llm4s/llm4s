package org.llm4s.scalafix

import scalafix.v1._

/**
 * Every rule that brings code compiled against llm4s 1.0.x to the current release, in one run.
 *
 * 1.0.0 starts the compatibility promise. A Stable API of a frozen module does not break in 1.x, but a growth-prone
 * class gains constructor fields, which breaks a caller's positional pattern: [[PositionalPatternsGainFields]], with
 * [[PositionalPatternsGainFields.arity10]], gives `OpenAIConfig` (11 to 14 fields) and `ModelMetadata` (8 to 9) a `_`
 * for each (#2900). A rule for a break in an Experimental API or a Beta or Experimental module after 1.0.0 joins this
 * list and that of every older umbrella ([[Llm4sMigrateFrom_0_7]], [[Llm4sMigrateFrom_0_6]], [[Llm4sMigrateFrom_0_5]]).
 *
 * It leaves out the rules for the breaks up to 1.0.0, which 1.0.x code is already past and which would rewrite it a
 * second time. Each starting version has one umbrella rule, so no rule runs twice
 * (docs/reference/scalafix.md#migration-rules).
 */
class Llm4sMigrateFrom_1_0 extends SemanticRule("Llm4sMigrateFrom_1_0") {

  override def description: String =
    "Migrates code from llm4s 1.0.x to the current release: every rewrite and lint for the breaking changes since 1.0.0"

  private val rules: List[SemanticRule] = List(new PositionalPatternsGainFields(List(PositionalPatternsGainFields.arity10)))

  override def fix(implicit doc: SemanticDocument): Patch = rules.map(_.fix).asPatch
}
