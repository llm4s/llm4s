package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.LLMClient
import upickle.default._

/**
 * Tests for AgentStatus sealed trait and its serialization.
 */
class AgentStatusSpec extends AnyFlatSpec with Matchers {

  // Mock client for creating Agent instances
  private val mockClient: LLMClient = null

  // ==========================================================================
  // AgentStatus Variants Tests
  // ==========================================================================

  "AgentStatus.InProgress" should "be a singleton" in {
    AgentStatus.InProgress shouldBe AgentStatus.InProgress
  }

  "AgentStatus.WaitingForTools" should "be a singleton" in {
    AgentStatus.WaitingForTools shouldBe AgentStatus.WaitingForTools
  }

  "AgentStatus.Complete" should "be a singleton" in {
    AgentStatus.Complete shouldBe AgentStatus.Complete
  }

  "AgentStatus.Failed" should "store error message" in {
    val status = AgentStatus.Failed("Something went wrong")

    status.error shouldBe "Something went wrong"
  }

  it should "support equality based on error message" in {
    val s1 = AgentStatus.Failed("Error A")
    val s2 = AgentStatus.Failed("Error A")
    val s3 = AgentStatus.Failed("Error B")

    s1 shouldBe s2
    s1 should not be s3
  }

  "AgentStatus.HandoffRequested" should "store handoff and reason" in {
    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff(targetAgent, Some("Specialist needed"))
    val status      = AgentStatus.HandoffRequested(handoff, Some("Complex query"))

    status.handoff shouldBe handoff
    status.handoffReason shouldBe Some("Complex query")
  }

  it should "allow None for handoff reason" in {
    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff(targetAgent)
    val status      = AgentStatus.HandoffRequested(handoff, None)

    status.handoffReason shouldBe None
  }

  // ==========================================================================
  // Serialization Tests - Simple Statuses
  // ==========================================================================

  "AgentStatus serialization" should "serialize InProgress to string" in {
    val json = write[AgentStatus](AgentStatus.InProgress)

    json shouldBe "\"InProgress\""
  }

  it should "serialize WaitingForTools to string" in {
    val json = write[AgentStatus](AgentStatus.WaitingForTools)

    json shouldBe "\"WaitingForTools\""
  }

  it should "serialize Complete to string" in {
    val json = write[AgentStatus](AgentStatus.Complete)

    json shouldBe "\"Complete\""
  }

  it should "serialize Failed to object with error" in {
    val json   = write[AgentStatus](AgentStatus.Failed("Error message"))
    val parsed = ujson.read(json)

    parsed("type").str shouldBe "Failed"
    parsed("error").str shouldBe "Error message"
  }

  // ==========================================================================
  // Deserialization Tests - Simple Statuses
  // ==========================================================================

  "AgentStatus deserialization" should "deserialize InProgress" in {
    val status = read[AgentStatus]("\"InProgress\"")

    status shouldBe AgentStatus.InProgress
  }

  it should "deserialize WaitingForTools" in {
    val status = read[AgentStatus]("\"WaitingForTools\"")

    status shouldBe AgentStatus.WaitingForTools
  }

  it should "deserialize Complete" in {
    val status = read[AgentStatus]("\"Complete\"")

    status shouldBe AgentStatus.Complete
  }

  it should "deserialize Failed with error" in {
    val json   = """{"type":"Failed","error":"Something broke"}"""
    val status = read[AgentStatus](json)

    status shouldBe a[AgentStatus.Failed]
    status.asInstanceOf[AgentStatus.Failed].error shouldBe "Something broke"
  }

  it should "handle Failed without error field" in {
    val json   = """{"type":"Failed"}"""
    val status = read[AgentStatus](json)

    status shouldBe a[AgentStatus.Failed]
    status.asInstanceOf[AgentStatus.Failed].error shouldBe "Unknown error"
  }

  // ==========================================================================
  // HandoffRequested Serialization Tests
  // ==========================================================================

  "AgentStatus.HandoffRequested serialization" should "serialize with handoff ID" in {
    val targetAgent         = new Agent(mockClient)
    val handoff             = Handoff(targetAgent, Some("Physics expertise"))
    val status: AgentStatus = AgentStatus.HandoffRequested(handoff, Some("Complex physics question"))

    val json   = write[AgentStatus](status)
    val parsed = ujson.read(json)

    parsed("type").str shouldBe "HandoffRequested"
    parsed("handoffId").str should startWith("handoff_to_agent_")
    parsed("reason").str shouldBe "Complex physics question"
  }

  it should "serialize with null reason when None" in {
    val targetAgent         = new Agent(mockClient)
    val handoff             = Handoff(targetAgent)
    val status: AgentStatus = AgentStatus.HandoffRequested(handoff, None)

    val json   = write[AgentStatus](status)
    val parsed = ujson.read(json)

    parsed("type").str shouldBe "HandoffRequested"
    parsed("reason").isNull shouldBe true
  }

  // Note: HandoffRequested cannot be fully deserialized because it contains Agent reference
  it should "return Failed status when attempting to deserialize HandoffRequested" in {
    val json   = """{"type":"HandoffRequested","handoffId":"handoff_to_agent_abc","reason":"test"}"""
    val status = read[AgentStatus](json)

    // HandoffRequested cannot be deserialized - it contains Agent reference
    status shouldBe a[AgentStatus.Failed]
  }

  // ==========================================================================
  // Edge Cases
  // ==========================================================================

  "AgentStatus deserialization" should "handle unknown status format" in {
    val json   = """{"type":"Unknown"}"""
    val status = read[AgentStatus](json)

    status shouldBe a[AgentStatus.Failed]
    status.asInstanceOf[AgentStatus.Failed].error should include("Unknown")
  }

  it should "handle invalid JSON structure" in {
    val json   = "123"
    val status = read[AgentStatus](json)

    status shouldBe a[AgentStatus.Failed]
  }

  // ==========================================================================
  // Round-trip Tests
  // ==========================================================================

  "AgentStatus" should "round-trip InProgress" in {
    val original = AgentStatus.InProgress
    val json     = write[AgentStatus](original)
    val restored = read[AgentStatus](json)

    restored shouldBe original
  }

  it should "round-trip WaitingForTools" in {
    val original = AgentStatus.WaitingForTools
    val json     = write[AgentStatus](original)
    val restored = read[AgentStatus](json)

    restored shouldBe original
  }

  it should "round-trip Complete" in {
    val original = AgentStatus.Complete
    val json     = write[AgentStatus](original)
    val restored = read[AgentStatus](json)

    restored shouldBe original
  }

  it should "round-trip Failed" in {
    val original = AgentStatus.Failed("Test error")
    val json     = write[AgentStatus](original)
    val restored = read[AgentStatus](json)

    restored shouldBe original
  }

  // ==========================================================================
  // Pattern Matching Tests
  // ==========================================================================

  "AgentStatus" should "support exhaustive pattern matching" in {
    def describe(status: AgentStatus): String = status match {
      case AgentStatus.InProgress             => "in-progress"
      case AgentStatus.WaitingForTools        => "waiting"
      case AgentStatus.Complete               => "complete"
      case AgentStatus.Failed(err)            => s"failed: $err"
      case AgentStatus.HandoffRequested(h, _) => s"handoff: ${h.handoffId}"
    }

    describe(AgentStatus.InProgress) shouldBe "in-progress"
    describe(AgentStatus.WaitingForTools) shouldBe "waiting"
    describe(AgentStatus.Complete) shouldBe "complete"
    describe(AgentStatus.Failed("test")) shouldBe "failed: test"

    val targetAgent = new Agent(mockClient)
    val handoff     = Handoff(targetAgent)
    describe(AgentStatus.HandoffRequested(handoff, None)) should startWith("handoff: handoff_to_agent_")
  }
}
