package org.llm4s.config

import org.llm4s.agent.graph.RetentionPolicy
import org.llm4s.agent.harness.*
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/** Stage 3 slice P1 (#1915, design §4.37): `HarnessConfigLoader` over `llm4s-agent`'s `reference.conf`. */
class HarnessConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def load(hocon: String): Result[HarnessProfile] =
    HarnessConfigLoader.load(ReferenceConfig.withEnv(hocon, Map.empty))

  /** Every setting the loader reads, so two profiles compare without their backends. */
  private def settings(p: HarnessProfile) =
    (
      p.sandbox,
      p.tools,
      p.todos,
      p.summarization,
      p.offload,
      p.skills,
      p.instructions,
      p.systemPrompt,
      p.permissions,
      p.toolTimeout,
      p.retention,
      p.subagents
    )

  "The reference.conf defaults" should "be the deepAgents preset's settings" in {
    settings(load("").value) shouldBe settings(HarnessProfile.deepAgents)
  }

  "Overrides" should "change the settings they name, and only those" in {
    val loaded = load(
      """llm4s.agent.harness {
        |  tools { mode = "only", names = ["read_file", "ls"] }
        |  toolTimeout = "90 seconds"
        |  systemPrompt { mode = "suffix", text = "Be brief." }
        |  todos.enabled = false
        |  summarization { trigger = "messages", max = 40, keepRecent = 4 }
        |  offload.thresholdTokens = 5000
        |  skills.sources = ["/user/skills", "/project/skills"]
        |  instructions.paths = ["/user/AGENTS.md"]
        |  retention { maxCheckpoints = 5, maxAge = "7 days" }
        |}""".stripMargin
    ).value
    loaded.tools shouldBe ToolSelection.Only(Set("read_file", "ls"))
    loaded.toolTimeout shouldBe Some(90.seconds)
    loaded.systemPrompt shouldBe PromptMode.Suffix("Be brief.")
    loaded.todos shouldBe None
    loaded.summarization shouldBe Some(SummarizationConfig(SummaryTrigger.Messages(40), keepRecent = 4))
    loaded.offload shouldBe Some(OffloadConfig(thresholdTokens = 5000))
    loaded.skills.map(_.sources.map(_.value)) shouldBe Some(Vector("/user/skills", "/project/skills"))
    loaded.instructions.map(_.paths.map(_.value)) shouldBe Some(Vector("/user/AGENTS.md"))
    loaded.retention shouldBe RetentionPolicy(maxAge = Some(7.days), maxCheckpoints = Some(5))
  }

  "execute selected in configuration" should "load, and build once the sandbox is set in code" in {
    val loaded = load("""llm4s.agent.harness.tools { mode = "only", names = ["execute", "read_file"] }""").value
    loaded.assembled.left.value.message should include("needs a sandbox")
    val runner = new SandboxRunner {
      def locks(workdir: VPath) = Set(org.llm4s.agent.graph.tool.LockKey("sandbox"))
      def run(command: String, workdir: VPath, timeout: FiniteDuration, maxOutputBytes: Int, ctx: FileContext) =
        Right(ExecResult(0, "", ""))
    }
    loaded.withSandbox(SandboxBackend(runner)).assembled.value.tools.map(_.spec.name) shouldBe
      Vector("read_file", "execute")
  }

  "A bad value" should "be a ConfigurationError naming its key, at the app edge" in {
    def refused(hocon: String, mentions: String) = {
      val error = load(s"llm4s.agent.harness { $hocon }").left.value
      error shouldBe a[ConfigurationError]
      error.message should include(mentions)
    }
    refused("""tools.mode = "some"""", "llm4s.agent.harness.tools")
    refused("""tools { mode = "only", names = ["read_flie"] }""", "'read_flie' is not a harness tool")
    refused("""tools { mode = "all", names = ["ls"] }""", "names must be empty")
    refused("""todos.maxTodos = 0""", "maxTodos must be at least 1")
    refused("""summarization.trigger = "words"""", "trigger must be tokens or messages")
    refused("""summarization.max = 0""", "llm4s.agent.harness.summarization")
    refused("""skills.sources = ["relative"]""", "llm4s.agent.harness.skills")
    refused("""systemPrompt { mode = "replace", text = "" }""", "blank")
    refused("""toolTimeout = "soon"""", "llm4s.agent.harness.toolTimeout")
    refused("""retention.maxCheckpoints = 0""", "maxCheckpoints must be at least 1")
    refused("""offload.maxTotalBytes = "lots"""", "llm4s.agent.harness.offload")
    refused("""subagents.maxDepth = 0""", "maxDepth must be positive")
    refused("""subagents.maxReportBytes = 10""", "maxReportBytes must be at least 64")
    refused("""subagents.maxTasksPerTurn = "many"""", "llm4s.agent.harness.subagents")
    refused("""subagents.generalPurpose.enabled = "maybe"""", "llm4s.agent.harness.subagents.generalPurpose.enabled")
  }

  "Subagent settings" should "default to the general-purpose subagent under the default bounds (#2052)" in {
    load("").value.subagents shouldBe SubagentConfig.deepAgents
  }

  they should "turn the general-purpose subagent off and change each bound they name" in {
    val loaded = load(
      """llm4s.agent.harness.subagents {
        |  generalPurpose.enabled = false
        |  maxConcurrentChildren = 2
        |  maxTasksPerTurn = 8
        |  maxDepth = 2
        |  maxReportBytes = 4096
        |  childRetention = 10
        |}""".stripMargin
    ).value
    loaded.subagents.generalPurpose shouldBe None
    loaded.subagents.specialists shouldBe empty
    loaded.subagents.delegation shouldBe
      org.llm4s.agent.delegation.DelegationConfig(
        maxConcurrentChildren = 2,
        maxTasksPerTurn = 8,
        maxDepth = 2,
        maxReportBytes = 4096,
        childRetention = 10
      )
  }
}
