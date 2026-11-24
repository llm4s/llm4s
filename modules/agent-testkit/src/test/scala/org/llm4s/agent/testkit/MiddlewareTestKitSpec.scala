package org.llm4s.agent.testkit

import org.llm4s.agent.graph.{ RunBudgets, RunConfig, RunId, StateKey, StateUpdate, TenantId, ThreadId }
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  BoundaryResult,
  MiddlewareId,
  MiddlewareRef,
  ModelContext,
  Placement,
  PromptSection,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ AgentToolSpec, ToolContent, ToolContext, ToolOutcome }
import org.llm4s.llmconnect.model.{ Conversation, ToolCall, UserMessage }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/** `MiddlewareTestKit` (#2795): a middleware's hooks called directly, with no agent, runtime or model. */
class MiddlewareTestKitSpec extends AnyFlatSpec with Matchers with EitherValues {
  import MiddlewareTestKitSpec.*

  "MiddlewareTestKit.modelContext" should "give a boundary hook the state the test chose" in {
    val state   = MiddlewareTestKit.state(Set(Articles), StateUpdate.update(Articles, "returns policy")).value
    val context = MiddlewareTestKit.modelContext(state)

    new KnowledgeBase(_ => "unused").promptSections(context).value shouldBe
      Vector(PromptSection("kb-articles", "Relevant articles:\nreturns policy"))
  }

  it should "read a registered key that was never written as its initial value" in {
    val state = MiddlewareTestKit.state(Set(Articles)).value
    new KnowledgeBase(_ => "unused").promptSections(MiddlewareTestKit.modelContext(state)).value shouldBe empty
  }

  it should "carry the run identity and budgets the test gave it" in {
    val config = RunConfig()
      .withRunId(RunId("run-7"))
      .withTenantId(TenantId("acme"))
      .withBudgets(RunBudgets().withMaxTokens(500L))
    val context = MiddlewareTestKit.modelContext(config = config, threadId = ThreadId("thread-7"))

    context.run.config.tenantId shouldBe Some(TenantId("acme"))
    context.run.config.budgets.maxTokens shouldBe Some(500L)
    context.run.position.threadId shouldBe ThreadId("thread-7")
    context.run.position.runId shouldBe RunId("run-7")
  }

  it should "let a hook's update be applied for the next hook, as the runtime commits it" in {
    val middleware = new KnowledgeBase(query => s"articles about $query")
    val state      = MiddlewareTestKit.state(middleware.writes).value

    val before = middleware.beforeAgent("refunds", MiddlewareTestKit.modelContext(state)).value
    before.text shouldBe "refunds"
    val next = MiddlewareTestKit.updated(state, before.update).value

    middleware.promptSections(MiddlewareTestKit.modelContext(next)).value.map(_.text) shouldBe
      Vector("Relevant articles:\narticles about refunds")
  }

  it should "refuse an update to a key the state does not register" in {
    MiddlewareTestKit.updated(MiddlewareTestKit.emptyState, StateUpdate.update(Articles, "x")).isLeft shouldBe true
    MiddlewareTestKit.state(Set.empty, StateUpdate.update(Articles, "x")).isLeft shouldBe true
  }

  it should "count an accounted client's calls, with no agent running" in {
    val client  = ScriptedLLMClient.always(Reply.text("fine"))
    val context = MiddlewareTestKit.modelContext()
    MiddlewareTestKit.usage(context).requestCount shouldBe 0

    context.accounted(client).complete(Conversation(Seq(UserMessage("judge this")))).isRight shouldBe true

    MiddlewareTestKit.usage(context).requestCount shouldBe 1
  }

  it should "refuse an accounted call at the run's token budget" in {
    val client  = ScriptedLLMClient.always(Reply.text("fine"))
    val context = MiddlewareTestKit.modelContext(config = RunConfig().withBudgets(RunBudgets().withMaxTokens(1L)))
    val counted = context.accounted(client)

    counted
      .complete(Conversation(Seq(UserMessage("the first call is admitted under the budget"))))
      .isRight shouldBe true
    counted.complete(Conversation(Seq(UserMessage("the second is not: the first used it up")))).isLeft shouldBe true
    client.callCount shouldBe 1
  }

  "MiddlewareTestKit.toolContext" should "let a tool wrapper be called directly" in {
    val policy = new ReviewDeploys
    val call   = ToolCall("call-1", "deploy", ujson.Obj("text" -> "prod"))
    val ran    = ToolOutcome.success(ToolContent.Json(ujson.Str("deployed")))

    policy.wrapToolCall(ToolCallRequest(spec("deploy"), call), MiddlewareTestKit.toolContext(call))(() => ran) shouldBe
      ToolOutcome.NeedsApproval("'deploy' needs review")
    policy.wrapToolCall(
      ToolCallRequest(spec("deploy"), call),
      MiddlewareTestKit.toolContext(call, approved = true)
    )(() => ran) shouldBe ran
  }

  it should "give one call the same idempotency key every time, and another call another" in {
    val call  = ToolCall("call-1", "deploy", ujson.Obj("text" -> "prod"))
    val other = ToolCall("call-2", "deploy", ujson.Obj("text" -> "prod"))

    val context = MiddlewareTestKit.toolContext(call)
    context.toolCallId.value shouldBe "call-1"
    context.idempotencyKey shouldBe MiddlewareTestKit.toolContext(call).idempotencyKey
    context.idempotencyKey should not be MiddlewareTestKit.toolContext(other).idempotencyKey
    context.idempotencyKey should not be MiddlewareTestKit.toolContext(call, threadId = ThreadId("t2")).idempotencyKey
  }

  it should "carry the state and the run's configuration" in {
    val call    = ToolCall("call-1", "deploy", ujson.Obj("text" -> "prod"))
    val state   = MiddlewareTestKit.state(Set(Articles), StateUpdate.update(Articles, "a")).value
    val context = MiddlewareTestKit.toolContext(call, state, RunConfig().withTenantId(TenantId("acme")))

    context.state.get(Articles).value shouldBe "a"
    context.run.config.tenantId shouldBe Some(TenantId("acme"))
    context.approved shouldBe false
  }

  "MiddlewareTestKit.order" should "return the ids in the order the stack runs them" in {
    val inner = named("inner")
    val outer = new AgentMiddleware {
      val id: MiddlewareId                        = MiddlewareId("outer")
      override def runsBefore: Set[MiddlewareRef] = Set(MiddlewareRef(MiddlewareId("inner")))
    }
    MiddlewareTestKit.order(inner, outer).value.map(_.value) shouldBe Vector("outer", "inner")
    MiddlewareTestKit.order().value shouldBe empty
  }

  it should "return the validation error that names the constraint that failed" in {
    val needsAbsent = new AgentMiddleware {
      val id: MiddlewareId                       = MiddlewareId("needs-absent")
      override def runsAfter: Set[MiddlewareRef] = Set(MiddlewareRef(MiddlewareId("absent")))
    }
    MiddlewareTestKit.order(needsAbsent).left.value.message should include(
      "middleware 'needs-absent': runsAfter names unknown middleware 'absent'"
    )

    val picky = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("picky")
      override def placement(other: AgentMiddleware): Option[Placement] =
        Option.when(other.id == MiddlewareId("inner"))(Placement.Outside("it must see the request first"))
    }
    MiddlewareTestKit.order(named("inner"), picky).left.value.message should include(
      "middleware 'picky' must run outside 'inner' (it must see the request first)"
    )
    MiddlewareTestKit.order(picky, named("inner")).value.map(_.value) shouldBe Vector("picky", "inner")
  }
}

object MiddlewareTestKitSpec {

  final case class Text(text: String) derives ReadWriter

  val Articles: StateKey[String, String] = StateKey.replace[String]("kb-articles", "")

  def spec(name: String): AgentToolSpec[Text] =
    AgentToolSpec[Text](
      name,
      s"The $name tool",
      Schema.`object`[Text](name).withRequiredField("text", Schema.string("Text"))
    )

  def named(name: String): AgentMiddleware = new AgentMiddleware { val id: MiddlewareId = MiddlewareId(name) }

  /** The guide's state example: articles looked up once a turn, shown to every model call. */
  final class KnowledgeBase(search: String => String) extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("knowledge-base")

    override def writes: Set[StateKey[?, ?]] = Set(Articles)

    override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
      Right(BoundaryResult(input, StateUpdate.update(Articles, search(input))))

    override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
      context.state.get(Articles).map { text =>
        if (text.isEmpty) Vector.empty else Vector(PromptSection("kb-articles", s"Relevant articles:\n$text"))
      }
  }

  /** Sends every `deploy` call for review until it is approved. */
  final class ReviewDeploys extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("review-deploys")

    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      if (request.call.name == "deploy" && !context.approved) ToolOutcome.NeedsApproval("'deploy' needs review")
      else next()
  }
}
