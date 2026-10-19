package org.llm4s.agent.delegation

import org.llm4s.agent.{ AgentId, AgentResult, AgentStatus, CompletionFixture }
import org.llm4s.agent.graph.{
  BreakpointPhase,
  CheckpointStatus,
  GraphError,
  GraphRuntime,
  IdempotencyKey,
  InterruptId,
  NodeId,
  RunId,
  ThreadId,
  ToolCallId
}
import org.llm4s.agent.graph.toolloop.{ ApprovalRequest, ApprovalSource, BreakpointRequest }
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  MiddlewareId,
  PermissionMiddleware,
  PermissionRule,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ AgentTool, Operation, ToolContent, ToolContext, ToolOutcome, ToolSet }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model.*
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.jdk.CollectionConverters.*

/** Child suspension through the parent (#2050, design 4.46): a suspended child's interrupts, answered by the parent. */
class DelegatedSuspensionSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with LoneElement {
  import DelegationSpec.*
  import DelegatedSuspensionSpec.*

  "a child approval" should "surface in the parent under a composite id, and approving it run the child's tool once" in {
    val runs   = new AtomicInteger(0)
    val client = approvingClient()
    val agent  = lead(client).withSubagents(asking(probe(runs))).build().value

    val first     = agent.run(ThreadId("t1"), "go").value
    val suspended = suspendedOf(first)
    suspended.questions shouldBe empty
    suspended.approvals shouldBe empty
    val (id, request) = suspended.delegated.loneElement
    id.value should include("~")
    request.child.agent.value shouldBe "researcher"
    request.child.depth shouldBe 1
    request.child.threadId shouldBe agent.children(ThreadId("t1")).value.loneElement.threadId
    request.request shouldBe a[ChildInterrupt.Approval]
    request.request.asInstanceOf[ChildInterrupt.Approval].request.call.name shouldBe "probe"
    runs.get shouldBe 0

    val done = agent.resume(ThreadId("t1"), Map(first.approve(id))).value
    done.answer.value should include("probed once")
    runs.get shouldBe 1
    agent.children(ThreadId("t1")).value.loneElement.status shouldBe CheckpointStatus.Completed
    // the lead's two calls and the child's two, each counted once (rules 10, 11)
    done.usage.requestCount shouldBe 4
  }

  it should "reach the child's model as a rejection when rejected through the parent" in {
    val runs   = new AtomicInteger(0)
    val client = approvingClient()
    val agent  = lead(client).withSubagents(asking(probe(runs))).build().value
    val first  = agent.run(ThreadId("t1"), "go").value
    val id     = suspendedOf(first).delegated.loneElement._1

    val done = agent.resume(ThreadId("t1"), Map(first.reject(id, "not today"))).value
    runs.get shouldBe 0
    toolResults(client.sentTo("CHILD:researcher").last).loneElement should include("not today")
    done.answer.value should include("composed")
  }

  it should "run the child's tool with the arguments an edit through the parent gives it" in {
    val seen   = new CopyOnWriteArrayList[String]()
    val client = approvingClient()
    val tool = AgentTool(SpecText.spec("probe")) { (args, _) =>
      seen.add(args.text)
      ToolOutcome.Success(ToolContent.Json(ujson.Str(s"probed ${args.text}")))
    }
    val agent = lead(client).withSubagents(asking(tool)).build().value
    val first = agent.run(ThreadId("t1"), "go").value
    val id    = suspendedOf(first).delegated.loneElement._1

    agent.resume(ThreadId("t1"), Map(first.edit(id, ujson.Obj("text" -> "edited")))).value.answer shouldBe defined
    seen.asScala.toVector shouldBe Vector("edited")
  }

  "two parallel children that suspend" should "be answered one at a time, the other staying pending" in {
    val runs = new AtomicInteger(0)
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(
          calling(
            call("c1", "task_researcher", ujson.Obj("topic" -> "a")),
            call("c2", "task_researcher", ujson.Obj("topic" -> "b"))
          )
        )
      case Lead(messages) => Right(CompletionFixture.simple(s"composed ${toolResults(messages).mkString(" | ")}"))
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "probe", ujson.Obj("text" -> "x"))))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"done","sources":1}"""))
    }
    val agent = lead(client).withSubagents(asking(probe(runs))).build().value
    val first = agent.run(ThreadId("t1"), "go").value
    val ids   = suspendedOf(first).delegated.map(_._1)
    ids should have size 2
    suspendedOf(first).delegated.map(_._2.child.threadId).distinct should have size 2

    val second = agent.resume(ThreadId("t1"), Map(first.approve(ids.head))).value
    runs.get shouldBe 1
    val left = suspendedOf(second).delegated.loneElement
    left._1 shouldBe ids(1)

    val done = agent.resume(ThreadId("t1"), Map(second.approve(left._1))).value
    runs.get shouldBe 2
    done.answer.value should include("composed")
    agent.children(ThreadId("t1")).value.map(_.status).distinct shouldBe Vector(CheckpointStatus.Completed)
  }

  "a child with two approvals answered one at a time" should "ask again, a new phase, for the rest" in {
    val runs = new AtomicInteger(0)
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(s"composed ${toolResults(messages).mkString}"))
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(
          calling(
            ToolCall("k1", "probe", ujson.Obj("text" -> "x")),
            ToolCall("k2", "probe", ujson.Obj("text" -> "y"))
          )
        )
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"both","sources":2}"""))
    }
    val agent   = lead(client).withSubagents(asking(probe(runs))).build().value
    val first   = agent.run(ThreadId("t1"), "go").value
    val pending = suspendedOf(first).delegated
    pending should have size 2

    val second = agent.resume(ThreadId("t1"), Map(first.approve(pending.head._1))).value
    runs.get shouldBe 1
    val rest = suspendedOf(second).delegated.loneElement
    rest._2.request.id shouldBe pending(1)._2.request.id
    rest._1 should not be pending(1)._1 // the call asked again: another parent interrupt

    agent.resume(ThreadId("t1"), Map(second.approve(rest._1))).value.answer.value should include("both")
    runs.get shouldBe 2
  }

  "a grandchild approval" should "surface in the top-level parent as p~c~g at maxDepth = 2" in {
    val runs    = new AtomicInteger(0)
    val runtime = GraphRuntime.inMemory()
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "delegate"))))
      case Lead(messages) => Right(CompletionFixture.simple(s"lead: ${toolResults(messages).mkString}"))
      case Child("general", messages) if toolResults(messages).isEmpty =>
        Right(calling(call("g1", "task_researcher", ujson.Obj("topic" -> "deep"))))
      case Child("general", messages) => Right(CompletionFixture.simple(s"general: ${toolResults(messages).mkString}"))
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "probe", ujson.Obj("text" -> "x"))))
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"deep","sources":3}"""))
    }
    val grandchild = asking(probe(runs)).bind(client, runtime).value
    val nested     = new DelegationMiddleware(Vector(grandchild), DelegationConfig().withMaxDepth(2), depth = 1)
    val agent = lead(client)
      .withRuntime(runtime)
      .withDelegation(DelegationConfig().withMaxDepth(2))
      .withSubagents(general.withMiddleware(nested))
      .build()
      .value

    val first         = agent.run(ThreadId("t1"), "go").value
    val (id, request) = suspendedOf(first).delegated.loneElement
    id.value.count(_ == '~') shouldBe 2
    request.child.agent.value shouldBe "researcher"
    request.child.depth shouldBe 2
    request.request shouldBe a[ChildInterrupt.Approval]

    val done = agent.resume(ThreadId("t1"), Map(first.approve(id))).value
    runs.get shouldBe 1
    done.answer.value should include("deep")
  }

  "a crash after the child resumed and before the parent committed" should "not resume the child twice on recover" in {
    val runs   = new AtomicInteger(0)
    val client = approvingClient()
    val crash  = new CrashOnResume
    val agent  = lead(client).withMiddleware(crash).withSubagents(asking(probe(runs))).build().value
    val first  = agent.run(ThreadId("t1"), "go").value
    val id     = suspendedOf(first).delegated.loneElement._1

    agent.resume(ThreadId("t1"), Map(first.approve(id))).isLeft shouldBe true
    runs.get shouldBe 1
    agent.children(ThreadId("t1")).value.loneElement.status shouldBe CheckpointStatus.Completed
    val childCalls = client.sentTo("CHILD:researcher").size

    val done = agent.recover(ThreadId("t1")).value
    done.answer.value should include("probed once")
    runs.get shouldBe 1
    client.sentTo("CHILD:researcher").size shouldBe childCalls
    // the child's two calls counted once, by the attempt that committed the call
    done.usage.requestCount shouldBe 4
  }

  it should "reattach a child that suspended again, its answered interrupt no longer pending, rather than resume it" in {
    val runs = new AtomicInteger(0)
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(s"composed ${toolResults(messages).mkString}"))
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(
          calling(
            ToolCall("k1", "probe", ujson.Obj("text" -> "x")),
            ToolCall("k2", "probe", ujson.Obj("text" -> "y"))
          )
        )
      case Child("researcher", _) => Right(CompletionFixture.simple("""{"summary":"both","sources":2}"""))
    }
    val agent   = lead(client).withMiddleware(new CrashOnResume).withSubagents(asking(probe(runs))).build().value
    val first   = agent.run(ThreadId("t1"), "go").value
    val pending = suspendedOf(first).delegated

    agent.resume(ThreadId("t1"), Map(first.approve(pending.head._1))).isLeft shouldBe true
    runs.get shouldBe 1
    agent.children(ThreadId("t1")).value.loneElement.status shouldBe CheckpointStatus.Suspended

    val again = agent.recover(ThreadId("t1")).value
    runs.get shouldBe 1
    val rest = suspendedOf(again).delegated.loneElement
    rest._2.request.id shouldBe pending(1)._2.request.id

    agent.resume(ThreadId("t1"), Map(again.approve(rest._1))).value.answer.value should include("both")
    runs.get shouldBe 2
  }

  "a child tool's question" should "be answered through the parent with a typed reply" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(s"composed ${toolResults(messages).mkString}"))
      case Child("researcher", messages) if toolResults(messages).isEmpty =>
        Right(calling(ToolCall("k1", "convert", ujson.Obj("text" -> "10"))))
      case Child("researcher", messages) =>
        Right(CompletionFixture.simple(s"""{"summary":"${toolResults(messages).mkString}","sources":1}"""))
    }
    val agent         = lead(client).withSubagents(researcher.withTools(ToolSet.of(new Convert).value)).build().value
    val first         = agent.run(ThreadId("t1"), "go").value
    val (id, request) = suspendedOf(first).delegated.loneElement
    val question      = request.request.asInstanceOf[ChildInterrupt.Question].request
    upickle.default.read[Unit_](question.question) shouldBe Unit_("which unit?")

    val done = agent.resume(ThreadId("t1"), Map(first.reply(id, Choice("km")))).value
    done.answer.value should include("10 km")
  }

  "Agent.resume" should "refuse a composite id that is not pending, and an answer of the wrong shape" in {
    val runs   = new AtomicInteger(0)
    val agent  = lead(approvingClient()).withSubagents(asking(probe(runs))).build().value
    val first  = agent.run(ThreadId("t1"), "go").value
    val id     = suspendedOf(first).delegated.loneElement._1
    val parent = id.value.takeWhile(_ != '~')

    agent.resume(ThreadId("t1"), Map(first.approve(InterruptId(s"$parent~nope")))).left.value shouldBe a[
      GraphError.InvalidResume
    ]
    agent.resume(ThreadId("t1"), Map(id -> ujson.Str("yes"))).left.value.message should include("not an approval")
    agent.resume(ThreadId("t1"), Map(first.approve(id), InterruptId(parent) -> ujson.Obj())).isLeft shouldBe true
    runs.get shouldBe 0
    // still pending, and answerable
    agent.resume(ThreadId("t1"), Map(first.approve(id))).value.answer shouldBe defined
    runs.get shouldBe 1
  }

  "DelegatedAnswers.group" should "group composite answers by their first segment, keeping the rest composite" in {
    val grouped = DelegatedAnswers
      .group(
        Map(
          InterruptId("p~c")   -> ujson.Num(1),
          InterruptId("p~d~g") -> ujson.Num(2),
          InterruptId("q")     -> ujson.Num(3)
        )
      )
      .value
    grouped(InterruptId("q")) shouldBe ujson.Num(3)
    upickle.default.read[DelegatedAnswers](grouped(InterruptId("p"))) shouldBe
      DelegatedAnswers(Map(InterruptId("c") -> ujson.Num(1), InterruptId("d~g") -> ujson.Num(2)))
    DelegatedAnswers.group(Map(InterruptId("p~c") -> ujson.Null, InterruptId("p") -> ujson.Null)).isLeft shouldBe true
  }

  "a DelegatedQuestion" should "round-trip through its codec with every kind of child interrupt" in {
    val ref  = ChildRef(ThreadId("c"), RunId("r"), AgentId.unsafe("a"), Some(ToolCallId("call")), 1)
    val call = ToolCall("k", "probe", ujson.Obj())
    val held = BreakpointRequest(NodeId("n"), BreakpointPhase.Before, Some(call))
    val question = DelegatedQuestion(
      ref,
      Vector(
        ChildInterrupt.Approval(
          InterruptId("1"),
          ApprovalRequest("m", call, "why", ApprovalSource.Tool, IdempotencyKey("key"))
        ),
        ChildInterrupt.Breakpoint(InterruptId("2"), held),
        ChildInterrupt.Delegated(
          InterruptId("3~4"),
          DelegatedRequest(
            ref,
            ChildInterrupt.Breakpoint(InterruptId("4"), BreakpointRequest(NodeId("m"), BreakpointPhase.After))
          )
        )
      )
    )
    upickle.default.read[DelegatedQuestion](upickle.default.write(question)) shouldBe question
    DelegatedQuestion.flatten(InterruptId("p"), question).map(_._1.value) shouldBe Vector("p~1", "p~2", "p~3~4")
  }
}

object DelegatedSuspensionSpec extends EitherValues {
  import DelegationSpec.*

  /** A researcher whose `probe` calls a PermissionMiddleware asks to approve. */
  def asking(tool: AgentTool[?]): TypedAgent[Question, Findings] =
    researcher
      .withTools(ToolSet.of(tool).value)
      .withMiddleware(new PermissionMiddleware(Seq(PermissionRule.ask(Operation.Invoke, "probe"))))

  def probe(runs: AtomicInteger): AgentTool[SpecText] =
    AgentTool(SpecText.spec("probe")) { (_, _) =>
      val n = runs.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str(if n == 1 then "probed once" else s"probed $n times")))
    }

  /** The lead delegates one task; the researcher calls `probe` once, then reports what it saw. */
  def approvingClient(): RoutingClient = RoutingClient {
    case Lead(messages) if toolResults(messages).isEmpty =>
      Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
    case Lead(messages) => Right(CompletionFixture.simple(s"composed ${toolResults(messages).mkString}"))
    case Child("researcher", messages) if toolResults(messages).isEmpty =>
      Right(calling(ToolCall("k1", "probe", ujson.Obj("text" -> "x"))))
    case Child("researcher", messages) =>
      val seen = toolResults(messages).mkString.replace("\"", "")
      Right(CompletionFixture.simple(s"""{"summary":"$seen","sources":1}"""))
  }

  def suspendedOf(result: AgentResult): AgentStatus.Suspended = result.status match {
    case s: AgentStatus.Suspended => s
    case other                    => throw new AssertionError(s"expected Suspended, got $other")
  }

  final case class Unit_(ask: String) derives ReadWriter
  final case class Choice(unit: String) derives ReadWriter

  /** Asks which unit, then converts with the answer. */
  final class Convert extends AgentTool.Asking[SpecText, Unit_, Choice](SpecText.spec("convert")) {
    def execute(args: SpecText, context: ToolContext): ToolOutcome = ask(Unit_("which unit?"))
    def resume(args: SpecText, question: Unit_, answer: Choice, context: ToolContext): ToolOutcome =
      ToolOutcome.Success(ToolContent.Json(ujson.Str(s"${args.text} ${answer.unit}")))
  }

  /** Fails the parent's task call once it has resumed its child: the parent stops before the call commits. */
  final class CrashOnResume extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("crash-on-resume")
    private val seen     = new AtomicInteger(0)
    private val crashed  = new AtomicBoolean(false)

    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome = {
      val outcome = next()
      if request.toolName.value.startsWith("task") && seen.incrementAndGet() == 2 && crashed.compareAndSet(false, true)
      then ToolOutcome.Fatal(ProcessingError("crash", "the parent stopped before its call committed"))
      else outcome
    }
  }
}
