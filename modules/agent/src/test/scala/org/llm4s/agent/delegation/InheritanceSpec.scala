package org.llm4s.agent.delegation

import org.llm4s.agent.{ AgentStatus, CompletionFixture }
import org.llm4s.agent.graph.{ CheckpointStatus, GraphError, GraphRuntime, RunBudgets, RunConfig, ThreadId }
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  MiddlewareId,
  ModelContext,
  PermissionMiddleware,
  PermissionRule,
  PromptSection
}
import org.llm4s.agent.graph.tool.{ AgentTool, Operation, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import scala.concurrent.duration.*

/** Explicit inheritance (#2049, design 4.45): grants, narrowing permissions, budgets and `DelegationPlan`. */
class InheritanceSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with LoneElement {
  import DelegationSpec.*
  import InheritanceSpec.*

  /** The lead calls `task_researcher` once, then answers with its tool results. */
  private def delegatingOnce: PartialFunction[Vector[Message], Result[Completion]] = {
    case Lead(messages) if toolResults(messages).isEmpty =>
      Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "x"))))
    case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString("|")))
  }

  /** A child that calls `tool` every step and never answers. */
  private def looping(tool: String): PartialFunction[Vector[Message], Result[Completion]] = {
    case Child("researcher", messages) =>
      Right(calling(ToolCall(s"k${toolResults(messages).size}", tool, ujson.Obj("text" -> "x"))))
  }

  private def answering: PartialFunction[Vector[Message], Result[Completion]] = { case Child("researcher", _) =>
    Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
  }

  "a tool grant" should "offer the child only the parent tools it names, and none without one (rule 15)" in {
    def offered(grant: ToolGrant): Set[String] = {
      val client = RoutingClient(delegatingOnce.orElse(answering))
      lead(client)
        .withTools(ToolSet.of(echo("a"), echo("b"), echo("c")).value)
        .withSubagents(
          researcher
            .withTools(ToolSet.of(echo("own")).value)
            .withInheritance(Inheritance().withTools(grant))
        )
        .build()
        .value
        .run(ThreadId("t1"), "go")
        .value
      client.toolsSentTo("CHILD:researcher").loneElement.toSet
    }
    offered(ToolGrant.None) shouldBe Set("own")
    offered(ToolGrant.Only(Set("b"))) shouldBe Set("own", "b")
    offered(ToolGrant.AllExcept(Set("a"))) shouldBe Set("own", "b", "c")
  }

  it should "run a granted tool, the parent's instance, against the child's thread" in {
    val threads = new AtomicReference[String]("")
    val shared = AgentTool(SpecText.spec("shared")) { (_, ctx) =>
      threads.set(ctx.run.position.threadId.value)
      ToolOutcome.Success(ToolContent.Json(ujson.Str("ran")))
    }
    val client = RoutingClient(delegatingOnce.orElse {
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "shared", ujson.Obj("text" -> "x"))))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"s","sources":1}"""))
    })
    val agent = lead(client)
      .withTools(ToolSet.of(shared).value)
      .withSubagents(researcher.withInheritance(Inheritance().withTools(ToolGrant.Only(Set("shared")))))
      .build()
      .value
    agent.run(ThreadId("t1"), "go").value
    threads.get shouldBe agent.children(ThreadId("t1")).value.loneElement.threadId.value
  }

  it should "be refused at build() when it names a tool the parent does not have, the task tools included" in {
    val client = RoutingClient(PartialFunction.empty)
    def built(grant: ToolGrant) =
      lead(client)
        .withTools(ToolSet.of(echo("a")).value)
        .withSubagents(researcher.withInheritance(Inheritance().withTools(grant)), general)
        .build()
    built(ToolGrant.Only(Set("a", "missing"))).left.value.message should include("has no tool 'missing'")
    built(ToolGrant.AllExcept(Set("nope"))).left.value.message should include("has no tool 'nope'")
    built(ToolGrant.Only(Set("task"))).left.value.message should include("has no tool 'task'")
  }

  it should "validate a granted tool's arguments with the parent's validator" in {
    val ran = new AtomicInteger(0)
    val shared = AgentTool(SpecText.spec("shared")) { (_, _) =>
      ran.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str("ran")))
    }
    // the parent's validator refuses one value the default accepts
    val strict = new org.llm4s.agent.graph.tool.ToolArgumentValidator {
      def unsupported(schema: ujson.Value): Vector[String] =
        org.llm4s.agent.graph.tool.ToolArgumentValidator.default.unsupported(schema)
      def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String] =
        if (arguments.toString.contains("forbidden")) Vector("$.text: forbidden by the parent's validator")
        else org.llm4s.agent.graph.tool.ToolArgumentValidator.default.validate(schema, arguments)
    }
    val client = RoutingClient(delegatingOnce.orElse {
      // the child's own tool has the same schema: its own validator checks it, which accepts the value
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(
          calling(
            ToolCall("k1", "shared", ujson.Obj("text" -> "forbidden")),
            ToolCall("k2", "own", ujson.Obj("text" -> "forbidden"))
          )
        )
      case Child("researcher", messages) =>
        Right(
          CompletionFixture.simple(ujson.write(ujson.Obj("summary" -> toolResults(messages).mkString, "sources" -> 0)))
        )
    })
    val ownRan = new AtomicInteger(0)
    val own = AgentTool(SpecText.spec("own")) { (_, _) =>
      ownRan.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str("own ran")))
    }
    val result = lead(client)
      .withTools(ToolSet.of(strict, shared).value)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(own).value)
          .withInheritance(Inheritance().withTools(ToolGrant.Only(Set("shared"))))
      )
      .build()
      .value
      .run(ThreadId("t1"), "go")
      .value
    ran.get shouldBe 0
    ownRan.get shouldBe 1
    result.answer.value should include("forbidden by the parent's validator")
  }

  it should "validate a granted middleware's tool with the parent's validator" in {
    val ran = new AtomicInteger(0)
    val contributing = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("contributing")
      override val tools: Vector[AgentTool[?]] = Vector(AgentTool(SpecText.spec("mtool")) { (_, _) =>
        ran.incrementAndGet()
        ToolOutcome.Success(ToolContent.Json(ujson.Str("ran")))
      })
    }
    val strict = new org.llm4s.agent.graph.tool.ToolArgumentValidator {
      def unsupported(schema: ujson.Value): Vector[String] =
        org.llm4s.agent.graph.tool.ToolArgumentValidator.default.unsupported(schema)
      def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String] =
        if (arguments.toString.contains("forbidden")) Vector("$.text: forbidden by the parent's validator")
        else org.llm4s.agent.graph.tool.ToolArgumentValidator.default.validate(schema, arguments)
    }
    val client = RoutingClient(delegatingOnce.orElse {
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "mtool", ujson.Obj("text" -> "forbidden"))))
      case Child("researcher", messages) =>
        Right(
          CompletionFixture.simple(ujson.write(ujson.Obj("summary" -> toolResults(messages).mkString, "sources" -> 0)))
        )
    })
    val result = lead(client)
      .withTools(ToolSet.of(strict, echo("parent_tool")).value)
      .withMiddleware(contributing)
      .withSubagents(researcher.withInheritance(Inheritance().withMiddleware(MiddlewareId("contributing"))))
      .build()
      .value
      .run(ThreadId("t1"), "go")
      .value
    ran.get shouldBe 0
    result.answer.value should include("forbidden by the parent's validator")
  }

  "a middleware grant" should "share the parent's instance with the child, and nothing without one" in {
    def sections(grant: Boolean): (Vector[String], Int) = {
      val memory = new Memory
      val client = RoutingClient(delegatingOnce.orElse(answering))
      val inheritance =
        if (grant) Inheritance().withMiddleware(Memory.Id) else Inheritance.none
      lead(client)
        .withMiddleware(memory)
        .withSubagents(researcher.withInheritance(inheritance))
        .build()
        .value
        .run(ThreadId("t1"), "go")
        .value
      (client.sentTo("CHILD:researcher").map(_.head.content), memory.reads.get)
    }
    val (granted, grantedReads) = sections(grant = true)
    granted.loneElement should include("MEMORY-SECTION")
    grantedReads shouldBe 3 // the lead's two calls and the child's one, through the one instance
    val (ungranted, ungrantedReads) = sections(grant = false)
    (ungranted.loneElement should not).include("MEMORY-SECTION")
    ungrantedReads shouldBe 2
  }

  it should "be refused at build() for a middleware the parent does not have, and for delegation itself" in {
    val client = RoutingClient(PartialFunction.empty)
    def built(ids: MiddlewareId*) =
      lead(client).withSubagents(researcher.withInheritance(Inheritance().withMiddleware(ids*))).build()
    built(MiddlewareId("absent")).left.value.message should include("has no middleware 'absent'")
    built(MiddlewareId("delegation")).left.value.message should include("cannot be granted")
  }

  "an ancestor's permission rules" should "deny a call the child's own rules allow (rule 15)" in {
    val ran = new AtomicInteger(0)
    val tool = AgentTool(SpecText.spec("child_tool")) { (_, _) =>
      ran.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str("ran")))
    }
    val client = RoutingClient(delegatingOnce.orElse {
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "child_tool", ujson.Obj("text" -> "x"))))
      case Child("researcher", messages) =>
        Right(
          CompletionFixture.simple(
            ujson.write(ujson.Obj("summary" -> toolResults(messages).mkString, "sources" -> 0))
          )
        )
    })
    val parentRules = new PermissionMiddleware(Seq(PermissionRule.allow(Operation.Invoke, "task:researcher")))
    val childRules  = new PermissionMiddleware(Seq(PermissionRule.allow(Operation.Invoke, "**")))
    val result = lead(client)
      .withMiddleware(parentRules)
      .withSubagents(researcher.withTools(ToolSet.of(tool).value).withMiddleware(childRules))
      .build()
      .value
      .run(ThreadId("t1"), "go")
      .value

    ran.get shouldBe 0
    result.answer.value should include("Denied: invoke child_tool (by agent 'lead')")
  }

  it should "deny a call the child's own rules deny, without asking for an ancestor's approval first" in {
    val ran = new AtomicInteger(0)
    val tool = AgentTool(SpecText.spec("child_tool")) { (_, _) =>
      ran.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str("ran")))
    }
    val client = RoutingClient(delegatingOnce.orElse {
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "child_tool", ujson.Obj("text" -> "x"))))
      case Child("researcher", messages) =>
        Right(
          CompletionFixture.simple(ujson.write(ujson.Obj("summary" -> toolResults(messages).mkString, "sources" -> 0)))
        )
    })
    val parentRules = new PermissionMiddleware(
      Seq(PermissionRule.allow(Operation.Invoke, "task:**"), PermissionRule.ask(Operation.Invoke, "child_tool"))
    )
    val childRules = new PermissionMiddleware(Seq(PermissionRule.deny(Operation.Invoke, "child_tool")))
    val result = lead(client)
      .withMiddleware(parentRules)
      .withSubagents(researcher.withTools(ToolSet.of(tool).value).withMiddleware(childRules))
      .build()
      .value
      .run(ThreadId("t1"), "go")
      .value
    result.status shouldBe a[AgentStatus.Completed]
    ran.get shouldBe 0
    result.answer.value should include("Denied: invoke child_tool (by agent 'researcher')")
  }

  it should "ask once when an ancestor asks, through the parent, and the approved call run once" in {
    val ran = new AtomicInteger(0)
    val tool = AgentTool(SpecText.spec("child_tool")) { (_, _) =>
      ran.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str("ran")))
    }
    val client = RoutingClient(delegatingOnce.orElse {
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "child_tool", ujson.Obj("text" -> "x"))))
      case Child("researcher", messages) =>
        Right(
          CompletionFixture.simple(
            ujson.write(ujson.Obj("summary" -> toolResults(messages).mkString, "sources" -> 1))
          )
        )
    })
    val parentRules = new PermissionMiddleware(
      Seq(PermissionRule.allow(Operation.Invoke, "task:**"), PermissionRule.ask(Operation.Invoke, "child_tool"))
    )
    // the child's own rules ask too: one approval still covers both levels
    val childRules = new PermissionMiddleware(Seq(PermissionRule.ask(Operation.Invoke, "child_tool")))
    val agent = lead(client)
      .withMiddleware(parentRules)
      .withSubagents(researcher.withTools(ToolSet.of(tool).value).withMiddleware(childRules))
      .build()
      .value
    val first = agent.run(ThreadId("t1"), "go").value

    ran.get shouldBe 0
    val suspended = first.status match {
      case s: AgentStatus.Suspended => s
      case other                    => fail(s"expected Suspended, got $other")
    }
    val (id, request) = suspended.delegated.loneElement
    request.child.agent.value shouldBe "researcher"
    request.request.asInstanceOf[ChildInterrupt.Approval].request.call.name shouldBe "child_tool"

    val done = agent.resume(ThreadId("t1"), Map(first.approve(id))).value
    ran.get shouldBe 1
    done.answer.value should include("ran")
    agent.children(ThreadId("t1")).value.loneElement.status shouldBe CheckpointStatus.Completed
  }

  "a child's steps" should "be capped by the parent turn's remaining steps" in {
    val client = RoutingClient(delegatingOnce.orElse(looping("child_tool")))
    val result = lead(client)
      .withMaxSteps(2)
      .withSubagents(researcher.withTools(ToolSet.of(echo("child_tool")).value))
      .build()
      .value
      .run(ThreadId("t1"), "go")
      .value
    // the lead's first step left one: the child made one call and stopped at its step limit
    client.sentTo("CHILD:researcher") should have size 1
    result.answer.value should include("reached its step limit")
  }

  it should "be the grant when it is below the parent's" in {
    val client = RoutingClient(delegatingOnce.orElse(looping("child_tool")))
    lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(echo("child_tool")).value)
          .withInheritance(Inheritance().withBudget(TaskBudget().withMaxSteps(3)))
      )
      .build()
      .value
      .run(ThreadId("t1"), "go")
      .value
    client.sentTo("CHILD:researcher") should have size 3
  }

  it should "be the grant above the default step limit when the child sets none of its own" in {
    val client = RoutingClient(delegatingOnce.orElse(looping("child_tool")))
    lead(client)
      .withMaxSteps(100)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(echo("child_tool")).value)
          .withInheritance(Inheritance().withBudget(TaskBudget().withMaxSteps(55)))
      )
      .build()
      .value
      .run(ThreadId("t1"), "go")
      .value
    client.sentTo("CHILD:researcher") should have size 55
  }

  "an exhausted parent step budget" should "start no child, the tool message naming only the subagent" in {
    val client = RoutingClient(delegatingOnce.orElse(answering))
    val agent  = lead(client).withMaxSteps(1).withSubagents(researcher).build().value
    val result = agent.run(ThreadId("t1"), "go").value

    result.status shouldBe AgentStatus.StepLimitReached
    val message = toolResults(result.messages).loneElement
    message should include("subagent 'researcher' was not started")
    message should include("step limit")
    (message should not).include("child thread")
    client.sentTo("CHILD:researcher") shouldBe empty
    agent.children(ThreadId("t1")).value shouldBe empty
  }

  "a child's tokens" should "be capped by what the parent's run has left" in {
    val client = RoutingClient(delegatingOnce.orElse(looping("child_tool")))
    val result = lead(client)
      .withSubagents(researcher.withTools(ToolSet.of(echo("child_tool")).value))
      .build()
      .value
      // the lead's call uses 30 of 40: the child has 10, so it stops after its first call (30)
      .run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withMaxTokens(40L)))
      .value
    client.sentTo("CHILD:researcher") should have size 1
    toolResults(result.messages).loneElement should include("token budget")
  }

  it should "be the grant when it is below the parent's remainder" in {
    val client = RoutingClient(delegatingOnce.orElse(looping("child_tool")))
    lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(echo("child_tool")).value)
          .withInheritance(Inheritance().withBudget(TaskBudget().withMaxTokens(70L)))
      )
      .build()
      .value
      .run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withMaxTokens(10000L)))
      .value
    client.sentTo("CHILD:researcher") should have size 3 // 30, 60, then 90 is over 70
  }

  "an exhausted parent token budget" should "start no child" in {
    val client = RoutingClient(delegatingOnce.orElse(answering))
    val agent  = lead(client).withSubagents(researcher).build().value
    val result =
      agent.run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withMaxTokens(30L))).value

    result.status shouldBe AgentStatus.TokenLimitReached
    toolResults(result.messages).loneElement should include("subagent 'researcher' was not started")
    toolResults(result.messages).loneElement should include("token budget")
    agent.children(ThreadId("t1")).value shouldBe empty
  }

  "a child's concurrency and deadline" should "never be above its parent's" in {
    val seen = new AtomicReference[(Int, Option[Instant])]((0, None))
    val peek = AgentTool(SpecText.spec("peek")) { (_, ctx) =>
      seen.set(ctx.run.config.budgets.maxConcurrency -> ctx.run.deadline)
      ToolOutcome.Success(ToolContent.Json(ujson.Str("peeked")))
    }
    def run(grant: TaskBudget): (Int, Option[Instant]) = {
      val client = RoutingClient(delegatingOnce.orElse {
        case Child("researcher", messages) if toolResults(messages).isEmpty =>
          Right(calling(ToolCall("k1", "peek", ujson.Obj("text" -> "x"))))
        case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
      })
      lead(client)
        .withSubagents(researcher.withTools(ToolSet.of(peek).value).withInheritance(Inheritance().withBudget(grant)))
        .build()
        .value
        .run(
          ThreadId("t1"),
          "go",
          RunConfig().withBudgets(RunBudgets.default.withMaxConcurrency(8).withTimeout(30.seconds))
        )
        .value
      seen.get
    }
    val before               = Instant.now()
    val (wide, parentCapped) = run(TaskBudget().withMaxConcurrency(20).withTimeout(1.hour))
    wide shouldBe 8
    parentCapped.value.isAfter(before.plusSeconds(31)) shouldBe false
    val (narrow, granted) = run(TaskBudget().withMaxConcurrency(2).withTimeout(5.seconds))
    narrow shouldBe 2
    granted.value.isAfter(Instant.now().plusSeconds(6)) shouldBe false
  }

  "an expired parent deadline" should "start no child and fail with the parent's DeadlineExceeded" in {
    val clock = new ManualClock(Instant.parse("2026-10-19T00:00:00Z"))
    val client = RoutingClient {
      case Lead(_) =>
        clock.advance(1.minute) // the model call outlives the run's deadline, by the runtime's clock
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "x"))))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
    }
    val agent = lead(client).withRuntime(GraphRuntime.inMemory(clock)).withSubagents(researcher).build().value
    val error = agent
      .run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withTimeout(30.seconds)))
      .left
      .value
    // the call fails (Fatal) with the parent's own DeadlineExceeded, so the run fails and its recover runs it again
    error match {
      case GraphError.NodeFailed(_, _, GraphError.ToolFailed(_, _, late: GraphError.DeadlineExceeded)) =>
        late.threadId shouldBe "t1"
      case other => fail(s"expected the parent's DeadlineExceeded, got $other")
    }
    client.sentTo("CHILD:researcher") shouldBe empty
    agent.children(ThreadId("t1")).value shouldBe empty
  }

  "a child's grant" should "be its whole life: a recovered child has its tokens less what it used before" in {
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(failingOnce("probe", fails)).value)
          .withFailure(ChildFailure.Fail)
          .withInheritance(Inheritance().withBudget(TaskBudget().withMaxTokens(50L)))
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go").left.value.message should include("probe failed")
    // 30 used before: the recovered child has 20, so it stops after one more call (a fresh 50 would allow two)
    agent.recover(ThreadId("t1")).left.value.message should include("token budget")
    client.sentTo("CHILD:researcher") should have size 2
  }

  it should "settle a recovered child whose tokens are spent, without running it" in {
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(failingOnce("probe", fails)).value)
          .withFailure(ChildFailure.Fail)
          .withInheritance(Inheritance().withBudget(TaskBudget().withMaxTokens(30L)))
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go").left.value
    agent.recover(ThreadId("t1")).left.value.message should include("token budget")
    client.sentTo("CHILD:researcher") should have size 1
    agent.children(ThreadId("t1")).value.loneElement.status shouldBe CheckpointStatus.Failed
    // and again: the settled child's refusal, still with no run
    agent.recover(ThreadId("t1")).left.value.message should include("token budget")
    client.sentTo("CHILD:researcher") should have size 1
  }

  it should "keep the limit the parent's remainder set at the first start, when the recovery runs with no budget" in {
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher.withTools(ToolSet.of(failingOnce("probe", fails)).value).withFailure(ChildFailure.Fail)
      )
      .build()
      .value
    // the lead's call uses 30 of 100: the child's whole life has 70
    agent.run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withMaxTokens(100L))).left.value
    // recovered with no token budget: the child still has 70 less its 30, so two more calls (60) and no more
    agent.recover(ThreadId("t1")).left.value.message should include("token budget")
    client.sentTo("CHILD:researcher") should have size 3
  }

  it should "never exceed the tokens its first admission was granted when the parent recovers with a larger budget" in {
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher.withTools(ToolSet.of(failingOnce("probe", fails)).value).withFailure(ChildFailure.Fail)
      )
      .build()
      .value
    // the lead's call uses 30 of 70: the child is first granted 40, and its first call uses 30 of them
    agent.run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withMaxTokens(70L))).left.value
    // a fresh, far larger budget: the child still has only 10 left, so one more call and then the limit
    agent
      .recover(ThreadId("t1"), RunConfig().withBudgets(RunBudgets.default.withMaxTokens(100000L)))
      .left
      .value
      .message should include("token budget")
    client.sentTo("CHILD:researcher") should have size 2
  }

  it should "let a recovery under a smaller parent budget use that run's allowance, not settle the child" in {
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher.withTools(ToolSet.of(failingOnce("probe", fails)).value).withFailure(ChildFailure.Fail)
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withMaxTokens(100L))).left.value
    // the child's whole life has 70, of which it used 30; the recovered run allows 20 more: one call, then the limit
    agent
      .recover(ThreadId("t1"), RunConfig().withBudgets(RunBudgets.default.withMaxTokens(20L)))
      .left
      .value
      .message should include("token budget")
    client.sentTo("CHILD:researcher") should have size 2
  }

  it should "keep the concurrency of its first start when the recovery runs with a wider one" in {
    val fails = new AtomicBoolean(true)
    val seen  = new AtomicInteger(0)
    val probe = AgentTool(SpecText.spec("probe")) { (_, ctx) =>
      seen.set(ctx.run.config.budgets.maxConcurrency)
      if (fails.getAndSet(false)) ToolOutcome.Fatal(ProcessingError("probe", "probe failed"))
      else ToolOutcome.Success(ToolContent.Json(ujson.Str("probed")))
    }
    val client = RoutingClient(delegatingOnce.orElse {
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "probe", ujson.Obj("text" -> "x"))))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"s","sources":0}"""))
    })
    val agent = lead(client)
      .withSubagents(researcher.withTools(ToolSet.of(probe).value).withFailure(ChildFailure.Fail))
      .build()
      .value
    agent.run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withMaxConcurrency(1))).left.value
    seen.get shouldBe 1
    agent.recover(ThreadId("t1")).value // the default config's maxConcurrency is 16
    seen.get shouldBe 1
  }

  it should "count a recovered child's steps from its first attempt" in {
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(failingOnce("probe", fails)).value)
          .withFailure(ChildFailure.Fail)
          .withInheritance(Inheritance().withBudget(TaskBudget().withMaxSteps(2)))
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go").left.value
    agent.recover(ThreadId("t1")).left.value.message should include("step limit")
    client.sentTo("CHILD:researcher") should have size 2
  }

  it should "run its time from the child's first start, not from the recovery" in {
    val clock  = new ManualClock(Instant.now())
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withRuntime(GraphRuntime.inMemory(clock))
      .withSubagents(
        researcher
          .withTools(ToolSet.of(failingOnce("probe", fails)).value)
          .withFailure(ChildFailure.Fail)
          .withInheritance(Inheritance().withBudget(TaskBudget().withTimeout(1.minute)))
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go").left.value
    clock.advance(2.minutes)
    agent.recover(ThreadId("t1")).left.value.message should include("deadline")
    client.sentTo("CHILD:researcher") should have size 1
    // settled Failed, as a child timing out mid-run is, so a later recover reads the refusal back and runs nothing
    agent.children(ThreadId("t1")).value.loneElement.status shouldBe CheckpointStatus.Failed
    agent.recover(ThreadId("t1")).left.value.message should include("DeadlineExceeded")
    client.sentTo("CHILD:researcher") should have size 1
  }

  it should "report a child whose own time is spent at recovery as a Failed refusal under Report" in {
    val clock   = new ManualClock(Instant.now())
    val client  = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val runtime = GraphRuntime.inMemory(clock)
    // a first parent, under Fail, leaves the child Running; a second one, under Report, recovers the same thread
    def parent(policy: ChildFailure) =
      lead(client)
        .withRuntime(runtime)
        .withSubagents(
          researcher
            .withTools(ToolSet.of(failingOnce("probe", new AtomicBoolean(policy == ChildFailure.Fail))).value)
            .withFailure(policy)
            .withInheritance(Inheritance().withBudget(TaskBudget().withTimeout(1.minute)))
        )
        .build()
        .value
    parent(ChildFailure.Fail).run(ThreadId("t1"), "go").left.value
    clock.advance(2.minutes)
    val result = parent(ChildFailure.Report).recover(ThreadId("t1")).value
    toolResults(result.messages).loneElement should include("DeadlineExceeded")
    client.sentTo("CHILD:researcher") should have size 1
  }

  it should "stop waiting on a busy child at its own stored deadline, and settle it Failed once free (#2086)" in {
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val store  = new org.llm4s.agent.graph.InMemoryCheckpointer()
    val claims = org.llm4s.agent.graph.ClaimPolicy(ttl = 3.seconds, renewEvery = 100.millis)
    val agent = lead(client)
      .withRuntime(new GraphRuntime(store, claims = claims))
      .withSubagents(
        researcher
          .withTools(ToolSet.of(failingOnce("probe", fails)).value)
          .withFailure(ChildFailure.Fail)
          .withInheritance(Inheritance().withBudget(TaskBudget().withTimeout(1500.millis)))
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go").left.value
    val child = agent.children(ThreadId("t1")).value.loneElement.threadId

    // another runtime's run holds the child's claim past the child's own deadline
    val held = store
      .claim(child, org.llm4s.agent.graph.ClaimRequest(org.llm4s.agent.graph.RunId("elsewhere"), None, 30.seconds))
      .value
    val began = System.nanoTime()
    agent.recover(ThreadId("t1")).left.value.message should include("held by run 'elsewhere'")
    // cut off at the child's deadline (1.5 s after it started), not after twice the claim's life (6 s)
    (System.nanoTime() - began).nanos should be < claims.ttl
    store.release(child, held.token).value

    agent.recover(ThreadId("t1")).left.value.message should include("deadline")
    agent.children(ThreadId("t1")).value.loneElement.status shouldBe CheckpointStatus.Failed
    client.sentTo("CHILD:researcher") should have size 1
  }

  /** A lead whose researcher calls `probe` each step, asking approval for every call; with `grant` and `runtime`. */
  private def askingFamily(client: RoutingClient, ran: AtomicInteger, grant: TaskBudget, runtime: GraphRuntime) = {
    val probe = AgentTool(SpecText.spec("probe")) { (_, _) =>
      ran.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str("probed")))
    }
    lead(client)
      .withRuntime(runtime)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(probe).value)
          .withMiddleware(new PermissionMiddleware(Seq(PermissionRule.ask(Operation.Invoke, "probe"))))
          .withInheritance(Inheritance().withBudget(grant))
      )
      .build()
      .value
  }

  private def delegatedId(result: org.llm4s.agent.AgentResult) = result.status match {
    case s: AgentStatus.Suspended => s.delegated.loneElement._1
    case other                    => fail(s"expected Suspended, got $other")
  }

  "a resumed child" should "be settled Failed without running when its own granted time has passed" in {
    val clock  = new ManualClock(Instant.now())
    val ran    = new AtomicInteger(0)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent  = askingFamily(client, ran, TaskBudget().withTimeout(1.minute), GraphRuntime.inMemory(clock))
    val first  = agent.run(ThreadId("t1"), "go").value
    val id     = delegatedId(first)
    clock.advance(2.minutes)

    val done = agent.resume(ThreadId("t1"), Map(first.approve(id))).value
    ran.get shouldBe 0
    toolResults(done.messages).loneElement should include("DeadlineExceeded")
    agent.children(ThreadId("t1")).value.loneElement.status shouldBe CheckpointStatus.Failed
    client.sentTo("CHILD:researcher") should have size 1
  }

  it should "be settled TokenLimitReached without running when its token grant is spent" in {
    val ran    = new AtomicInteger(0)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent  = askingFamily(client, ran, TaskBudget().withMaxTokens(30L), GraphRuntime.inMemory())
    val first  = agent.run(ThreadId("t1"), "go").value // the child's one call used its 30

    val done = agent.resume(ThreadId("t1"), Map(first.approve(delegatedId(first)))).value
    ran.get shouldBe 0
    toolResults(done.messages).loneElement should include("token budget")
    client.sentTo("CHILD:researcher") should have size 1
  }

  it should "run with only what is left of its token grant" in {
    val ran    = new AtomicInteger(0)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent  = askingFamily(client, ran, TaskBudget().withMaxTokens(50L), GraphRuntime.inMemory())
    val first  = agent.run(ThreadId("t1"), "go").value

    // 20 left: the approved probe runs, the next call (30) is made, and then the grant is spent
    val second = agent.resume(ThreadId("t1"), Map(first.approve(delegatedId(first)))).value
    ran.get shouldBe 1
    client.sentTo("CHILD:researcher") should have size 2
    val third = agent.resume(ThreadId("t1"), Map(second.approve(delegatedId(second)))).value
    ran.get shouldBe 1
    toolResults(third.messages).loneElement should include("token budget")
  }

  it should "record a grant a resume narrowed, so a later resume never widens it" in {
    val seen = new java.util.concurrent.CopyOnWriteArrayList[Int]()
    val probe = AgentTool(SpecText.spec("probe")) { (_, ctx) =>
      seen.add(ctx.run.config.budgets.maxConcurrency)
      ToolOutcome.Success(ToolContent.Json(ujson.Str("probed")))
    }
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(probe).value)
          .withMiddleware(new PermissionMiddleware(Seq(PermissionRule.ask(Operation.Invoke, "probe"))))
      )
      .build()
      .value
    def budget(n: Int) = RunConfig().withBudgets(RunBudgets.default.withMaxConcurrency(n))
    val first          = agent.run(ThreadId("t1"), "go", budget(8)).value
    val second         = agent.resume(ThreadId("t1"), Map(first.approve(delegatedId(first))), budget(2)).value
    agent.resume(ThreadId("t1"), Map(second.approve(delegatedId(second))), budget(16)).value
    scala.jdk.CollectionConverters.ListHasAsScala(seen).asScala.toVector shouldBe Vector(2, 2)
  }

  "Agent.delegation" should "list a child's middleware in the order its stack runs them" in {
    val client = RoutingClient(PartialFunction.empty)
    val a      = new AgentMiddleware { val id: MiddlewareId = MiddlewareId("a") }
    val b = new AgentMiddleware {
      val id: MiddlewareId                       = MiddlewareId("b")
      override def runsBefore: Set[MiddlewareId] = Set(MiddlewareId("a"))
    }
    val agent = lead(client).withSubagents(researcher.withMiddleware(a, b)).build().value
    agent.delegation.subagents.loneElement.middleware.map(_.value) shouldBe Vector("b", "a")
  }

  it should "count a failed attempt's completed model call as a step" in {
    // every child model call completes, then its step fails: nothing commits a step
    val failing = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("fail-after-call")
      override def wrapModelCall(
        request: org.llm4s.agent.graph.middleware.ModelRequest,
        context: ModelContext
      )(
        next: org.llm4s.agent.graph.middleware.ModelRequest => Result[org.llm4s.agent.graph.middleware.ModelResponse]
      ): Result[org.llm4s.agent.graph.middleware.ModelResponse] =
        next(request).flatMap(_ => Left(ProcessingError("model", "lost after the call")))
    }
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(echo("probe")).value)
          .withMiddleware(failing)
          .withFailure(ChildFailure.Fail)
          .withInheritance(Inheritance().withBudget(TaskBudget().withMaxSteps(1)))
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go").left.value
    agent.recover(ThreadId("t1")).left.value.message should include("step limit")
    agent.recover(ThreadId("t1")).left.value.message should include("step limit")
    client.sentTo("CHILD:researcher") should have size 1
  }

  it should "keep the deadline of its first start when its parent recovers with a later one" in {
    val clock  = new ManualClock(Instant.now())
    val fails  = new AtomicBoolean(true)
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withRuntime(GraphRuntime.inMemory(clock))
      .withSubagents(
        researcher.withTools(ToolSet.of(failingOnce("probe", fails)).value).withFailure(ChildFailure.Fail)
      )
      .build()
      .value
    // the parent has a minute: the child's first admission fixes its deadline there
    agent.run(ThreadId("t1"), "go", RunConfig().withBudgets(RunBudgets.default.withTimeout(1.minute))).left.value
    clock.advance(2.minutes)
    // the recovered parent has ten more minutes; the child's deadline has still passed
    agent
      .recover(ThreadId("t1"), RunConfig().withBudgets(RunBudgets.default.withTimeout(10.minutes)))
      .left
      .value
      .message should include("DeadlineExceeded")
    client.sentTo("CHILD:researcher") should have size 1
  }

  /**
   * A child left `Running` by a first parent under `Fail`, then a second parent under `Report` recovering the thread
   * with a nested stream after `before`: the merged stream and the parent's stored `TaskEnded`s.
   */
  private def settledOnRecovery(
    grant: TaskBudget,
    before: ManualClock => Unit
  ): (Vector[org.llm4s.agent.graph.StreamEvent], Vector[org.llm4s.agent.events.TaskEnded], String) = {
    val clock   = new ManualClock(Instant.now())
    val runtime = GraphRuntime.inMemory(clock)
    val client  = RoutingClient(delegatingOnce.orElse(looping("probe")))
    def parent(policy: ChildFailure) =
      lead(client)
        .withRuntime(runtime)
        .withSubagents(
          researcher
            .withTools(ToolSet.of(failingOnce("probe", new AtomicBoolean(policy == ChildFailure.Fail))).value)
            .withFailure(policy)
            .withInheritance(Inheritance().withBudget(grant))
        )
        .build()
        .value
    parent(ChildFailure.Fail).run(ThreadId("t1"), "go").left.value
    before(clock)
    val seen       = new java.util.concurrent.CopyOnWriteArrayList[org.llm4s.agent.graph.StreamEvent]()
    val recovering = parent(ChildFailure.Report)
    recovering.streamRecover(ThreadId("t1"), nested = true)(seen.add(_): Unit).value.await().value
    client.sentTo("CHILD:researcher") should have size 1 // settled, never run again
    val ends = runtime
      .storedEvents(ThreadId("t1"), 0L, 1000)
      .value
      .flatMap(r =>
        org.llm4s.agent.events.SubagentEvents.TaskEnded.unapply(org.llm4s.agent.graph.StreamEvent.Durable(r))
      )
    val child      = recovering.children(ThreadId("t1")).value.loneElement.threadId
    val settledRun = runtime.history(child, None, 1).value.head.runId
    (scala.jdk.CollectionConverters.ListHasAsScala(seen).asScala.toVector, ends, settledRun)
  }

  private def assertSettledStream(
    seen: Vector[org.llm4s.agent.graph.StreamEvent],
    ends: Vector[org.llm4s.agent.events.TaskEnded],
    settledRun: String
  ) = {
    import org.llm4s.agent.graph.StreamEvent
    val ended = ends.loneElement
    ended.child.runId.value shouldBe settledRun
    // the settlement ran nothing, so its run is announced with a gap, and the call's end follows it
    val started = seen.indexWhere(e =>
      org.llm4s.agent.events.SubagentEvents.TaskStarted.unapply(e).exists(_.child.runId.value == settledRun)
    )
    val gap = seen.indexWhere {
      case StreamEvent.Nested(c, StreamEvent.LiveGap(0)) => c.runId.value == settledRun
      case _                                             => false
    }
    val end = seen.indexWhere(org.llm4s.agent.events.SubagentEvents.TaskEnded.unapply(_).isDefined)
    started should be >= 0
    started should be < gap
    gap should be < end
    ended
  }

  "a child settled on a spent grant" should "give the call one TaskEnded, after a gap naming the settled run" in {
    val (seen, ends, run) = settledOnRecovery(TaskBudget().withMaxTokens(30L), _ => ())
    assertSettledStream(seen, ends, run).status should include("token")
  }

  it should "do so for a spent granted time too" in {
    val (seen, ends, run) = settledOnRecovery(TaskBudget().withTimeout(1.minute), _.advance(2.minutes))
    assertSettledStream(seen, ends, run).status shouldBe "failed"
  }

  it should "settle a child whose failed attempt exhausted its steps, its lost call reported with the settlement" in {
    val runtime = GraphRuntime.inMemory()
    val client  = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val failingStep = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("fail-after-call")
      override def wrapModelCall(
        request: org.llm4s.agent.graph.middleware.ModelRequest,
        context: ModelContext
      )(
        next: org.llm4s.agent.graph.middleware.ModelRequest => Result[org.llm4s.agent.graph.middleware.ModelResponse]
      ): Result[org.llm4s.agent.graph.middleware.ModelResponse] =
        next(request).flatMap(_ => Left(ProcessingError("model", "lost after the call")))
    }
    def parent(policy: ChildFailure) =
      lead(client)
        .withRuntime(runtime)
        .withSubagents(
          researcher
            .withTools(ToolSet.of(echo("probe")).value)
            .withMiddleware(failingStep)
            .withFailure(policy)
            .withInheritance(Inheritance().withBudget(TaskBudget().withMaxSteps(1)))
        )
        .build()
        .value
    // the child's one model call completes and its step fails: the parent fails with the child left Running
    parent(ChildFailure.Fail).run(ThreadId("t1"), "go").left.value
    // the replayed call settles it on the spent step - no model call - and reports the lost call in its roll-up
    val result = parent(ChildFailure.Report).recover(ThreadId("t1")).value
    toolResults(result.messages).loneElement should include("step limit")
    client.sentTo("CHILD:researcher") should have size 1
    result.usage.requestCount shouldBe 3L // the lead's two calls and the child's lost one, counted once
  }

  it should "count a failed attempt's agent step once, not a middleware's model call beside it" in {
    val failing = new AtomicBoolean(true)
    // a summariser-like hook: its own model call, then the agent's, then (the first time) the step fails
    val summarising = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("summarising")
      override def wrapModelCall(
        request: org.llm4s.agent.graph.middleware.ModelRequest,
        context: ModelContext
      )(
        next: org.llm4s.agent.graph.middleware.ModelRequest => Result[org.llm4s.agent.graph.middleware.ModelResponse]
      ): Result[org.llm4s.agent.graph.middleware.ModelResponse] = {
        context.recordUsage("summariser", TokenUsage(promptTokens = 1, completionTokens = 1, totalTokens = 2))
        next(request).flatMap(r =>
          if (failing.getAndSet(false)) Left(ProcessingError("model", "lost after the call")) else Right(r)
        )
      }
    }
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    val agent = lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(echo("probe")).value)
          .withMiddleware(summarising)
          .withFailure(ChildFailure.Fail)
          .withInheritance(Inheritance().withBudget(TaskBudget().withMaxSteps(2)))
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go").left.value
    // the failed attempt used one of the two steps: one more model step, then the limit
    agent.recover(ThreadId("t1")).left.value.message should include("step limit")
    client.sentTo("CHILD:researcher") should have size 2
  }

  it should "cap its step grant by the child's own maxSteps before deducting a failed attempt's call" in {
    val failing = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("fail-after-call")
      override def wrapModelCall(
        request: org.llm4s.agent.graph.middleware.ModelRequest,
        context: ModelContext
      )(
        next: org.llm4s.agent.graph.middleware.ModelRequest => Result[org.llm4s.agent.graph.middleware.ModelResponse]
      ): Result[org.llm4s.agent.graph.middleware.ModelResponse] =
        next(request).flatMap(_ => Left(ProcessingError("model", "lost after the call")))
    }
    val client = RoutingClient(delegatingOnce.orElse(looping("probe")))
    // no TaskBudget.maxSteps, and the parent has 50: the child's own limit of 1 is its whole life
    val agent = lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(echo("probe")).value)
          .withMiddleware(failing)
          .withMaxSteps(1)
          .withFailure(ChildFailure.Fail)
      )
      .build()
      .value
    agent.run(ThreadId("t1"), "go").left.value
    agent.recover(ThreadId("t1")).left.value.message should include("step limit")
    client.sentTo("CHILD:researcher") should have size 1
  }

  "Agent.delegation" should "list a child's tools in the order its model is offered them" in {
    def contributing(name: String, before: Set[String]) = new AgentMiddleware {
      val id: MiddlewareId                       = MiddlewareId(name)
      override def runsBefore: Set[MiddlewareId] = before.map(MiddlewareId(_))
      override val tools: Vector[AgentTool[?]]   = Vector(echo(s"tool_$name"))
    }
    val client = RoutingClient(delegatingOnce.orElse(answering))
    val agent = lead(client)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(echo("own")).value)
          .withMiddleware(contributing("a", Set.empty), contributing("b", Set("a")))
      )
      .build()
      .value
    val planned = agent.delegation.subagents.loneElement.tools.map(_.name)
    planned shouldBe Vector("own", "tool_b", "tool_a")
    agent.run(ThreadId("t1"), "go").value
    client.toolsSentTo("CHILD:researcher").loneElement shouldBe planned
  }

  it should "resolve a delegating child's middleware with its delegation middleware included" in {
    val client = RoutingClient(PartialFunction.empty)
    val early = new AgentMiddleware {
      val id: MiddlewareId                       = MiddlewareId("early")
      override def runsBefore: Set[MiddlewareId] = Set(MiddlewareId("delegation"))
    }
    val agent = lead(client)
      .withSubagents(
        researcher.withMiddleware(early).withSubagents(general).withInheritance(Inheritance().withDelegate(true))
      )
      .withDelegation(DelegationConfig().withMaxDepth(2))
      .build()
      .value
    agent.delegation.subagents.loneElement.middleware.map(_.value) shouldBe Vector("early", "delegation")
  }

  "a middleware grant naming a PermissionMiddleware" should "be refused at build(): ancestors' rules already apply" in {
    val client = RoutingClient(PartialFunction.empty)
    lead(client)
      .withMiddleware(new PermissionMiddleware(Seq(PermissionRule.allow(Operation.Invoke, "**"))))
      .withSubagents(researcher.withInheritance(Inheritance().withMiddleware(MiddlewareId("permissions"))))
      .build()
      .left
      .value
      .message should include("PermissionMiddleware cannot be granted")
  }

  "a child without delegate" should "have no task tool even when it declares subagents" in {
    def childTools(inheritance: Inheritance, maxDepth: Int): Vector[String] = {
      val client = RoutingClient(delegatingOnce.orElse(answering))
      val agent = lead(client)
        .withSubagents(researcher.withSubagents(general).withInheritance(inheritance))
        .withDelegation(DelegationConfig().withMaxDepth(maxDepth))
        .build()
        .value
      agent.run(ThreadId("t1"), "go").value
      val plan = agent.delegation.subagents.loneElement
      plan.delegates shouldBe client.toolsSentTo("CHILD:researcher").loneElement.contains("task")
      client.toolsSentTo("CHILD:researcher").loneElement
    }
    childTools(Inheritance.none, maxDepth = 2) shouldBe empty
    childTools(Inheritance().withDelegate(true), maxDepth = 1) shouldBe empty // no room under maxDepth
    childTools(Inheritance().withDelegate(true), maxDepth = 2) shouldBe Vector("task")
  }

  "a child" should "trace to its parent's tracing (rule 16)" in {
    val tracing = new Recording
    val client  = RoutingClient(delegatingOnce.orElse(answering))
    lead(client).withTracing(tracing).withSubagents(researcher).build().value.run(ThreadId("t1"), "go").value
    (tracing.endedAgents should contain).allOf("lead", "researcher")
  }

  "the child's model" should "be the parent's client and options unless withModel sets them" in {
    val client = RoutingClient(delegatingOnce.orElse(answering))
    lead(client)
      .withCompletionOptions(CompletionOptions().withTemperature(0.123))
      .withSubagents(researcher)
      .build()
      .value
      .run(ThreadId("t1"), "go")
      .value
    client.optionsSentTo("CHILD:researcher").loneElement.temperature shouldBe 0.123
  }

  "RunContext.remainingSteps" should "give a tool the model calls its turn has left" in {
    val seen = new AtomicReference[Option[Int]](None)
    val peek = AgentTool(SpecText.spec("peek")) { (_, ctx) =>
      seen.set(ctx.run.remainingSteps)
      ToolOutcome.Success(ToolContent.Json(ujson.Str("peeked")))
    }
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("p1", "peek", ujson.Obj("text" -> "x"))))
      case Lead(_) => Right(CompletionFixture.simple("done"))
    }
    lead(client).withMaxSteps(5).withTools(ToolSet.of(peek).value).build().value.run(ThreadId("t1"), "go").value
    seen.get shouldBe Some(4)
  }

  "Agent.delegation" should "show each subagent's tools, middleware, rules, model, budget and delegation as they run" in {
    val memory = new Memory
    val client = RoutingClient(delegatingOnce.orElse(answering))
    val rules  = Vector(PermissionRule.allow(Operation.Invoke, "**"))
    val agent = lead(client)
      .withTools(ToolSet.of(echo("shared"), echo("private")).value)
      .withMiddleware(new PermissionMiddleware(rules), memory)
      .withSubagents(
        researcher
          .withTools(ToolSet.of(echo("own")).value)
          .withInheritance(
            Inheritance()
              .withTools(ToolGrant.Only(Set("shared")))
              .withMiddleware(Memory.Id)
              .withBudget(TaskBudget().withMaxSteps(4))
          ),
        general.withModel(client, CompletionOptions())
      )
      .build()
      .value
    val plan = agent.delegation
    val r    = plan.find(agent.id, researcher.id).value

    r.toolName shouldBe "task_researcher"
    r.tools shouldBe Vector(PlannedTool("own", granted = false), PlannedTool("shared", granted = true))
    r.middleware.map(_.value) shouldBe Vector("ancestor-permissions", "memory")
    r.ancestorRules.loneElement.rules shouldBe rules
    r.ancestorRules.loneElement.agent shouldBe agent.id
    r.modelInherited shouldBe true
    r.budget shouldBe TaskBudget().withMaxSteps(4)
    r.delegates shouldBe false
    r.depth shouldBe 1
    plan.find(agent.id, general.id).value.modelInherited shouldBe false
    plan.toString should include("lead -> researcher via task_researcher: tools [own, shared (granted)]")

    agent.run(ThreadId("t1"), "go").value
    client.toolsSentTo("CHILD:researcher").loneElement.toSet shouldBe r.tools.map(_.name).toSet
  }

  it should "order a harnessed child's stack ancestors, granted, harness, own" in {
    val client = RoutingClient(PartialFunction.empty)
    val own    = new AgentMiddleware { val id: MiddlewareId = MiddlewareId("own") }
    val agent = lead(client)
      .withMiddleware(new PermissionMiddleware(Vector(PermissionRule.allow(Operation.Invoke, "**"))), new Memory)
      .withSubagents(
        TypedAgent("researcher", "Researches", questionSchema, findingsSchema)
          .withHarness(org.llm4s.agent.harness.HarnessProfile().withTodos(org.llm4s.agent.harness.TodoConfig()))
          .withMiddleware(own)
          .withInheritance(Inheritance().withMiddleware(Memory.Id))
      )
      .build()
      .value
    agent.delegation.subagents.loneElement.middleware.map(_.value) shouldBe
      Vector("ancestor-permissions", "memory", "todos", "own")
  }

  "build()" should "refuse a child middleware ordered outside the ancestors' permissions" in {
    val client = RoutingClient(PartialFunction.empty)
    val early = new AgentMiddleware {
      val id: MiddlewareId                       = MiddlewareId("early")
      override def runsBefore: Set[MiddlewareId] = Set(MiddlewareId("ancestor-permissions"))
    }
    lead(client)
      .withMiddleware(new PermissionMiddleware(Vector(PermissionRule.allow(Operation.Invoke, "**"))))
      .withSubagents(researcher.withMiddleware(early))
      .build()
      .left
      .value
      .message should include("ancestor-permissions")
  }

  it should "refuse a budget value that is not positive" in {
    val client = RoutingClient(PartialFunction.empty)
    lead(client)
      .withSubagents(researcher.withInheritance(Inheritance().withBudget(TaskBudget().withMaxTokens(0L))))
      .build()
      .left
      .value
      .message should include("maxTokens must be positive")
  }
}

object InheritanceSpec {
  import DelegationSpec.SpecText

  /** A memory-like middleware: a prompt section, counting each read. */
  final class Memory extends AgentMiddleware {
    val id: MiddlewareId = Memory.Id
    val reads            = new AtomicInteger(0)
    override def promptSections(context: ModelContext): Result[Vector[PromptSection]] = {
      reads.incrementAndGet()
      Right(Vector(PromptSection("memory", "MEMORY-SECTION")))
    }
  }
  object Memory {
    val Id: MiddlewareId = MiddlewareId("memory")
  }

  /** A tool that fails the run (`Fatal`) the first time, while `fails` is set, and succeeds after. */
  def failingOnce(name: String, fails: AtomicBoolean): AgentTool[SpecText] =
    AgentTool(SpecText.spec(name)) { (_, _) =>
      if (fails.getAndSet(false)) ToolOutcome.Fatal(ProcessingError(name, s"$name failed"))
      else ToolOutcome.Success(ToolContent.Json(ujson.Str("probed")))
    }

  /** Records the agent of each `AgentRunEnded`. */
  final class Recording extends org.llm4s.trace.Tracing {
    private val ended = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    def traceEvent(event: org.llm4s.trace.TraceEvent): Result[Unit] = {
      event match {
        case e: org.llm4s.trace.TraceEvent.AgentRunEnded => ended.add(e.agent)
        case _                                           => ()
      }
      Right(())
    }
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
    def endedAgents: Vector[String] = scala.jdk.CollectionConverters.CollectionHasAsScala(ended).asScala.toVector
  }

  /** A clock moved by hand. */
  final class ManualClock(start: Instant) extends Clock {
    private val now                            = new AtomicReference[Instant](start)
    def advance(by: FiniteDuration): Unit      = now.updateAndGet(_.plusNanos(by.toNanos))
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = now.get
  }
}
