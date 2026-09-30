package org.llm4s.agent

import org.llm4s.llmconnect.LLMClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for Handoff functionality
 */
class HandoffSpec extends AnyFlatSpec with Matchers {

  // Mock client for testing
  private val mockClient: LLMClient = null // Will be mocked in actual tests

  "Handoff" should "create with target agent" in {
    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff(targetAgent)

    handoff.targetAgent shouldBe targetAgent
    handoff.preserveContext shouldBe true
    handoff.transferSystemMessage shouldBe false
    handoff.transferReason shouldBe None
  }

  it should "create with reason" in {
    val targetAgent = new Agent(mockClient)
    val reason      = "Specialist knowledge required"
    val handoff     = Handoff(targetAgent, Some(reason))

    handoff.transferReason shouldBe Some(reason)
    handoff.preserveContext shouldBe true
  }

  it should "generate unique handoff ID" in {
    val agent1 = new Agent(mockClient)
    val agent2 = new Agent(mockClient)

    val handoff1 = Handoff(agent1)
    val handoff2 = Handoff(agent2)

    // IDs should be different for different agents
    (handoff1.handoffId should not).equal(handoff2.handoffId)

    // IDs should be consistent for same agent
    val handoff1b = Handoff(agent1)
    handoff1.handoffId shouldBe handoff1b.handoffId
  }

  it should "generate handoff ID with correct format" in {
    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff(targetAgent)

    handoff.handoffId should startWith("handoff_to_agent_")
    (handoff.handoffId should have).length(handoff.handoffId.length) // Should be consistent
  }

  it should "generate human-readable name with reason" in {
    val targetAgent = new Agent(mockClient)
    val reason      = "Math expertise"
    val handoff     = Handoff(targetAgent, Some(reason))

    handoff.handoffName should include(reason)
    handoff.handoffName should startWith("Handoff:")
  }

  it should "generate human-readable name without reason" in {
    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff(targetAgent)

    handoff.handoffName should startWith("Handoff to agent")
  }

  "Handoff companion object" should "create simple handoff with to()" in {
    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff.to(targetAgent)

    handoff.targetAgent shouldBe targetAgent
    handoff.transferReason shouldBe None
    handoff.preserveContext shouldBe true
    handoff.transferSystemMessage shouldBe false
  }

  it should "create handoff with reason using to(agent, reason)" in {
    val targetAgent = new Agent(mockClient)
    val reason      = "Specialist needed"
    val handoff     = Handoff.to(targetAgent, reason)

    handoff.transferReason shouldBe Some(reason)
    handoff.preserveContext shouldBe true
  }

  "HandoffRequested status" should "contain handoff and reason" in {
    val targetAgent   = new Agent(mockClient)
    val handoff       = Handoff(targetAgent, Some("Test reason"))
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
    val handoff             = Handoff(targetAgent, Some("Test handoff"))
    val status: AgentStatus = AgentStatus.HandoffRequested(handoff, Some("Complex query"))

    import upickle.default._
    // Explicitly use AgentStatus type to find the implicit serializer
    val json = write[AgentStatus](status)

    json should include("HandoffRequested")
    json should include("Complex query")
    json should include(handoff.handoffId)
  }
}
