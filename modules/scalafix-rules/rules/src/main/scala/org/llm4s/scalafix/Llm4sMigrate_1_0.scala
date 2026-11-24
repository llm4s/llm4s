package org.llm4s.scalafix

import scalafix.v1._

/**
 * Every rule for the upgrade from llm4s 0.5.x to the next release (the breaking changes since 0.5.1 in
 * docs/reference/migration.md), in one run: [[CopyToWithSetters_1_0]], [[CredentialsToSecret]],
 * [[FromValuesKeyFirst]], [[CompletionCreatedToInstant]], [[PositionalPatternsGainFields]], [[DuplicateApisRemoved]],
 * the [[RemovedApis_1_0]] lints, and for the breaks since 0.6.0 [[ThinkingReplacesBudgetTokens]],
 * [[FinishReasonTyped]], [[CompressionRuleTransform]], [[AgentCopyToWithSetters]], [[ResultSyntaxMoved]],
 * [[ToolFunctionOneTypeParameter]] and the [[AgentStatusGainsTruncated]] lint. It grows as the 1.0 breaks land (#1281).
 *
 * It assumes code compiled against 0.5.x: `CredentialsToSecret`, `CompletionCreatedToInstant` and
 * `FromValuesKeyFirst` would rewrite 0.6.x code a second time. Code on 0.6.x runs the rules for the breaks since 0.6.0
 * by name instead (docs/reference/scalafix.md#migration-rules).
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
    new RemovedApis_1_0,
    new ThinkingReplacesBudgetTokens,
    new FinishReasonTyped,
    new CompressionRuleTransform,
    new AgentCopyToWithSetters,
    new AgentStatusGainsTruncated,
    new ResultSyntaxMoved,
    new SamplingOptionsOptional,
    new CompletionFromMessage,
    new MessageAndConversationShapes,
    new ClientEntryPoints,
    new ToolFunctionOneTypeParameter
  )

  override def fix(implicit doc: SemanticDocument): Patch = rules.map(_.fix).asPatch
}
