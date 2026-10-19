package org.llm4s.agent.testkit

import org.llm4s.agent.{ Agent, AgentBuilder }
import org.llm4s.agent.delegation.{ ChildFailure, TaskRequest, TypedAgent }
import org.llm4s.agent.graph.{ Checkpointer, CheckpointStatus, GraphRuntime, ThreadId }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, PermissionMiddleware, PermissionRule }
import org.llm4s.agent.graph.middleware.{ GuardrailMiddleware, ToolCallRequest }
import org.llm4s.agent.graph.tool.{
  AgentTool,
  AgentToolSpec,
  Operation,
  ToolContent,
  ToolContext,
  ToolOutcome,
  ToolSet
}
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.jdk.CollectionConverters.*

/**
 * The delegation contract (design 4.44), as a ScalaTest suite: a parent agent delegating to a subagent on a store,
 * through every row of rule 14's table - what a `task` call does, by the child thread's latest checkpoint, when the call
 * runs again (the parent's `recover`):
 *
 * | child's latest checkpoint | the call                                   |
 * |---------------------------|--------------------------------------------|
 * | none                      | starts the child                           |
 * | `Running`                 | continues the child from its checkpoint    |
 * | `Suspended`               | reports the child suspended again          |
 * | `Completed` or `Failed`   | returns the outcome there, with no run     |
 *
 * Mix it into a spec and give it a fresh store per case:
 * {{{
 * class MyStoreDelegationSpec extends AnyFlatSpec with DelegationContract:
 *   protected def newCheckpointer(): Checkpointer = MyStore.open(...)
 * }}}
 */
@Experimental
trait DelegationContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues with LoneElement:
  import DelegationContract.*

  /** A fresh, empty store for one case. */
  protected def newCheckpointer(): Checkpointer

  private def lead(client: LLMClient, store: Checkpointer): AgentBuilder =
    Agent.builder("lead", client).withSystemPrompt(LeadPrompt).withRuntime(new GraphRuntime(store))

  private def worker: TypedAgent[TaskRequest, String] =
    TypedAgent.text("worker", "Does one task").withSystemPrompt(WorkerPrompt)

  private val parent = ThreadId("delegation-contract")

  /** The lead delegates once, then answers with the tool result it read. */
  private def leadRoute: PartialFunction[Vector[Message], Result[Completion]] = {
    case messages if isLead(messages) && results(messages).isEmpty =>
      Right(calling(ToolCall("c1", "task", ujson.Obj("subagent_type" -> "worker", "description" -> "do it"))))
    case messages if isLead(messages) => Right(text(s"lead read: ${results(messages).mkString}"))
  }

  "DelegationContract: a call with no child thread" should "start the child, on a child thread of the parent" in {
    val store  = newCheckpointer()
    val client = Routing(leadRoute.orElse { case m if isWorker(m) => Right(text("report")) })
    val agent  = lead(client, store).withSubagents(worker).build().value

    agent.run(parent, "go").value.answer.value shouldBe "lead read: report"
    val child = agent.children(parent).value.loneElement
    child.status shouldBe CheckpointStatus.Completed
    child.origin.parent shouldBe parent
    child.origin.depth shouldBe 1
    client.callsTo(WorkerPrompt) shouldBe 1
  }

  "DelegationContract: a call whose child is Running" should "continue the child from its checkpoint, not start it again" in {
    val store      = newCheckpointer()
    val workerCall = new AtomicInteger(0)
    val probes     = new AtomicInteger(0)
    val client = Routing(leadRoute.orElse {
      case m if isWorker(m) =>
        workerCall.incrementAndGet() match
          case 1 => Right(calling(ToolCall("p1", "probe", ujson.Obj("text" -> "x"))))
          case 2 => Left(ValidationError("provider", "the provider is down"))
          case _ => Right(text("recovered report"))
    })
    val probe = AgentTool(textSpec("probe")) { (_, _) =>
      probes.incrementAndGet()
      ToolOutcome.Success(ToolContent.Json(ujson.Str("probed")))
    }
    val agent = lead(client, store)
      .withSubagents(worker.withTools(ToolSet.of(probe).value).withFailure(ChildFailure.Fail))
      .build()
      .value

    agent.run(parent, "go").isLeft shouldBe true
    agent.children(parent).value.loneElement.status shouldBe CheckpointStatus.Running

    agent.recover(parent).value.answer.value shouldBe "lead read: recovered report"
    probes.get shouldBe 1
    agent.children(parent).value.loneElement.status shouldBe CheckpointStatus.Completed
  }

  "DelegationContract: a call whose child Completed" should "return the child's report with no child run" in {
    val store  = newCheckpointer()
    val client = Routing(leadRoute.orElse { case m if isWorker(m) => Right(text("report")) })
    val agent  = lead(client, store).withMiddleware(new CrashOnce).withSubagents(worker).build().value

    // the child completes, then the parent's call fails before it commits
    agent.run(parent, "go").isLeft shouldBe true
    agent.children(parent).value.loneElement.status shouldBe CheckpointStatus.Completed

    agent.recover(parent).value.answer.value shouldBe "lead read: report"
    client.callsTo(WorkerPrompt) shouldBe 1
    agent.children(parent).value should have size 1
  }

  "DelegationContract: a call whose child Failed" should "return the child's refusal with no child run" in {
    val store   = newCheckpointer()
    val client  = Routing(leadRoute.orElse { case m if isWorker(m) => Right(text("a report far too long")) })
    val blocked = worker.withMiddleware(new GuardrailMiddleware(Seq.empty, Seq(new LengthCheck(0, 5))))
    val agent   = lead(client, store).withMiddleware(new CrashOnce).withSubagents(blocked).build().value

    agent.run(parent, "go").isLeft shouldBe true
    agent.children(parent).value.loneElement.status shouldBe CheckpointStatus.Failed

    val answer = agent.recover(parent).value.answer.value
    answer should include("subagent 'worker'")
    answer should include("blocked by guardrail")
    client.callsTo(WorkerPrompt) shouldBe 1
  }

  "DelegationContract: a call whose child is Suspended" should "report it suspended again, leaving it suspended, with no child run" in {
    val store = newCheckpointer()
    val client = Routing(leadRoute.orElse {
      case m if isWorker(m) =>
        Right(calling(ToolCall("p1", "probe", ujson.Obj("text" -> "x"))))
    })
    val probe = AgentTool(textSpec("probe"))((_, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str("probed"))))
    val asking = worker
      .withTools(ToolSet.of(probe).value)
      .withMiddleware(new PermissionMiddleware(Seq(PermissionRule.ask(Operation.Invoke, "probe"))))
    val agent = lead(client, store).withMiddleware(new CrashOnce).withSubagents(asking).build().value

    agent.run(parent, "go").isLeft shouldBe true
    agent.children(parent).value.loneElement.status shouldBe CheckpointStatus.Suspended

    agent.recover(parent).value.answer.value should include("suspended")
    client.callsTo(WorkerPrompt) shouldBe 1
    agent.children(parent).value.loneElement.status shouldBe CheckpointStatus.Suspended
  }

object DelegationContract:
  private val LeadPrompt   = "LEAD"
  private val WorkerPrompt = "WORKER"

  private def isLead(messages: Vector[Message]): Boolean = messages.headOption.exists(_.content.startsWith(LeadPrompt))
  private def isWorker(messages: Vector[Message]): Boolean =
    messages.headOption.exists(_.content.startsWith(WorkerPrompt))

  private def results(messages: Vector[Message]): Vector[String] = messages.collect { case t: ToolMessage => t.content }

  private def text(content: String): Completion = completion(AssistantMessage(content, Seq.empty))

  private def calling(calls: ToolCall*): Completion = completion(AssistantMessage(None, calls))

  private def completion(message: AssistantMessage): Completion =
    Completion(
      id = "delegation-contract",
      created = 0L,
      content = message.content,
      model = "contract-model",
      message = message,
      toolCalls = message.toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15))
    )

  final private case class Text(text: String) derives ReadWriter

  private def textSpec(name: String): AgentToolSpec[Text] =
    AgentToolSpec(name, s"$name tool", Schema.`object`[Text]("args").withRequiredField("text", Schema.string("t")))

  /** Fails the parent's first `task` call after it ran - a crash after the child ended, before the call committed. */
  final private class CrashOnce extends AgentMiddleware:
    val id: MiddlewareId = MiddlewareId("crash-once")
    private val crashed  = new AtomicBoolean(false)

    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      val outcome = next()
      if request.toolName.value == "task" && crashed.compareAndSet(false, true) then
        ToolOutcome.Fatal(ProcessingError("crash", "the parent stopped before its call committed"))
      else outcome

  /** Answers each call by the conversation sent, counting calls by system prompt; thread-safe. */
  final private class Routing(route: PartialFunction[Vector[Message], Result[Completion]]) extends LLMClient:
    private val recorded = new CopyOnWriteArrayList[Vector[Message]]()

    def callsTo(prompt: String): Int = recorded.asScala.count(_.headOption.exists(_.content.startsWith(prompt)))

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      val messages = conversation.messages.toVector
      recorded.add(messages)
      route.applyOrElse(messages, (_: Vector[Message]) => Left(ValidationError("routing", "no route for the call")))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 128000
    override def getReserveCompletion(): Int = 4096
