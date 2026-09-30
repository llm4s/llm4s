package org.llm4s.agent.orchestration

import org.llm4s.error.LLMError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OrchestrationErrorSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // PlanValidationError
  // ==========================================================================

  "PlanValidationError" should "create with message only" in {
    val error = OrchestrationError.PlanValidationError("cycle detected")
    error.message should include("cycle detected")
    error.planId shouldBe None
    error.validationFailures shouldBe empty
    error shouldBe an[LLMError]
  }

  it should "create with message and planId" in {
    val error = OrchestrationError.PlanValidationError("bad edges", "plan-123")
    error.message should include("bad edges")
    error.planId shouldBe Some("plan-123")
    error.context should contain("planId" -> "plan-123")
  }

  it should "create with failure list" in {
    val failures = List("no source node", "cycle", "missing input")
    val error    = OrchestrationError.PlanValidationError(failures, Some("plan-456"))
    error.validationFailures shouldBe failures
    error.message should include("3 errors")
    error.context("failureCount") shouldBe "3"
  }

  it should "include component in context" in {
    val error = OrchestrationError.PlanValidationError("test")
    error.context("component") shouldBe "plan-validation"
  }

  // ==========================================================================
  // NodeExecutionError
  // ==========================================================================

  "NodeExecutionError" should "create recoverable error" in {
    val error = OrchestrationError.NodeExecutionError("node-1", "Summarizer", "timeout")
    error.nodeId shouldBe "node-1"
    error.nodeName shouldBe "Summarizer"
    error.message should include("node-1")
    error.message should include("Summarizer")
    error.recoverable shouldBe true
    error.cause shouldBe None
  }

  it should "create recoverable error with cause" in {
    val cause = new RuntimeException("connection reset")
    val error = OrchestrationError.NodeExecutionError("node-2", "Fetcher", "network error", cause)
    error.cause shouldBe Some(cause)
    error.recoverable shouldBe true
    error.context should contain("cause" -> "RuntimeException")
  }

  it should "create non-recoverable error" in {
    val error = OrchestrationError.NodeExecutionError.nonRecoverable("node-3", "Parser", "invalid schema")
    error.recoverable shouldBe false
    error.cause shouldBe None
  }

  it should "create non-recoverable error with cause" in {
    val cause = new IllegalArgumentException("bad format")
    val error = OrchestrationError.NodeExecutionError.nonRecoverable("node-4", "Validator", "schema mismatch", cause)
    error.recoverable shouldBe false
    error.cause shouldBe Some(cause)
  }

  it should "include node details in context" in {
    val error = OrchestrationError.NodeExecutionError("n1", "MyNode", "fail")
    error.context("component") shouldBe "node-execution"
    error.context("nodeId") shouldBe "n1"
    error.context("nodeName") shouldBe "MyNode"
    error.context("recoverable") shouldBe "true"
  }

  // ==========================================================================
  // PlanExecutionError
  // ==========================================================================

  "PlanExecutionError" should "create with message only" in {
    val error = OrchestrationError.PlanExecutionError("failed")
    error.message should include("failed")
    error.planId shouldBe None
    error.executedNodes shouldBe empty
    error.failedNode shouldBe None
    error.cause shouldBe None
  }

  it should "create with message and planId" in {
    val error = OrchestrationError.PlanExecutionError("node failed", "plan-1")
    error.planId shouldBe Some("plan-1")
    error.context should contain("planId" -> "plan-1")
  }

  it should "create with cause" in {
    val cause = new RuntimeException("boom")
    val error = OrchestrationError.PlanExecutionError.withCause("exploded", cause)
    error.cause shouldBe Some(cause)
  }

  it should "create with cause and planId" in {
    val cause = new RuntimeException("boom")
    val error = OrchestrationError.PlanExecutionError.withCause("exploded", cause, "plan-2")
    error.cause shouldBe Some(cause)
    error.planId shouldBe Some("plan-2")
  }

  it should "include component in context" in {
    val error = OrchestrationError.PlanExecutionError("test")
    error.context("component") shouldBe "plan-execution"
  }

  // ==========================================================================
  // TypeMismatchError
  // ==========================================================================

  "TypeMismatchError" should "capture type information" in {
    val error = OrchestrationError.TypeMismatchError("nodeA", "nodeB", "String", "Int")
    error.sourceNode shouldBe "nodeA"
    error.targetNode shouldBe "nodeB"
    error.expectedType shouldBe "String"
    error.actualType shouldBe "Int"
    error.message should include("nodeA")
    error.message should include("nodeB")
    error.message should include("String")
    error.message should include("Int")
  }

  it should "include all details in context" in {
    val error = OrchestrationError.TypeMismatchError("src", "dst", "List[Int]", "Set[String]")
    error.context("component") shouldBe "type-validation"
    error.context("sourceNode") shouldBe "src"
    error.context("targetNode") shouldBe "dst"
    error.context("expectedType") shouldBe "List[Int]"
    error.context("actualType") shouldBe "Set[String]"
  }

  // ==========================================================================
  // AgentTimeoutError
  // ==========================================================================

  "AgentTimeoutError" should "capture agent and timeout info" in {
    val error = OrchestrationError.AgentTimeoutError("slow-agent", 30000L)
    error.agentName shouldBe "slow-agent"
    error.timeoutMs shouldBe 30000L
    error.message should include("slow-agent")
    error.message should include("30000ms")
  }

  it should "include details in context" in {
    val error = OrchestrationError.AgentTimeoutError("agent-1", 5000L)
    error.context("component") shouldBe "agent-timeout"
    error.context("agentName") shouldBe "agent-1"
    error.context("timeoutMs") shouldBe "5000"
  }

  // ==========================================================================
  // All types extend OrchestrationError and LLMError
  // ==========================================================================

  "All OrchestrationError types" should "extend LLMError" in {
    val errors: Seq[OrchestrationError] = Seq(
      OrchestrationError.PlanValidationError("test"),
      OrchestrationError.NodeExecutionError("n", "N", "msg"),
      OrchestrationError.PlanExecutionError("test"),
      OrchestrationError.TypeMismatchError("a", "b", "X", "Y"),
      OrchestrationError.AgentTimeoutError("a", 100L)
    )

    errors.foreach { error =>
      error shouldBe an[LLMError]
      error shouldBe an[OrchestrationError]
      error.message should not be empty
      error.context should not be empty
    }
  }
}
