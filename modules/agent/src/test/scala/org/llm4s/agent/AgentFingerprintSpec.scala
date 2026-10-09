package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, ThreadId }
import org.llm4s.agent.graph.middleware.{ ApprovalMiddleware, MiddlewareId }
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The graph version is the structural fingerprint of the agent family: what changes it, and what a changed one refuses. */
class AgentFingerprintSpec extends AnyFlatSpec with Matchers {

  private val client = ScriptedLLMClient.of()

  private val echo  = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
  private val other = SpecTools.tool("other")((a, _) => ToolOutcome.Success(ujson.Str(a.text)))

  private def base: AgentBuilder =
    Agent
      .builder("root", client)
      .withSystemPrompt("prompt")
      .withTools(SpecTools.set(echo))
      .withMiddleware(new ApprovalMiddleware(_ => None))
      .withHandoffs(Handoff.to("helper", Agent.builder("helper", client)))

  private def version(builder: AgentBuilder): String = built(builder).loop.graph.version

  "The graph version" should "be the same for the same builders across two builds" in {
    version(base) shouldBe version(base)
  }

  it should "change with the system prompt" in {
    version(base.withSystemPrompt("another")) should not be version(base)
  }

  it should "change with a tool" in {
    val changed = Agent
      .builder("root", client)
      .withSystemPrompt("prompt")
      .withTools(SpecTools.set(echo, other))
      .withMiddleware(new ApprovalMiddleware(_ => None))
      .withHandoffs(Handoff.to("helper", Agent.builder("helper", client)))
    version(changed) should not be version(base)
  }

  it should "change with a middleware id" in {
    val changed = Agent
      .builder("root", client)
      .withSystemPrompt("prompt")
      .withTools(SpecTools.set(echo))
      .withMiddleware(new ApprovalMiddleware(_ => None, MiddlewareId("review")))
      .withHandoffs(Handoff.to("helper", Agent.builder("helper", client)))
    version(changed) should not be version(base)
  }

  /** A middleware contributing one tool, `lookup`, whose description is `description`. */
  final private class ToolMiddleware(description: String) extends org.llm4s.agent.graph.middleware.AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("lookup-provider")
    private val lookup = org.llm4s.agent.graph.tool.AgentTool(
      org.llm4s.agent.graph.tool.AgentToolSpec[SpecTools.Text](
        "lookup",
        description,
        org.llm4s.toolapi.Schema
          .`object`[SpecTools.Text]("lookup")
          .withRequiredField("text", org.llm4s.toolapi.Schema.string("Text"))
      )
    )((a, _) => ToolOutcome.Success(ujson.Str(a.text)))
    override def tools: Vector[org.llm4s.agent.graph.tool.AgentTool[?]] = Vector(lookup)
  }

  it should "change with a middleware-contributed tool's schema, the middleware id unchanged" in {
    def withLookup(description: String) =
      Agent.builder("root", client).withMiddleware(new ToolMiddleware(description))
    version(withLookup("Looks things up")) shouldBe version(withLookup("Looks things up"))
    version(withLookup("Looks other things up")) should not be version(withLookup("Looks things up"))
  }

  it should "change with a handoff" in {
    val changed = Agent
      .builder("root", client)
      .withSystemPrompt("prompt")
      .withTools(SpecTools.set(echo))
      .withMiddleware(new ApprovalMiddleware(_ => None))
    version(changed) should not be version(base)
  }

  "A thread that handed off" should "be refused by the root rebuilt without that handoff, not mis-routed" in {
    val runtime = GraphRuntime.inMemory()
    val thread  = ThreadId("handed-off")
    val rootClient = ScriptedLLMClient.of(
      CompletionFixture.withMessage(
        AssistantMessage(None, Seq(ToolCall("h1", "handoff_to_helper", ujson.Obj("reason" -> "needs help"))))
      )
    )
    val helperClient = ScriptedLLMClient.of(CompletionFixture.simple("helped"), CompletionFixture.simple("never"))
    val withHandoff = built(
      Agent
        .builder("root", rootClient)
        .withHandoffs(Handoff.to("helper", Agent.builder("helper", helperClient)))
        .withRuntime(runtime)
    )
    withHandoff.run(thread, "q1").value.activeAgent.value shouldBe "helper"

    val without = built(Agent.builder("root", rootClient).withRuntime(runtime))
    val refused = without.run(thread, "q2")

    refused.error shouldBe a[GraphError.RestoreRejected]
    helperClient.callCount shouldBe 1
    rootClient.callCount shouldBe 1
  }
}
