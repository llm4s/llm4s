package org.llm4s.scalafix

import scalafix.v1._

/**
 * Every rule for the upgrade from llm4s 0.4.x to 0.5.x (docs/migrations/0-4-1-to-0-5-0.md), in one run:
 * [[CopyToWithSetters]], [[MillisToFiniteDuration]], [[IsRecoverableToTotal]], [[MovedConfigLoaders]],
 * [[MessageExtractorToTypePattern]] and the [[RemovedApis]] lints.
 */
class Llm4sMigrate_0_5 extends SemanticRule("Llm4sMigrate_0_5") {

  override def description: String =
    "Migrates code from llm4s 0.4.x to 0.5.x: every rewrite and lint of the 0.4.1 to 0.5.0 migration guide"

  private val rules: List[SemanticRule] = List(
    new CopyToWithSetters,
    new MillisToFiniteDuration,
    new IsRecoverableToTotal,
    new MovedConfigLoaders,
    new MessageExtractorToTypePattern,
    new RemovedApis
  )

  override def fix(implicit doc: SemanticDocument): Patch = rules.map(_.fix).asPatch
}
