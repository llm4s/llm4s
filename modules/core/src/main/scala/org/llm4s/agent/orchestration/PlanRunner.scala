package org.llm4s.agent.orchestration

import org.llm4s.types.{ Result, AsyncResult, PlanId }
import org.slf4j.{ LoggerFactory, MDC }
import scala.concurrent.{ Future, ExecutionContext }

/**
 * Executes DAG plans with topological ordering and parallel execution.
 *
 * @param maxConcurrentNodes Maximum number of nodes that can execute in parallel (default: 10).
 *                          Used to prevent resource exhaustion in large DAGs.
 *
 * Follows LLM4S patterns:
 * - Uses AsyncResult[_] (Future[Result[_]]) for async operations
 * - Structured logging with MDC context
 * - Proper error types from OrchestrationError
 * - Result.safely for exception handling
 * - Standard Future combinators
 *
 * @note MDC context is preserved across async boundaries using MDCContext utility.
 * @note Supports cancellation via CancellationToken for long-running operations.
 * @example
 * {{{
 * val runner = PlanRunner(maxConcurrentNodes = 5)
 * val token = CancellationToken()
 * val result = runner.execute(plan, Map("input" -> data), token)
 * }}}
 */
class PlanRunner(maxConcurrentNodes: Int = 10) {
  private val logger = LoggerFactory.getLogger(getClass)

  require(maxConcurrentNodes > 0, "maxConcurrentNodes must be positive")

  /**
   * Execute a plan with the given initial inputs
   *
   * @param plan The execution plan
   * @param initialInputs Map from node ID to input value
   * @param cancellationToken Optional token for cancelling execution
   * @param ec Execution context for async operations
   * @return AsyncResult with final results from all exit points
   */
  def execute(
    plan: Plan,
    initialInputs: Map[String, Any],
    cancellationToken: CancellationToken = CancellationToken.none
  )(implicit ec: ExecutionContext): AsyncResult[Map[String, Any]] = {
    val planId = PlanId.generate()

    // Use safe MDC context management
    MDCContext.withValues(
      "planId"    -> planId.value,
      "component" -> "plan-runner"
    ) {
      logger.info("Starting plan execution with {} nodes and {} edges", plan.nodes.size, plan.edges.size)
    }

    // Execute with preserved context
    val contextEc = MDCContext.preservingExecutionContext(ec)
    val resultFuture = MDCContext.withValues(
      "planId"    -> planId.value,
      "component" -> "plan-runner"
    ) {
      executeWithContext(plan, initialInputs, planId, cancellationToken)(contextEc)
    }

    // Ensure cleanup happens regardless of success/failure
    resultFuture.andThen { case _ =>
      logger.info("Plan execution completed")
    // MDC cleanup is automatic with withValues
    }(contextEc)
  }

  private def executeWithContext(
    plan: Plan,
    initialInputs: Map[String, Any],
    planId: PlanId,
    cancellationToken: CancellationToken
  )(implicit ec: ExecutionContext): AsyncResult[Map[String, Any]] =
    // Validate plan structure
    plan.validate match {
      case Left(error) =>
        logger.error("Plan validation failed: {}", error)
        Future.successful(Left(OrchestrationError.PlanValidationError(error, planId.value)))
      case Right(_) =>
        logger.debug("Plan validation successful")

        // Get execution batches for parallel processing
        plan.getParallelBatches match {
          case Left(error) =>
            logger.error("Failed to create execution batches: {}", error)
            Future.successful(
              Left(OrchestrationError.PlanValidationError(s"Batch creation failed: $error", planId.value))
            )
          case Right(batches) =>
            logger.debug("Created {} execution batches", batches.size)

            // Execute batches sequentially, nodes within batches in parallel
            executeBatches(batches, initialInputs, plan, planId, cancellationToken)
              .map {
                case Right(results) =>
                  logger.info("Plan execution completed successfully with {} results", results.size)
                  Right(results)
                case Left(error) =>
                  logger.error("Plan execution failed: {}", error.toString)
                  Left(error)
              }
              .recover { error =>
                logger.error("Plan execution failed with unexpected error", error)
                Left(
                  OrchestrationError.PlanExecutionError.withCause(
                    s"Unexpected execution failure: ${error.getMessage}",
                    error,
                    planId.value
                  )
                )
              }
        }
    }

  private def executeBatches(
    batches: List[List[Node[_, _]]],
    initialInputs: Map[String, Any],
    plan: Plan,
    planId: PlanId,
    cancellationToken: CancellationToken
  )(implicit ec: ExecutionContext): AsyncResult[Map[String, Any]] = {

    def executeBatch(
      batch: List[Node[_, _]],
      currentResults: Map[String, Any],
      batchIndex: Int
    ): Future[Result[Map[String, Any]]] = {
      // Check for cancellation before starting batch
      if (cancellationToken.isCancelled) {
        return Future.successful(
          Left(
            OrchestrationError.PlanExecutionError(s"Execution cancelled at batch $batchIndex", planId.value)
          )
        )
      }

      logger.debug("Executing batch {} with {} nodes: {}", batchIndex, batch.size, batch.map(_.id).mkString(", "))

      // Execute nodes with concurrency control to prevent resource exhaustion
      def executeLimitedConcurrency(nodes: List[Node[_, _]]): Future[List[Result[(String, Any)]]] =
        if (nodes.size <= maxConcurrentNodes) {
          // Small batch - execute all in parallel
          Future.sequence(nodes.map { node =>
            executeNode(node, currentResults, plan, planId, cancellationToken).map(result =>
              result.map(r => node.id -> r)
            )
          })
        } else {
          // Large batch - execute in limited chunks sequentially
          logger.debug("Batch size {} exceeds limit {}, limiting concurrency", nodes.size, maxConcurrentNodes)

          def executeChunks(
            remaining: List[Node[_, _]],
            acc: List[Result[(String, Any)]]
          ): Future[List[Result[(String, Any)]]] =
            if (remaining.isEmpty) {
              Future.successful(acc)
            } else {
              val (chunk, rest) = remaining.splitAt(maxConcurrentNodes)
              Future
                .sequence(chunk.map { node =>
                  executeNode(node, currentResults, plan, planId, cancellationToken).map(result =>
                    result.map(r => node.id -> r)
                  )
                })
                .flatMap(results => executeChunks(rest, acc ++ results))
            }

          executeChunks(nodes, List.empty)
        }

      // Execute with limited concurrency
      executeLimitedConcurrency(batch)
        .map { results =>
          // Collect successes and failures
          val successes = results.collect { case Right((id, result)) => id -> result }
          val failures  = results.collect { case Left(error) => error }

          if (failures.nonEmpty) {
            logger.error("Batch {} had {} failures", batchIndex, failures.size)
            Left(failures.head) // Return first failure
          } else {
            val newResults = currentResults ++ successes.toMap
            logger.debug("Batch {} completed, total results: {}", batchIndex, newResults.size)
            Right(newResults)
          }
        }
        .recover { error =>
          logger.error("Batch {} failed with exception", batchIndex, error)
          Left(
            OrchestrationError.PlanExecutionError(
              s"Batch $batchIndex execution failed: ${error.getMessage}",
              planId.value
            )
          )
        }
    }

    // Execute batches sequentially, accumulating results
    def executeBatchesSequentially(
      remainingBatches: List[(List[Node[_, _]], Int)],
      currentResults: Map[String, Any]
    ): Future[Result[Map[String, Any]]] =
      remainingBatches match {
        case Nil => Future.successful(Right(currentResults))
        case (batch, index) :: tail =>
          executeBatch(batch, currentResults, index + 1).flatMap {
            case Right(newResults) => executeBatchesSequentially(tail, newResults)
            case Left(error)       => Future.successful(Left(error))
          }
      }

    executeBatchesSequentially(batches.zipWithIndex, initialInputs)
  }

  /**
   * Resolve input for a node based on DAG dependencies
   */
  private def resolveNodeInput(node: Node[_, _], availableInputs: Map[String, Any], plan: Plan): Option[Any] = {
    // Find incoming edges to this node
    val incomingEdges = plan.edges.filter(_.target.id == node.id)

    if (incomingEdges.isEmpty) {
      // This is an entry node - look for initial input by node ID
      availableInputs.get(node.id)
    } else {
      // This node depends on other nodes - find the input from dependencies
      // Iterate through all incoming edges to find the first one with a successful output
      // This allows downstream nodes to run when at least one dependency succeeds
      incomingEdges
        .map(edge => availableInputs.get(edge.source.id))
        .collectFirst { case Some(value) => value }
    }
  }

  private def executeNode(
    node: Node[_, _],
    availableInputs: Map[String, Any],
    plan: Plan,
    @annotation.unused planId: PlanId,
    cancellationToken: CancellationToken
  )(implicit ec: ExecutionContext): AsyncResult[Any] = {
    // Set up node-specific MDC context
    MDC.put("nodeId", node.id)
    MDC.put("nodeName", node.agent.name)
    MDC.put("agentId", node.agent.id.value)

    logger.debug("Starting node execution")

    // Determine input for this node
    val nodeInput = resolveNodeInput(node, availableInputs, plan)

    val resultFuture = nodeInput match {
      case Some(input) =>
        logger.debug("Node input available, type: {}", input.getClass.getSimpleName)

        // Use Try to safely handle potential ClassCastException from type mismatch
        import scala.util.Try

        Try {
          // This cast is necessary due to type erasure in the DAG structure
          // We validate types at DAG construction time, but runtime verification is limited
          val agent = node.agent.asInstanceOf[Agent[Any, Any]]

          // Race agent execution against cancellation
          Future.firstCompletedOf(
            List(
              agent.execute(input),
              cancellationToken.cachedCancellationFuture.map(_ =>
                Left(
                  OrchestrationError.PlanExecutionError(s"Node ${node.id} cancelled", planId.value)
                )
              )
            )
          )
        }.fold(
          throwable => {
            logger.error("Node execution failed with type error", throwable)
            Future.successful(
              Left(
                OrchestrationError.TypeMismatchError(
                  "unknown", // Source node would need to be tracked
                  node.id,
                  "Expected",
                  input.getClass.getSimpleName
                )
              )
            )
          },
          futureResult =>
            futureResult.recover { error =>
              logger.error("Node execution failed with unexpected error", error)
              Left(
                OrchestrationError.NodeExecutionError(
                  node.id,
                  node.agent.name,
                  s"Unexpected execution error: ${error.getMessage}",
                  error
                )
              )
            }
        )
      case None =>
        logger.error("No input available for node")
        Future.successful(
          Left(
            OrchestrationError.NodeExecutionError(
              node.id,
              node.agent.name,
              "No input available for node execution"
            )
          )
        )
    }

    // Ensure cleanup happens regardless of success/failure
    resultFuture.andThen { case _ =>
      MDC.remove("nodeId")
      MDC.remove("nodeName")
      MDC.remove("agentId")
    }
  }
}

object PlanRunner {
  def apply(): PlanRunner = new PlanRunner()
}
