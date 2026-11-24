package org.llm4s.scalafix

import scalafix.v1._

/**
 * Every rule that brings code compiled against llm4s 0.6.x to the current release, in one run: the rules for the
 * breaking changes since 0.6.0, [[ThinkingReplacesBudgetTokens]], [[FinishReasonTyped]], [[CompressionRuleTransform]],
 * [[AgentCopyToWithSetters]], [[ResultSyntaxMoved]], [[ProviderConfigNewtypes]], [[ToolFunctionOneTypeParameter]],
 * [[PositionalPatternsGainFields]], [[SamplingOptionsOptional]], [[CompletionFromMessage]],
 * [[MessageAndConversationShapes]], [[ClientEntryPoints]], [[ToolCallIdTyped]] and the [[AgentStatusGainsTruncated]]
 * lint. It grows as the
 * 1.0 breaks land (#1281).
 *
 * It leaves out the rules for the 0.5.1 breaks that 0.6.x code is already past (`CredentialsToSecret`,
 * `CompletionCreatedToInstant`, `FromValuesKeyFirst`, ...), which would rewrite it a second time; [[Llm4sMigrateFrom_0_5]]
 * runs those too. Each starting version has one umbrella rule, so no rule runs twice
 * (docs/reference/scalafix.md#migration-rules).
 */
class Llm4sMigrateFrom_0_6 extends SemanticRule("Llm4sMigrateFrom_0_6") {

  override def description: String =
    "Migrates code from llm4s 0.6.x to the current release: every rewrite and lint for the breaking changes since 0.6.0"

  private val rules: List[SemanticRule] = List(
    new PositionalPatternsGainFields,
    new ThinkingReplacesBudgetTokens,
    new FinishReasonTyped,
    new CompressionRuleTransform,
    new AgentCopyToWithSetters,
    new AgentStatusGainsTruncated,
    new ResultSyntaxMoved,
    new ProviderConfigNewtypes,
    new SamplingOptionsOptional,
    new CompletionFromMessage,
    new MessageAndConversationShapes,
    new ClientEntryPoints,
    new ToolFunctionOneTypeParameter,
    new ToolCallIdTyped
  )

  override def fix(implicit doc: SemanticDocument): Patch = rules.map(_.fix).asPatch
}
