package org.llm4s.scalafix

import scalafix.v1._

/**
 * Every rule for the upgrade from llm4s 0.5.x to the next release (the breaking changes since 0.5.1 in
 * docs/reference/migration.md), in one run: [[CopyToWithSetters_1_0]], [[CredentialsToSecret]],
 * [[FromValuesKeyFirst]], [[CompletionCreatedToInstant]], [[PositionalPatternsGainFields]], [[DuplicateApisRemoved]]
 * and the [[RemovedApis_1_0]] lints. It grows as the 1.0 breaks land (#1281).
 */
class Llm4sMigrate_1_0 extends SemanticRule("Llm4sMigrate_1_0") {

  override def description: String =
    "Migrates code from llm4s 0.5.x toward 1.0: every rewrite and lint for the breaking changes since 0.5.1"

  private val rules: List[SemanticRule] = List(
    new CopyToWithSetters_1_0,
    new CredentialsToSecret,
    new FromValuesKeyFirst,
    new CompletionCreatedToInstant,
    new PositionalPatternsGainFields,
    new DuplicateApisRemoved,
    new RemovedApis_1_0
  )

  override def fix(implicit doc: SemanticDocument): Patch = rules.map(_.fix).asPatch
}
