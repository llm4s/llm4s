package org.llm4s.scalafix

import scalafix.v1._

/**
 * Every rule that brings code compiled against llm4s 0.5.x to the current release, in one run: the rules for the
 * breaking changes since 0.5.1 ([[CopyToWithSetters_1_0]], [[CredentialsToSecret]], [[FromValuesKeyFirst]],
 * [[CompletionCreatedToInstant]], [[PositionalPatternsGainFields]], [[DuplicateApisRemoved]], the [[RemovedApis_1_0]]
 * lints) and every rule of [[Llm4sMigrateFrom_0_6]]. Its [[PositionalPatternsGainFields]] takes an `OpenAIConfig` pattern of
 * 0.5.1's nine fields to the current fourteen in one pass (#2900). It grows as the 1.0 breaks land (#1281).
 *
 * It assumes 0.5.x code: `CredentialsToSecret`, `CompletionCreatedToInstant` and `FromValuesKeyFirst` would rewrite
 * 0.6.x code a second time. Code on 0.6.x runs [[Llm4sMigrateFrom_0_6]] (docs/reference/scalafix.md#migration-rules).
 */
class Llm4sMigrateFrom_0_5 extends SemanticRule("Llm4sMigrateFrom_0_5") {

  override def description: String =
    "Migrates code from llm4s 0.5.x to the current release: every rewrite and lint for the breaking changes since 0.5.1"

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
    new ProviderConfigNewtypes,
    new ModelIdNewtypes(FromValuesKeyFirst.configs),
    new SamplingOptionsOptional,
    new CompletionFromMessage,
    new MessageAndConversationShapes,
    new ClientEntryPoints,
    new ToolFunctionOneTypeParameter,
    new ToolCallIdTyped,
    new AccessorRenames,
    new ErrorIsRetryable,
    new StreamedChunkDeltaNames,
    new ErrorProviderIdTyped
  )

  override def fix(implicit doc: SemanticDocument): Patch = rules.map(_.fix).asPatch
}
