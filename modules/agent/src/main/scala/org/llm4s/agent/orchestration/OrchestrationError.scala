package org.llm4s.agent.orchestration

import org.llm4s.error.LLMError

/**
 * Orchestration-specific error types following LLM4S error patterns
 */
sealed trait OrchestrationError extends LLMError

object OrchestrationError {

  /**
   * Plan validation errors (non-recoverable)
   */
  final case class PlanValidationError private (
    override val message: String,
    planId: Option[String],
    validationFailures: List[String]
  ) extends OrchestrationError {

    override val context: Map[String, String] = Map(
      "component"    -> "plan-validation",
      "failureCount" -> validationFailures.size.toString
    ) ++ planId.map("planId" -> _)
  }

  object PlanValidationError {
    def apply(message: String): PlanValidationError =
      new PlanValidationError(s"Plan validation failed: $message", None, List.empty)

    def apply(message: String, planId: String): PlanValidationError =
      new PlanValidationError(s"Plan validation failed: $message", Some(planId), List.empty)

    def apply(failures: List[String], planId: Option[String]): PlanValidationError =
      new PlanValidationError(
        s"Plan validation failed with ${failures.size} errors: ${failures.mkString(", ")}",
        planId,
        failures
      )
  }

  /**
   * Node execution errors (potentially recoverable)
   */
  final case class NodeExecutionError private (
    override val message: String,
    nodeId: String,
    nodeName: String,
    cause: Option[Throwable],
    recoverable: Boolean
  ) extends OrchestrationError {

    override val context: Map[String, String] = Map(
      "component"   -> "node-execution",
      "nodeId"      -> nodeId,
      "nodeName"    -> nodeName,
      "recoverable" -> recoverable.toString
    ) ++ cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  object NodeExecutionError {
    def apply(nodeId: String, nodeName: String, message: String): NodeExecutionError =
      new NodeExecutionError(s"Node execution failed [$nodeId:$nodeName]: $message", nodeId, nodeName, None, true)

    def apply(nodeId: String, nodeName: String, message: String, cause: Throwable): NodeExecutionError =
      new NodeExecutionError(
        s"Node execution failed [$nodeId:$nodeName]: $message",
        nodeId,
        nodeName,
        Some(cause),
        true
      )

    def nonRecoverable(nodeId: String, nodeName: String, message: String): NodeExecutionError =
      new NodeExecutionError(s"Node execution failed [$nodeId:$nodeName]: $message", nodeId, nodeName, None, false)

    def nonRecoverable(nodeId: String, nodeName: String, message: String, cause: Throwable): NodeExecutionError =
      new NodeExecutionError(
        s"Node execution failed [$nodeId:$nodeName]: $message",
        nodeId,
        nodeName,
        Some(cause),
        false
      )
  }

  /**
   * Plan execution errors (potentially recoverable)
   */
  final case class PlanExecutionError private (
    override val message: String,
    planId: Option[String],
    executedNodes: List[String],
    failedNode: Option[String],
    cause: Option[Throwable]
  ) extends OrchestrationError {

    override val context: Map[String, String] = Map(
      "component"         -> "plan-execution",
      "executedNodeCount" -> executedNodes.size.toString
    ) ++ planId.map("planId" -> _) ++
      failedNode.map("failedNode" -> _) ++
      cause.map(ex => "cause" -> ex.getClass.getSimpleName)
  }

  object PlanExecutionError {
    def apply(message: String): PlanExecutionError =
      new PlanExecutionError(s"Plan execution failed: $message", None, List.empty, None, None)

    def apply(message: String, planId: String): PlanExecutionError =
      new PlanExecutionError(s"Plan execution failed: $message", Some(planId), List.empty, None, None)

    def withCause(message: String, cause: Throwable): PlanExecutionError =
      new PlanExecutionError(s"Plan execution failed: $message", None, List.empty, None, Some(cause))

    def withCause(message: String, cause: Throwable, planId: String): PlanExecutionError =
      new PlanExecutionError(s"Plan execution failed: $message", Some(planId), List.empty, None, Some(cause))
  }

  /**
   * Input/Output type mismatch errors (non-recoverable)
   */
  final case class TypeMismatchError private (
    override val message: String,
    sourceNode: String,
    targetNode: String,
    expectedType: String,
    actualType: String
  ) extends OrchestrationError {

    override val context: Map[String, String] = Map(
      "component"    -> "type-validation",
      "sourceNode"   -> sourceNode,
      "targetNode"   -> targetNode,
      "expectedType" -> expectedType,
      "actualType"   -> actualType
    )
  }

  object TypeMismatchError {
    def apply(sourceNode: String, targetNode: String, expectedType: String, actualType: String): TypeMismatchError =
      new TypeMismatchError(
        s"Type mismatch between nodes: $sourceNode -> $targetNode (expected: $expectedType, got: $actualType)",
        sourceNode,
        targetNode,
        expectedType,
        actualType
      )
  }

  /**
   * Agent timeout errors (recoverable)
   */
  final case class AgentTimeoutError private (
    override val message: String,
    agentName: String,
    timeoutMs: Long
  ) extends OrchestrationError {

    override val context: Map[String, String] = Map(
      "component" -> "agent-timeout",
      "agentName" -> agentName,
      "timeoutMs" -> timeoutMs.toString
    )
  }

  object AgentTimeoutError {
    def apply(agentName: String, timeoutMs: Long): AgentTimeoutError =
      new AgentTimeoutError(s"Agent '$agentName' timed out after ${timeoutMs}ms", agentName, timeoutMs)
  }
}
