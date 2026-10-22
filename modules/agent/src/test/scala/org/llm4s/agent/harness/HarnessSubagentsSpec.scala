package org.llm4s.agent.harness

import org.llm4s.agent.{ Agent, AgentId, CompletionFixture }
import org.llm4s.agent.delegation.{ DelegationConfig, Inheritance, ToolGrant, TypedAgent }
import org.llm4s.agent.delegation.DelegationSpec.*
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, PermissionRule }
import org.llm4s.agent.graph.tool.{ LockKey, Operation, ToolSet }
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.FiniteDuration

/** Stage 4 slice P1 (#2052, design 4.48): subagents in `HarnessProfile`. */
class HarnessSubagentsSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with LoneElement {

  private val Assistant = AgentId.unsafe("assistant")
  private val General   = AgentId.unsafe(GeneralPurposeConfig.Id)

  /** A conversation sent to the general-purpose subagent: its prompt carries the default suffix. */
  private object ToGeneral {
    def unapply(messages: Vector[Message]): Option[Vector[Message]] =
      Option.when(messages.headOption.exists(_.content.contains(GeneralPurposeConfig.DefaultPromptSuffix)))(messages)
  }

  /** The parent calls `task` for the general-purpose subagent once, then answers with its tool results. */
  private def delegating: PartialFunction[Vector[Message], Result[Completion]] = {
    case ToGeneral(_) => Right(CompletionFixture.simple("the report"))
    case messages if toolResults(messages).isEmpty =>
      Right(
        calling(call("c1", "task", ujson.Obj("subagent_type" -> GeneralPurposeConfig.Id, "description" -> "look")))
      )
    case messages => Right(CompletionFixture.simple(toolResults(messages).mkString("|")))
  }

  private object Echo extends SandboxRunner {
    def locks(workdir: VPath): Set[LockKey] = Set(LockKey("sandbox"))
    def run(command: String, workdir: VPath, timeout: FiniteDuration, maxOutputBytes: Int, ctx: FileContext) =
      Right(ExecResult(0, s"ran $command", ""))
  }

  private def assistant(client: RoutingClient, profile: HarnessProfile) =
    Agent.builder("assistant", client).withHarness(profile)

  "HarnessProfile.deepAgents" should "offer task with the general-purpose subagent, which runs and reports" in {
    val client = RoutingClient(delegating)
    val agent  = assistant(client, HarnessProfile.deepAgents).build().value
    val result = agent.run(ThreadId("t1"), "go").value
    result.answer.value should include("the report")
    agent.delegation.subagents.map(s => s.id -> s.toolName) shouldBe Vector(General -> "task")
    val parentTools = client.calls.collect { case (ToGeneral(_), _) => None; case (_, o) => Some(o) }.flatten
    parentTools.head.tools.map(_.name) should contain("task")
    client.calls.count { case (m, _) => ToGeneral.unapply(m).isDefined } shouldBe 1
  }

  it should "keep the general-purpose subagent's files its own: only the report crosses (rule 17)" in {
    val client = RoutingClient {
      case ToGeneral(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("k1", "write_file", ujson.Obj("path" -> "/notes.md", "content" -> "secret"))))
      case ToGeneral(_) => Right(CompletionFixture.simple("the report"))
      case messages if toolResults(messages).isEmpty =>
        Right(
          calling(call("c1", "task", ujson.Obj("subagent_type" -> GeneralPurposeConfig.Id, "description" -> "look")))
        )
      case messages if toolResults(messages).size == 1 =>
        Right(calling(call("c2", "read_file", ujson.Obj("path" -> "/notes.md"))))
      case messages => Right(CompletionFixture.simple(toolResults(messages).mkString("|")))
    }
    val answer = assistant(client, HarnessProfile.deepAgents).build().value.run(ThreadId("t1"), "go").value.answer.value
    answer should include("the report")
    (answer should not).include("secret")
  }

  "The minimal profile" should "offer no task tool and no subagent: delegation is never granted silently" in {
    val client = RoutingClient { case _ => Right(CompletionFixture.simple("done")) }
    val agent  = assistant(client, HarnessProfile()).build().value
    agent.run(ThreadId("t1"), "go").value
    agent.delegation.subagents shouldBe empty
    client.calls.loneElement._2.tools.map(_.name) should not contain "task"
    HarnessProfile().subagents shouldBe SubagentConfig.none
    (HarnessProfile.defaultPrompt(HarnessProfile()) should not).include("Delegation:")
    HarnessProfile.defaultPrompt(HarnessProfile.deepAgents) should include("Delegation:")
  }

  "The general-purpose subagent's grant" should "be every parent tool but the file tools and execute, with no sandbox" in {
    val client = RoutingClient(PartialFunction.empty)
    val agent = assistant(client, HarnessProfile.deepAgents)
      .withTools(ToolSet.of(echo("lookup")).value)
      .build()
      .value
    val gp = agent.delegation.find(Assistant, General).value
    gp.tools.filter(_.granted).map(_.name) shouldBe Vector("lookup")
    gp.tools.filterNot(_.granted).map(_.name) shouldBe FileTools.Names
    gp.delegates shouldBe false
    gp.middleware shouldBe empty
    gp.modelInherited shouldBe true
  }

  it should "withhold execute too when the parent has a sandbox, so the preset builds either way" in {
    val client = RoutingClient(PartialFunction.empty)
    val agent = assistant(client, HarnessProfile.deepAgents.withSandbox(SandboxBackend(Echo)))
      .withTools(ToolSet.of(echo("lookup")).value)
      .build()
      .value
    val gp = agent.delegation.find(Assistant, General).value
    gp.tools.map(_.name) should not contain ExecuteTool.Name
    gp.tools.filter(_.granted).map(_.name) shouldBe Vector("lookup")
  }

  it should "be shown by the plan" in {
    val client = RoutingClient(PartialFunction.empty)
    val plan = assistant(client, HarnessProfile.deepAgents)
      .withTools(ToolSet.of(echo("lookup")).value)
      .build()
      .value
      .delegation
    plan.toString should include(
      s"assistant -> general-purpose via task: tools [${FileTools.Names.mkString(", ")}, lookup (granted)]; middleware []"
    )
    plan.toString should include("does not delegate; depth 1 of 1")
  }

  it should "check a grant other than AllExcept as every subagent's is" in {
    val client = RoutingClient(PartialFunction.empty)
    val only = SubagentConfig.deepAgents.withGeneralPurpose(
      GeneralPurposeConfig().withInheritance(Inheritance().withTools(ToolGrant.Only(Set("absent"))))
    )
    assistant(client, HarnessProfile.deepAgents.withSubagents(only)).build().left.value.message should include(
      "has no tool 'absent' to grant"
    )
  }

  "GeneralPurposeConfig" should "set the subagent's model, description and prompt" in {
    val client = RoutingClient(delegating)
    val own    = RoutingClient { case _ => Right(CompletionFixture.simple("own model")) }
    val config = SubagentConfig.deepAgents.withGeneralPurpose(
      GeneralPurposeConfig()
        .withModel(own, CompletionOptions())
        .withDescription("Does anything")
        .withSystemPrompt(PromptMode.Replace("GP"))
    )
    val agent = assistant(client, HarnessProfile.deepAgents.withSubagents(config)).build().value
    agent.delegation.find(Assistant, General).value.modelInherited shouldBe false
    agent.run(ThreadId("t1"), "go").value.answer.value should include("own model")
    own.calls.loneElement._1.head.content should startWith("GP")
    client.calls.head._1.head.content should include("Does anything")
  }

  "Disabling the general-purpose subagent" should "leave the specialists only" in {
    val client = RoutingClient(PartialFunction.empty)
    val agent =
      assistant(client, HarnessProfile.deepAgents.withSubagents(SubagentConfig.none.withSpecialists(researcher)))
        .build()
        .value
    agent.delegation.subagents.map(s => s.id.value -> s.toolName) shouldBe Vector("researcher" -> "task_researcher")
    Agent
      .builder("assistant", client)
      .withHarness(
        HarnessProfile.deepAgents.withSubagents(SubagentConfig.none.withSpecialists(researcher))
      )
      .inventory
      .value
      ._1 should (contain("task_researcher").and(not) contain "task")
  }

  "The builder's own subagents" should "follow the harness's, under the harness's bounds" in {
    val client = RoutingClient(PartialFunction.empty)
    val agent  = assistant(client, HarnessProfile.deepAgents).withSubagents(researcher).build().value
    agent.delegation.subagents.map(_.id.value) shouldBe Vector(GeneralPurposeConfig.Id, "researcher")
  }

  "Two builders of one agent" should "agree when one sets the default bounds explicitly and the other leaves them unset" in {
    val client    = RoutingClient(PartialFunction.empty)
    val implicit1 = Agent.builder("worker", client).withSubagents(researcher)
    val explicit1 = Agent.builder("worker", client).withSubagents(researcher).withDelegation(DelegationConfig.default)
    val other     = Agent.builder("other", client).withHandoffs(org.llm4s.agent.Handoff.to("worker", explicit1, "w"))
    Agent
      .builder("lead", client)
      .withHandoffs(
        org.llm4s.agent.Handoff.to("worker", implicit1, "w"),
        org.llm4s.agent.Handoff.to("other", other, "o")
      )
      .build()
      .isRight shouldBe true
    // different bounds are still two different builders
    val wider = Agent.builder("worker", client).withSubagents(researcher).withDelegation(DelegationConfig(maxDepth = 2))
    val other2 = Agent.builder("other", client).withHandoffs(org.llm4s.agent.Handoff.to("worker", wider, "w"))
    Agent
      .builder("lead", client)
      .withHandoffs(
        org.llm4s.agent.Handoff.to("worker", implicit1, "w"),
        org.llm4s.agent.Handoff.to("other", other2, "o")
      )
      .build()
      .left
      .value
      .message should include("two different builders")
  }

  "withDelegation beside a harness" should "be refused: the profile's SubagentConfig sets the bounds" in {
    val client = RoutingClient(PartialFunction.empty)
    assistant(client, HarnessProfile.deepAgents)
      .withDelegation(DelegationConfig().withMaxDepth(2))
      .build()
      .left
      .value
      .message should include("SubagentConfig.withDelegation")
  }

  "A harness's SubagentConfig.delegation" should "be its agent's bounds in the handoff family's one DelegationConfig (#2089)" in {
    val client = RoutingClient(PartialFunction.empty)
    val profile =
      HarnessProfile.deepAgents.withSubagents(SubagentConfig.deepAgents.withDelegation(DelegationConfig(maxDepth = 1)))
    val wider = Agent.builder("specialist", client).withDelegation(DelegationConfig(maxDepth = 3))
    assistant(client, profile)
      .withHandoffs(org.llm4s.agent.Handoff.to("specialist", wider, "s"))
      .build()
      .left
      .value
      .message should include("maxDepth (1 vs 3)")
    // a target that sets none takes the harness's
    val plain = Agent.builder("specialist", client).withSubagents(researcher)
    assistant(client, profile)
      .withHandoffs(org.llm4s.agent.Handoff.to("specialist", plain, "s"))
      .build()
      .isRight shouldBe
      true
  }

  "A harness's delegation bound that is not positive" should "be refused by build(), naming the profile field" in {
    val client = RoutingClient(PartialFunction.empty)
    val error = assistant(
      client,
      HarnessProfile.deepAgents.withSubagents(SubagentConfig.deepAgents.withDelegation(DelegationConfig(maxDepth = 0)))
    ).build().left.value
    error.message should include("maxDepth must be positive")
    error.message should include("harness.subagents")
  }

  "The delegation middleware" should "run inside the harness's permissions and outside its other middleware" in {
    val client = RoutingClient(PartialFunction.empty)
    val mine   = new AgentMiddleware { val id: MiddlewareId = MiddlewareId("mine") }
    def order(profile: HarnessProfile) =
      Agent.builder("assistant", client).withHarness(profile).withMiddleware(mine).inventory.value._2.map(_.value)
    val rest = Vector("summarization", "tool-output-offload", "skills", "instructions", "todos", "mine")
    order(HarnessProfile.deepAgents.withPermissions(Vector(PermissionRule.allow(Operation.Invoke, "**")))) shouldBe
      ("permissions" +: "delegation" +: rest)
    order(HarnessProfile.deepAgents) shouldBe ("delegation" +: rest)
    // builder-level subagents without a harness stay innermost, as before
    Agent
      .builder("assistant", client)
      .withMiddleware(mine)
      .withSubagents(researcher)
      .inventory
      .value
      ._2
      .map(_.value) shouldBe
      Vector("mine", "delegation")
  }

  "Permission rules" should "decide each task call: task needs an Invoke rule" in {
    def toolResult(rules: Vector[PermissionRule]): String = {
      val client = RoutingClient(delegating)
      assistant(client, HarnessProfile.deepAgents.withPermissions(rules)).build().value.run(ThreadId("t1"), "go").value
      toolResults(client.calls.last._1).loneElement
    }
    toolResult(Vector(PermissionRule.allow(Operation.Invoke, "read_file"))) should include("Denied")
    toolResult(Vector(PermissionRule.allow(Operation.Invoke, s"task:${GeneralPurposeConfig.Id}"))) should include(
      "the report"
    )
    toolResult(Vector(PermissionRule.allow(Operation.Invoke, "task:*"))) should include("the report")
  }

  "An application tool named task" should "clash with the preset's task tool at build()" in {
    val client = RoutingClient(PartialFunction.empty)
    assistant(client, HarnessProfile.deepAgents)
      .withTools(ToolSet.of(echo("task")).value)
      .build()
      .left
      .value
      .message should include("task")
  }

  "A subagent with the deepAgents harness" should "get its harness's subagents only when delegation is granted (rule 15)" in {
    val client = RoutingClient(PartialFunction.empty)
    def worker(inheritance: Inheritance) =
      TypedAgent.text("worker", "Works").withHarness(HarnessProfile.deepAgents).withInheritance(inheritance)
    val lead = AgentId.unsafe("lead")

    val withheld = Agent
      .builder("lead", client)
      .withSubagents(worker(Inheritance.none))
      .withDelegation(DelegationConfig().withMaxDepth(2))
      .build()
      .value
      .delegation
      .find(lead, AgentId.unsafe("worker"))
      .value
    withheld.delegates shouldBe false
    withheld.tools.map(_.name) should not contain "task"
    withheld.middleware.map(_.value) should not contain "delegation"

    val granted = Agent
      .builder("lead", client)
      .withSubagents(worker(Inheritance().withDelegate(true)))
      .withDelegation(DelegationConfig().withMaxDepth(2))
      .build()
      .value
      .delegation
      .find(lead, AgentId.unsafe("worker"))
      .value
    granted.delegates shouldBe true
    granted.tools.map(_.name) should contain("task")
    granted.subagents.map(_.id.value) shouldBe Vector(GeneralPurposeConfig.Id)
    granted.subagents.loneElement.depth shouldBe 2
    granted.subagents.loneElement.maxDepth shouldBe 2
  }
}
