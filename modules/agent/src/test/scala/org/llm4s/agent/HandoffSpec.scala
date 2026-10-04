package org.llm4s.agent

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for Handoff functionality
 */
class HandoffSpec extends AnyFlatSpec with Matchers {

  // Mock client for testing
  private val mockClient: LLMClient = null // Will be mocked in actual tests

  private def countingClient(calls: AtomicInteger): LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.incrementAndGet()
      Right(Completion("t", 0L, "ok", "test", AssistantMessage("ok")))
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "Handoff" should "create with target agent" in {
    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff("physics", targetAgent)

    handoff.id shouldBe "physics"
    handoff.targetAgent shouldBe targetAgent
    handoff.preserveContext shouldBe true
    handoff.transferSystemMessage shouldBe false
    handoff.transferReason shouldBe None
  }

  it should "derive a stable handoffId from the explicit id" in {
    Handoff.to("physics", new Agent(mockClient)).handoffId shouldBe "handoff_to_physics"
  }

  it should "give equal handoffIds to the same id on different agent instances" in {
    val h1 = Handoff.to("math", new Agent(mockClient))
    val h2 = Handoff.to("math", new Agent(mockClient))
    h1.handoffId shouldBe h2.handoffId
  }

  it should "generate human-readable name with reason" in {
    val handoff = Handoff.to("math", new Agent(mockClient), "Math expertise")
    handoff.handoffName shouldBe "Handoff: Math expertise"
  }

  it should "generate human-readable name without reason" in {
    Handoff.to("math", new Agent(mockClient)).handoffName shouldBe "Handoff to math"
  }

  "Handoff.of" should "refuse invalid ids and accept valid ones" in {
    val agent = new Agent(mockClient)
    Handoff.of("", agent).left.map(_.getClass.getSimpleName) shouldBe Left("ValidationError")
    Handoff.of("has space", agent) shouldBe a[Left[_, _]]
    Handoff.of("x" * 53, agent) shouldBe a[Left[_, _]]
    Handoff.of("x" * 52, agent) shouldBe a[Right[_, _]]
    Handoff.of("a_b-C9", agent, Some("why")).map(_.transferReason) shouldBe Right(Some("why"))
  }

  "Handoff companion object" should "create with to(id, agent)" in {
    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff.to("math", targetAgent)

    handoff.targetAgent shouldBe targetAgent
    handoff.transferReason shouldBe None
  }

  it should "create with a reason using to(id, agent, reason)" in {
    val handoff = Handoff.to("math", new Agent(mockClient), "Specialist needed")
    handoff.transferReason shouldBe Some("Specialist needed")
  }

  it should "throw IllegalArgumentException for an invalid id" in {
    an[IllegalArgumentException] should be thrownBy Handoff.to("has space", new Agent(mockClient))
    an[IllegalArgumentException] should be thrownBy Handoff.to("", new Agent(mockClient), "r")
  }

  "Agent" should "fail with ValidationError, before any model call, when two handoffs share an id" in {
    val calls = new AtomicInteger(0)
    val agent = new Agent(countingClient(calls))
    val other = new Agent(countingClient(calls))
    val result = agent.run(
      "hi",
      ToolRegistry.empty,
      handoffs = Seq(Handoff.to("dup", other), Handoff.to("dup", other))
    )
    result.left.map(_.isInstanceOf[ValidationError]) shouldBe Left(true)
    calls.get() shouldBe 0
  }

  "HandoffRequested status" should "contain handoff and reason" in {
    val targetAgent   = new Agent(mockClient)
    val handoff       = Handoff.to("test", targetAgent, "Test reason")
    val handoffReason = "Complex query requires specialist"
    val status        = AgentStatus.HandoffRequested(handoff, Some(handoffReason))

    // Verify status type and contents
    status shouldBe a[AgentStatus.HandoffRequested]
    val requested = status.asInstanceOf[AgentStatus.HandoffRequested]
    requested.handoff shouldBe handoff
    requested.handoffReason shouldBe Some(handoffReason)
  }

  it should "serialize without target agent reference" in {
    val targetAgent         = new Agent(mockClient)
    val handoff             = Handoff.to("test", targetAgent, "Test handoff")
    val status: AgentStatus = AgentStatus.HandoffRequested(handoff, Some("Complex query"))

    import upickle.default._
    // Explicitly use AgentStatus type to find the implicit serializer
    val json = write[AgentStatus](status)

    json should include("HandoffRequested")
    json should include("Complex query")
    json should include(handoff.handoffId)
  }
}
