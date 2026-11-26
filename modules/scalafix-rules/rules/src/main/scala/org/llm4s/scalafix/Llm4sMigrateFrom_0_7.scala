package org.llm4s.scalafix

import scalafix.v1._

/**
 * Every rule that brings code compiled against llm4s 0.7.x to the current release, in one run.
 *
 * 0.7.0 is the release the compatibility baseline is set at, so the list is short: [[ErrorProviderIdTyped]] and
 * [[PositionalPatternsGainFields]] with [[PositionalPatternsGainFields.arity10]] (0.7.0's `OpenAIConfig` and
 * `ModelMetadata` have 1.0.0's arities, so a positional pattern on either gets a `_` for each field gained since, #2900).
 * A rule for a break after 0.7.0 joins this list and that of every older umbrella ([[Llm4sMigrateFrom_0_6]],
 * [[Llm4sMigrateFrom_0_5]]).
 *
 * It leaves out the rules for the breaks up to 0.7.0, which 0.7.x code is already past and which would rewrite it a
 * second time. Each starting version has one umbrella rule, so no rule runs twice
 * (docs/reference/scalafix.md#migration-rules).
 */
class Llm4sMigrateFrom_0_7 extends SemanticRule("Llm4sMigrateFrom_0_7") {

  override def description: String =
    "Migrates code from llm4s 0.7.x to the current release: every rewrite and lint for the breaking changes since 0.7.0"

  private val rules: List[SemanticRule] = List(
    new ErrorProviderIdTyped,
    new PositionalPatternsGainFields(List(PositionalPatternsGainFields.arity10))
  )

  override def fix(implicit doc: SemanticDocument): Patch = rules.map(_.fix).asPatch
}
