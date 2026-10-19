package org.llm4s.agent.delegation

import org.llm4s.agent.{ Agent, AgentBuilder, CompletionFixture }
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, InMemoryCheckpointer, RunConfig, ThreadId, ToolCallId }
import org.llm4s.agent.graph.middleware.{ PermissionMiddleware, PermissionRule }
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, Operation, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, SchemaDefinition }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/** `TypedAgent`, the `task` tools and parallel bounded children (#2048, design 4.44), through a parent agent's runs. */
class DelegationSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with LoneElement {
  import DelegationSpec.*

  "a typed subagent" should "take its typed input and give back a typed report the parent reads as its tool result" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "tides"))))
      case Lead(messages)         => Right(CompletionFixture.simple(s"composed from ${toolResults(messages).mkString}"))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"the moon","sources":2}"""))
    }
    val agent  = lead(client).withSubagents(researcher).build().value
    val result = agent.run(ThreadId("t1"), "research tides").value

    result.answer.value should include(""""summary":"the moon"""")
    toolResults(client.sentTo(LeadPrompt).last) shouldBe Vector("""{"summary":"the moon","sources":2}""")
  }

  it should "start the child with only its rendered input, and keep every child message out of the parent's thread (rule 17)" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "tides"))))
      case Lead(_)                => Right(CompletionFixture.simple("done"))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"SECRET-CHILD-TEXT","sources":1}"""))
    }
    val agent  = lead(client).withSubagents(researcher).build().value
    val result = agent.run(ThreadId("t1"), "PARENT-QUERY").value

    val childCall = client.sentTo("CHILD:researcher").loneElement
    childCall.collect { case u: UserMessage => u.content } shouldBe Vector(
      "Task for subagent 'researcher', as JSON:\n{\"topic\":\"tides\"}"
    )
    (childCall.map(_.content).mkString should not).include("PARENT-QUERY")
    // the parent's thread: its query, its call, the report as the tool result, its answer - no child message
    (result.messages.collect { case a: AssistantMessage => a.content }.mkString should not).include("SECRET-CHILD-TEXT")
    result.messages.count { case _: UserMessage => true; case _ => false } shouldBe 1
    // and the child thread holds only its own turn
    val child = agent.children(ThreadId("t1")).value.loneElement
    child.origin.agent.value shouldBe "researcher"
    child.origin.depth shouldBe 1
  }

  it should "offer the child only its own tools, never the parent's or the task tools (rule 15)" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "x"))))
      case Lead(_)                => Right(CompletionFixture.simple("done"))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
    }
    val agent = lead(client)
      .withTools(ToolSet.of(echo("parent_tool")).value)
      .withSubagents(researcher.withTools(ToolSet.of(echo("child_tool")).value))
      .build()
      .value
    agent.run(ThreadId("t1"), "go").value

    client.toolsSentTo("CHILD:researcher").loneElement shouldBe Vector("child_tool")
    client.toolsSentTo(LeadPrompt).head.toSet shouldBe Set("parent_tool", "task_researcher")
  }

  "a text subagent" should "be offered through the shared task tool, take the description as its query, and report its answer" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "summarise X"))))
      case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("general", messages) =>
        Right(CompletionFixture.simple(s"report on ${messages.collect { case u: UserMessage => u.content }.mkString}"))
    }
    val agent  = lead(client).withSubagents(general).build().value
    val result = agent.run(ThreadId("t1"), "go").value

    result.answer.value shouldBe "report on summarise X"
    val taskTool = client.optionsSentTo(LeadPrompt).head.tools.find(_.name == "task").value
    taskTool.toOpenAITool(true).render() should include(""""enum":["general"]""")
    // the prompt section lists the subagent
    client.sentTo(LeadPrompt).head.head.content should include("general (`task` with subagent_type \"general\")")
  }

  "a child whose typed answer fails its schema twice" should "be a refusal the parent model sees as a tool error" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "x"))))
      case Lead(messages)         => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":1}"""))
    }
    val result = lead(client).withSubagents(researcher).build().value.run(ThreadId("t1"), "go").value

    result.answer.value should include("subagent 'researcher'")
    result.answer.value should include("did not match its output schema")
  }

  "a blocked child or one at its step limit" should "be a refusal reported to the parent model" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "x"))))
      case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("researcher", _) =>
        Right(calling(ToolCall("k1", "child_tool", ujson.Obj("text" -> "x"))))
    }
    val limited = researcher.withTools(ToolSet.of(echo("child_tool")).value).withMaxSteps(1)
    val result  = lead(client).withSubagents(limited).build().value.run(ThreadId("t1"), "go").value

    result.answer.value should include("reached its step limit")
  }

  "parallel task calls" should "run concurrently, at most maxConcurrentChildren children at once" in {
    val bothLive = new CountDownLatch(2)
    val live     = new AtomicInteger(0)
    val maxLive  = new AtomicInteger(0)
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(
          calling(
            call("c1", "task_researcher", ujson.Obj("topic" -> "a")),
            call("c2", "task_researcher", ujson.Obj("topic" -> "b")),
            call("c3", "task_researcher", ujson.Obj("topic" -> "c"))
          )
        )
      case Lead(messages) => Right(CompletionFixture.simple(s"${toolResults(messages).size} reports"))
      case Child("researcher", _) =>
        val now = live.incrementAndGet()
        maxLive.accumulateAndGet(now, math.max)
        // the first two wait for each other, so both are live at once; the third finds the latch open
        bothLive.countDown()
        bothLive.await(5, TimeUnit.SECONDS)
        Thread.sleep(50)
        live.decrementAndGet()
        Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
    }
    val agent = lead(client)
      .withSubagents(researcher)
      .withDelegation(DelegationConfig().withMaxConcurrentChildren(2))
      .build()
      .value
    val result = agent.run(ThreadId("t1"), "go").value

    result.answer.value shouldBe "3 reports"
    bothLive.getCount shouldBe 0
    maxLive.get shouldBe 2
    agent.children(ThreadId("t1")).value should have size 3
  }

  "maxTasksPerTurn" should "refuse the calls of one batch past the bound, by their order in the message" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(
          calling(
            call("c1", "task_researcher", ujson.Obj("topic" -> "a")),
            call("c2", "task_researcher", ujson.Obj("topic" -> "b")),
            call("c3", "task_researcher", ujson.Obj("topic" -> "c"))
          )
        )
      case Lead(messages)         => Right(CompletionFixture.simple(toolResults(messages).mkString("|")))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
    }
    val agent = lead(client)
      .withSubagents(researcher)
      .withDelegation(DelegationConfig().withMaxTasksPerTurn(2))
      .build()
      .value
    val results = agent.run(ThreadId("t1"), "go").value.answer.value.split('|').toVector

    results.take(2).foreach(_ should include("summary"))
    results(2) should include("over maxTasksPerTurn = 2")
    agent.children(ThreadId("t1")).value should have size 2
  }

  it should "count the turn's task calls across its model steps" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) if toolResults(messages).size == 1 =>
        Right(
          calling(
            call("c2", "task_researcher", ujson.Obj("topic" -> "b")),
            call("c3", "task_researcher", ujson.Obj("topic" -> "c"))
          )
        )
      case Lead(messages)         => Right(CompletionFixture.simple(toolResults(messages).mkString("|")))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
    }
    val agent = lead(client)
      .withSubagents(researcher)
      .withDelegation(DelegationConfig().withMaxTasksPerTurn(2))
      .build()
      .value
    val results = agent.run(ThreadId("t1"), "go").value.answer.value.split('|').toVector

    results should have size 3
    results(2) should include("over maxTasksPerTurn = 2")
    // a new turn starts counting again
    val again = agent.run(ThreadId("t1"), "again").value.answer.value.split('|').toVector
    again.take(2).foreach(_ should include("summary"))
  }

  "maxDepth" should "refuse a task that would nest deeper than the bound" in {
    val client = RoutingClient { case Child("researcher", _) => Right(CompletionFixture.simple("{}")) }
    val bound  = researcher.bind(client, GraphRuntime.inMemory()).value
    // installed in an agent that itself runs at depth 1, with maxDepth 1
    val middleware = new DelegationMiddleware(Vector(bound), DelegationConfig(), depth = 1)
    val tool       = middleware.tools.loneElement
    val parent = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString))
    }
    val agent = lead(parent).withMiddleware(middleware).build().value
    tool.spec.name shouldBe "task_researcher"
    agent.run(ThreadId("t1"), "go").value.answer.value should include("deeper than maxDepth = 1")
    client.calls shouldBe empty
  }

  "maxReportBytes" should "turn a larger report into a tool error" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages)         => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("researcher", _) => Right(CompletionFixture.simple(s"""{"summary":"${"x" * 100}","sources":0}"""))
    }
    val agent = lead(client)
      .withSubagents(researcher)
      .withDelegation(DelegationConfig().withMaxReportBytes(64))
      .build()
      .value
    agent.run(ThreadId("t1"), "go").value.answer.value should include("report over 64 bytes")
  }

  "ChildFailure.Report" should "let the parent compose without a failed child" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages)         => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("researcher", _) => Left(ValidationError("provider", "the provider is down"))
    }
    val result = lead(client).withSubagents(researcher).build().value.run(ThreadId("t1"), "go").value
    result.answer.value should include("failed")
    result.answer.value should include("the provider is down")
  }

  "ChildFailure.Fail" should "fail the parent run, and the parent's recover continue the child from its checkpoint" in {
    val childCalls = new AtomicInteger(0)
    val probes     = new AtomicInteger(0)
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("researcher", _) =>
        childCalls.incrementAndGet() match
          case 1 => Right(calling(ToolCall("k1", "probe", ujson.Obj("text" -> "x"))))
          case 2 => Left(ValidationError("provider", "the provider is down"))
          case _ => Right(CompletionFixture.simple("""{"summary":"recovered","sources":1}"""))
    }
    val probe = AgentTool(SpecText.spec("probe")) { (_, _) =>
      probes.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str("probed")))
    }
    val agent = lead(client)
      .withSubagents(researcher.withTools(ToolSet.of(probe).value).withFailure(ChildFailure.Fail))
      .build()
      .value

    agent.run(ThreadId("t1"), "go").left.value.message should (include("task_researcher").and(
      include("the provider is down")
    ))
    val recovered = agent.recover(ThreadId("t1")).value

    recovered.answer.value should include("recovered")
    probes.get shouldBe 1 // the child continued from its checkpoint: its tool call did not run again
    childCalls.get shouldBe 3
    client.sentTo("CHILD:researcher").map(_.count { case _: UserMessage => true; case _ => false }).distinct shouldBe
      Vector(1)
    agent.children(ThreadId("t1")).value should have size 1
  }

  "a suspended child" should "be reported to the parent model as an error, the child left suspended" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("researcher", _) =>
        Right(calling(ToolCall("k1", "child_tool", ujson.Obj("text" -> "x"))))
    }
    val asking = researcher
      .withTools(ToolSet.of(echo("child_tool")).value)
      .withMiddleware(new PermissionMiddleware(Seq(PermissionRule.ask(Operation.Invoke, "child_tool"))))
    val agent  = lead(client).withSubagents(asking).build().value
    val result = agent.run(ThreadId("t1"), "go").value

    result.answer.value should include("suspended")
    agent.children(ThreadId("t1")).value.loneElement.status.toString shouldBe "Suspended"
  }

  "a task call" should "be decided by a PermissionMiddleware on its resource (Invoke, task:<id>)" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString))
    }
    val agent = lead(client)
      .withMiddleware(new PermissionMiddleware(Seq(PermissionRule.deny(Operation.Invoke, "task:researcher"))))
      .withSubagents(researcher)
      .build()
      .value
    agent.run(ThreadId("t1"), "go").value.answer.value.toLowerCase should include("denied")
    agent.children(ThreadId("t1")).value shouldBe empty
  }

  "a child" should "run with its parent's tenant (rule 16)" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(_)                => Right(CompletionFixture.simple("done"))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
    }
    val runtime = new GraphRuntime(new InMemoryCheckpointer())
    val agent   = lead(client).withRuntime(runtime).withSubagents(researcher).build().value
    val tenant  = RunConfig().withTenantId(org.llm4s.agent.graph.TenantId("acme"))
    agent.run(ThreadId("t1"), "go", tenant).value
    val child = agent.children(ThreadId("t1"), tenant).value.loneElement
    runtime.history(child.threadId, None, 1, tenant).value.loneElement.tenantId shouldBe Some("acme")
    runtime.history(child.threadId).left.value shouldBe a[GraphError.TenantMismatch]
  }

  "BoundAgent.run" should "run a subagent with no parent, on a top-level thread" in {
    val client = RoutingClient { case Child("researcher", _) =>
      Right(CompletionFixture.simple("""{"summary":"alone","sources":3}"""))
    }
    val runtime = GraphRuntime.inMemory()
    val bound   = researcher.bind(client, runtime).value
    val outcome = bound.run(Question("tides")).value

    outcome match
      case TaskOutcome.Reported(report, child, usage) =>
        report shouldBe Findings("alone", 3)
        child.depth shouldBe 0
        child.toolCallId shouldBe ToolCallId("")
        usage.requestCount shouldBe 1
        runtime.history(child.threadId).value.head.origin shouldBe None
      case other => fail(s"expected a report, got $other")
  }

  it should "report a typed answer that fails its schema as a refusal" in {
    val client  = RoutingClient { case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":1}""")) }
    val outcome = researcher.bind(client, GraphRuntime.inMemory()).value.run(Question("x")).value
    outcome match
      case TaskOutcome.Refused(_, TaskRefusal.OutputInvalid(errors), usage) =>
        errors should not be empty
        usage.requestCount shouldBe 2 // the answer and its retry, both counted
      case other => fail(s"expected a refusal, got $other")
  }

  "ChildFailure.Fail" should "fail the parent with a typed child's OutputInvalid, every error kept" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "x"))))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":1}"""))
    }
    val agent = lead(client).withSubagents(researcher.withFailure(ChildFailure.Fail)).build().value
    val error = agent.run(ThreadId("t1"), "go").left.value
    error.message should include("task_researcher")
    org.llm4s.agent.AgentFixture.cause(error) match
      case failed: GraphError.ToolFailed =>
        failed.cause match
          case GraphError.OutputInvalid(errors) => errors.mkString should include("sources")
          case other                            => fail(s"expected the child's OutputInvalid, got $other")
      case other => fail(s"expected ToolFailed, got $other")
  }

  "DelegationConfig.childRetention" should "apply when only a handoff target delegates, the tightest bound in the family" in {
    val client = RoutingClient {
      case Lead(_) => Right(calling(call("h1", "handoff_to_specialist", ujson.Obj())))
      case messages if messages.headOption.exists(_.content.startsWith("SPECIALIST")) =>
        val sinceUser = messages.drop(messages.lastIndexWhere { case _: UserMessage => true; case _ => false })
        if toolResults(sinceUser).exists(_.contains("r")) && sinceUser.exists {
            case a: AssistantMessage => a.toolCalls.exists(_.name == "task"); case _ => false
          }
        then Right(CompletionFixture.simple("done"))
        else
          Right(
            calling(call(s"t${messages.size}", "task", ujson.Obj("subagent_type" -> "general", "description" -> "d")))
          )
      case Child("general", _) => Right(CompletionFixture.simple("r"))
    }
    val specialist = Agent
      .builder("specialist", client)
      .withSystemPrompt("SPECIALIST")
      .withSubagents(general)
      .withDelegation(DelegationConfig().withChildRetention(1))
    val agent =
      lead(client).withHandoffs(org.llm4s.agent.Handoff.to("specialist", specialist, "specialist")).build().value
    agent.run(ThreadId("t1"), "one").value.answer.value shouldBe "done"
    agent.run(ThreadId("t1"), "two").value.answer.value shouldBe "done"
    agent.children(ThreadId("t1")).value should have size 1
  }

  "a task refused before any child existed" should "be a tool error under Report and Fatal under Fail, naming no child" in {
    val notStarted: TaskOutcome[Findings] = TaskOutcome.NotStarted(researcher.id, TaskRefusal.TokenLimitReached)
    DelegationMiddleware.toolOutcome("researcher", ChildFailure.Report, 1024, notStarted, None) shouldBe
      ToolOutcome.Error("subagent 'researcher' was not started: reached its token budget without an answer")
    DelegationMiddleware.toolOutcome("researcher", ChildFailure.Fail, 1024, notStarted, None) match
      case ToolOutcome.Fatal(error) => error.message should include("was not started")
      case other                    => fail(s"expected Fatal, got $other")
  }

  "build()" should "refuse two subagents with one id, a clash with another tool, bad bounds and a bad subagent" in {
    val client = RoutingClient(PartialFunction.empty)
    lead(client).withSubagents(general, general).build().left.value.message should include("defined twice")
    lead(client)
      .withTools(ToolSet.of(echo("task")).value)
      .withSubagents(general)
      .build()
      .left
      .value
      .message should include("task")
    lead(client)
      .withSubagents(general)
      .withDelegation(DelegationConfig().withMaxConcurrentChildren(0).withMaxDepth(-1))
      .build()
      .left
      .value
      .message should (include("maxConcurrentChildren must be positive").and(include("maxDepth must be positive")))
    lead(client).withSubagents(TypedAgent.text("bad id!", "d")).build().left.value.message should include("bad id!")
    lead(client).withSubagents(TypedAgent.text("blank", " ")).build().left.value.message should include("blank")
    lead(client)
      .withSubagents(
        TypedAgent.text("both", "d").withSystemPrompt("p").withHarness(org.llm4s.agent.harness.HarnessProfile())
      )
      .build()
      .left
      .value
      .message should include("harness")
  }

  it should "version the parent's graph by its task tools, and leave an agent without subagents as it was" in {
    val client = RoutingClient(PartialFunction.empty)
    val plain  = lead(client).build().value
    val one    = lead(client).withSubagents(general).build().value
    val two    = lead(client).withSubagents(general, TypedAgent.text("other", "d")).build().value
    Set(plain.loop.graph.version, one.loop.graph.version, two.loop.graph.version) should have size 3
    lead(client).build().value.loop.graph.version shouldBe plain.loop.graph.version
  }

  "DelegationConfig.childRetention" should "apply to the delegating agent's runs unless the run sets another" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "d"))))
      case Lead(_)             => Right(CompletionFixture.simple("done"))
      case Child("general", _) => Right(CompletionFixture.simple("r"))
    }
    val agent = lead(client)
      .withSubagents(general)
      .withDelegation(DelegationConfig().withChildRetention(1))
      .build()
      .value
    agent.run(ThreadId("t1"), "one").value
    agent.run(ThreadId("t1"), "two").value
    agent.children(ThreadId("t1")).value should have size 1
  }
}

object DelegationSpec {
  val LeadPrompt = "LEAD"

  final case class Question(topic: String) derives ReadWriter
  final case class Findings(summary: String, sources: Int) derives ReadWriter

  val questionSchema: SchemaDefinition[Question] =
    Schema.`object`[Question]("A research question").withRequiredField("topic", Schema.string("The topic"))
  val findingsSchema: SchemaDefinition[Findings] =
    Schema
      .`object`[Findings]("Findings")
      .withRequiredField("summary", Schema.string("The summary"))
      .withRequiredField("sources", Schema.integer("How many sources"))

  val researcher: TypedAgent[Question, Findings] =
    TypedAgent("researcher", "Researches one topic", questionSchema, findingsSchema)
      .withSystemPrompt("CHILD:researcher")

  val general: TypedAgent[TaskRequest, String] =
    TypedAgent.text("general", "A general-purpose subagent").withSystemPrompt("CHILD:general")

  def lead(client: LLMClient): AgentBuilder = Agent.builder("lead", client).withSystemPrompt(LeadPrompt)

  final case class SpecText(text: String) derives ReadWriter
  object SpecText {
    def spec(name: String): AgentToolSpec[SpecText] =
      AgentToolSpec(
        name,
        s"$name tool",
        Schema.`object`[SpecText]("args").withRequiredField("text", Schema.string("t"))
      )
  }

  def echo(name: String): AgentTool[SpecText] =
    AgentTool(SpecText.spec(name))((a, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str(a.text))))

  def call(id: String, name: String, args: ujson.Value): ToolCall = ToolCall(id, name, args)

  def calling(calls: ToolCall*): Completion = CompletionFixture.withMessage(AssistantMessage(None, calls))

  def toolResults(messages: Vector[Message]): Vector[String] = messages.collect { case t: ToolMessage => t.content }

  /** A conversation sent with the lead's system prompt. */
  object Lead {
    def unapply(messages: Vector[Message]): Option[Vector[Message]] =
      Option.when(messages.headOption.exists(_.content.startsWith(LeadPrompt)))(messages)
  }

  /** A conversation sent with a child's system prompt, `CHILD:<id>`. */
  object Child {
    def unapply(messages: Vector[Message]): Option[(String, Vector[Message])] =
      messages.headOption.map(_.content).filter(_.startsWith("CHILD:")).map { prompt =>
        (prompt.stripPrefix("CHILD:").takeWhile(c => c.isLetterOrDigit || c == '-' || c == '_'), messages)
      }
  }

  /** Answers each call by the conversation sent, recording every call; thread-safe. */
  final class RoutingClient(route: PartialFunction[Vector[Message], Result[Completion]]) extends LLMClient {
    private val recorded = new CopyOnWriteArrayList[(Vector[Message], CompletionOptions)]()

    def calls: Vector[(Vector[Message], CompletionOptions)] = recorded.asScala.toVector

    def sentTo(prompt: String): Vector[Vector[Message]] =
      calls.collect { case (messages, _) if messages.headOption.exists(_.content.startsWith(prompt)) => messages }

    def optionsSentTo(prompt: String): Vector[CompletionOptions] =
      calls.collect { case (messages, options) if messages.headOption.exists(_.content.startsWith(prompt)) => options }

    def toolsSentTo(prompt: String): Vector[Vector[String]] = optionsSentTo(prompt).map(_.tools.map(_.name).toVector)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val messages = conversation.messages.toVector
      recorded.add(messages -> options)
      route.applyOrElse(messages, (_: Vector[Message]) => Left(ValidationError("routing", "no route for the call")))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 128000
    override def getReserveCompletion(): Int = 4096
  }
}
