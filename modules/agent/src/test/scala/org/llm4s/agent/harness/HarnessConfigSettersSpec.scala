package org.llm4s.agent.harness

import org.llm4s.agent.{ Agent, ScriptedLLMClient }
import org.llm4s.agent.graph.middleware.ModelContext
import org.llm4s.agent.harness.HarnessFiles.p
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.Message
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The defaults and `with*` setters of the harness config types in `HarnessProfile.scala`. Each setter rebuilds the
 * value by position, so every setter is checked from a value whose fields all differ, where a swapped argument shows.
 */
class HarnessConfigSettersSpec extends AnyFlatSpec with Matchers with EitherValues {

  final private class FixedCompressor(summary: String) extends SummaryCompressor:
    def compress(
      previous: Option[String],
      messages: Vector[Message],
      maxTokens: Int,
      context: ModelContext
    ): Result[String] = Right(summary)

  private val compressorA: SummaryCompressor = new FixedCompressor("a")
  private val compressorB: SummaryCompressor = new FixedCompressor("b")

  private val todo          = TodoConfig(maxTodos = 3, maxTodoChars = 5)
  private val summarization = SummarizationConfig(SummaryTrigger.Messages(3), 5, 7, Some(compressorA))
  private val offload       = OffloadConfig(thresholdTokens = 3, maxResultBytes = 5L, maxTotalBytes = 7L)
  private val skills        = SkillsConfig(Vector(p("/my-skills")), 3, 5, 7, 11, 13)
  private val instructions  = InstructionsConfig(Vector(p("/RULES.md")), 3, 5, 7)

  // ==========================================================================
  // TodoConfig
  // ==========================================================================

  "TodoConfig()" should "have the TodoMiddleware defaults" in {
    TodoConfig().maxTodos shouldBe TodoMiddleware.DefaultMaxTodos
    TodoConfig().maxTodoChars shouldBe TodoMiddleware.DefaultMaxTodoChars
  }

  "TodoConfig setters" should "each change only their own field" in {
    todo.withMaxTodos(17) shouldBe TodoConfig(17, 5)
    todo.withMaxTodoChars(17) shouldBe TodoConfig(3, 17)
  }

  it should "chain to the same value as apply" in {
    todo.withMaxTodos(17).withMaxTodoChars(19) shouldBe TodoConfig(17, 19)
  }

  // ==========================================================================
  // SummarizationConfig
  // ==========================================================================

  "SummarizationConfig()" should "have the SummarizationMiddleware defaults and no compressor" in {
    val config = SummarizationConfig()
    config.trigger shouldBe SummaryTrigger.Tokens(SummarizationConfig.DefaultTriggerTokens)
    config.keepRecent shouldBe SummarizationMiddleware.DefaultKeepRecent
    config.maxSummaryTokens shouldBe SummarizationMiddleware.DefaultMaxSummaryTokens
    config.compressor shouldBe None
  }

  "SummarizationConfig setters" should "each change only their own field" in {
    summarization.withTrigger(SummaryTrigger.Tokens(17)) shouldBe
      SummarizationConfig(SummaryTrigger.Tokens(17), 5, 7, Some(compressorA))
    summarization.withKeepRecent(17) shouldBe SummarizationConfig(SummaryTrigger.Messages(3), 17, 7, Some(compressorA))
    summarization.withMaxSummaryTokens(17) shouldBe
      SummarizationConfig(SummaryTrigger.Messages(3), 5, 17, Some(compressorA))
    summarization.withCompressor(compressorB) shouldBe
      SummarizationConfig(SummaryTrigger.Messages(3), 5, 7, Some(compressorB))
  }

  it should "agree between the two withCompressor overloads, and clear the compressor with None" in {
    summarization.withCompressor(Some(compressorB)) shouldBe summarization.withCompressor(compressorB)
    summarization.withCompressor(None) shouldBe SummarizationConfig(SummaryTrigger.Messages(3), 5, 7, None)
  }

  it should "chain to the same value as apply" in {
    summarization
      .withTrigger(SummaryTrigger.Tokens(17))
      .withKeepRecent(19)
      .withMaxSummaryTokens(23)
      .withCompressor(compressorB) shouldBe SummarizationConfig(SummaryTrigger.Tokens(17), 19, 23, Some(compressorB))
  }

  // ==========================================================================
  // OffloadConfig
  // ==========================================================================

  "OffloadConfig()" should "have the ToolOutputOffload defaults" in {
    OffloadConfig().thresholdTokens shouldBe ToolOutputOffload.DefaultThresholdTokens
    OffloadConfig().maxResultBytes shouldBe ToolOutputOffload.DefaultMaxResultBytes
    OffloadConfig().maxTotalBytes shouldBe ToolOutputOffload.DefaultMaxTotalBytes
  }

  "OffloadConfig setters" should "each change only their own field" in {
    offload.withThresholdTokens(17) shouldBe OffloadConfig(17, 5L, 7L)
    offload.withMaxResultBytes(17L) shouldBe OffloadConfig(3, 17L, 7L)
    offload.withMaxTotalBytes(17L) shouldBe OffloadConfig(3, 5L, 17L)
  }

  it should "chain to the same value as apply" in {
    offload.withThresholdTokens(17).withMaxResultBytes(19L).withMaxTotalBytes(23L) shouldBe
      OffloadConfig(17, 19L, 23L)
  }

  // ==========================================================================
  // SkillsConfig
  // ==========================================================================

  "SkillsConfig()" should "read /skills with the SkillsMiddleware defaults" in {
    val config = SkillsConfig()
    config.sources shouldBe Vector(SkillsConfig.DefaultSource)
    SkillsConfig.DefaultSource shouldBe p("/skills")
    config.maxSkills shouldBe SkillsMiddleware.DefaultMaxSkills
    config.maxDescriptionChars shouldBe SkillsMiddleware.DefaultMaxDescriptionChars
    config.maxSectionTokens shouldBe SkillsMiddleware.DefaultMaxSectionTokens
    config.maxScanned shouldBe SkillsMiddleware.DefaultMaxScanned
    config.maxCachedRuns shouldBe SkillsMiddleware.DefaultMaxCachedRuns
  }

  "SkillsConfig setters" should "each change only their own field" in {
    val other = Vector(p("/other"), p("/more"))
    skills.withSources(other) shouldBe SkillsConfig(other, 3, 5, 7, 11, 13)
    skills.withMaxSkills(17) shouldBe SkillsConfig(Vector(p("/my-skills")), 17, 5, 7, 11, 13)
    skills.withMaxDescriptionChars(17) shouldBe SkillsConfig(Vector(p("/my-skills")), 3, 17, 7, 11, 13)
    skills.withMaxSectionTokens(17) shouldBe SkillsConfig(Vector(p("/my-skills")), 3, 5, 17, 11, 13)
    skills.withMaxScanned(17) shouldBe SkillsConfig(Vector(p("/my-skills")), 3, 5, 7, 17, 13)
    skills.withMaxCachedRuns(17) shouldBe SkillsConfig(Vector(p("/my-skills")), 3, 5, 7, 11, 17)
  }

  it should "chain to the same value as apply" in {
    val other = Vector(p("/other"))
    skills
      .withSources(other)
      .withMaxSkills(17)
      .withMaxDescriptionChars(19)
      .withMaxSectionTokens(23)
      .withMaxScanned(29)
      .withMaxCachedRuns(31) shouldBe SkillsConfig(other, 17, 19, 23, 29, 31)
  }

  // ==========================================================================
  // InstructionsConfig
  // ==========================================================================

  "InstructionsConfig()" should "read /AGENTS.md with the InstructionMemoryMiddleware defaults" in {
    val config = InstructionsConfig()
    config.paths shouldBe Vector(InstructionsConfig.DefaultPath)
    InstructionsConfig.DefaultPath shouldBe p("/AGENTS.md")
    config.maxFileTokens shouldBe InstructionMemoryMiddleware.DefaultMaxFileTokens
    config.maxTotalTokens shouldBe InstructionMemoryMiddleware.DefaultMaxTotalTokens
    config.maxCachedRuns shouldBe InstructionMemoryMiddleware.DefaultMaxCachedRuns
  }

  "InstructionsConfig setters" should "each change only their own field" in {
    val other = Vector(p("/OTHER.md"), p("/MORE.md"))
    instructions.withPaths(other) shouldBe InstructionsConfig(other, 3, 5, 7)
    instructions.withMaxFileTokens(17) shouldBe InstructionsConfig(Vector(p("/RULES.md")), 17, 5, 7)
    instructions.withMaxTotalTokens(17) shouldBe InstructionsConfig(Vector(p("/RULES.md")), 3, 17, 7)
    instructions.withMaxCachedRuns(17) shouldBe InstructionsConfig(Vector(p("/RULES.md")), 3, 5, 17)
  }

  it should "chain to the same value as apply" in {
    val other = Vector(p("/OTHER.md"))
    instructions.withPaths(other).withMaxFileTokens(17).withMaxTotalTokens(19).withMaxCachedRuns(23) shouldBe
      InstructionsConfig(other, 17, 19, 23)
  }

  // ==========================================================================
  // Validation
  // ==========================================================================

  "The setters" should "not validate: a bad value is refused only when the agent is built" in {
    val zero = TodoConfig().withMaxTodos(0)
    zero.maxTodos shouldBe 0
    val error = Agent
      .builder("assistant", ScriptedLLMClient.of())
      .withHarness(HarnessProfile.deepAgents.withTodos(zero))
      .build()
      .left
      .value
    error shouldBe a[ValidationError]
    error.message should include("harness.todos")
    error.message should include("maxTodos")
  }
}
