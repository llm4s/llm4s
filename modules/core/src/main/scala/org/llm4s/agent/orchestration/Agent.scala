package org.llm4s.agent.orchestration

import org.llm4s.types.{ Result, AsyncResult, AgentId }
import org.llm4s.{ Result => ResultObject }
import org.slf4j.LoggerFactory
import scala.concurrent.{ Future, ExecutionContext }

/**
 * Typed agent abstraction for multi-agent orchestration.
 *
 * An Agent represents a computation that takes input of type I and produces output of type O.
 * Agents are composable and can be wired together in DAGs with compile-time type safety.
 *
 * Follows LLM4S patterns:
 * - Uses AsyncResult[_] (Future[Result[_]]) for async operations
 * - Uses Result[_] for error handling
 * - Structured logging with context
 * - Proper error types from OrchestrationError
 * - Safe execution with Result.safely
 *
 * @tparam I Input type
 * @tparam O Output type
 */
trait Agent[I, O] {

  /**
   * Execute the agent with the given input
   * @param input The input to process
   * @param ec Execution context for async operations
   * @return AsyncResult (Future[Result[O]]) with either an error or the output
   */
  def execute(input: I)(implicit ec: ExecutionContext): AsyncResult[O]

  /**
   * Agent identifier for debugging and tracing
   */
  def id: AgentId

  /**
   * Human-readable agent name
   */
  def name: String

  /**
   * Optional description of what this agent does
   */
  def description: Option[String] = None
}

object Agent {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Create a simple functional agent from a function using Result.safely
   */
  def fromFunction[I, O](agentName: String)(f: I => Result[O]): Agent[I, O] =
    new Agent[I, O] {
      val id: AgentId  = AgentId.generate()
      val name: String = agentName

      def execute(input: I)(implicit ec: ExecutionContext): AsyncResult[O] = {
        logger.debug("Executing functional agent: {} [{}]", agentName, id.value)
        Future {
          ResultObject.safely(f(input)).flatten
        }
      }
    }

  /**
   * Create a functional agent from an unsafe function (auto-wrapped with Result.safely)
   */
  def fromUnsafeFunction[I, O](agentName: String)(f: I => O): Agent[I, O] =
    fromFunction(agentName)(input => ResultObject.safely(f(input)))

  /**
   * Create an effectful agent from a Future-returning function
   */
  def fromFuture[I, O](agentName: String)(f: I => Future[Result[O]]): Agent[I, O] =
    new Agent[I, O] {
      val id: AgentId  = AgentId.generate()
      val name: String = agentName

      def execute(input: I)(implicit ec: ExecutionContext): AsyncResult[O] = {
        logger.debug("Executing async agent: {} [{}]", agentName, id.value)
        f(input).recover { error =>
          logger.warn("Async agent {} [{}] failed with exception", agentName, id.value, error)
          Left(
            OrchestrationError.NodeExecutionError(
              id.value,
              agentName,
              s"Async operation failed: ${error.getMessage}",
              error
            )
          )
        }
      }
    }

  /**
   * Create an agent from an unsafe Future operation (auto-wrapped with error handling)
   */
  def fromUnsafeFuture[I, O](agentName: String)(f: I => Future[O]): Agent[I, O] =
    fromFuture(agentName) { input =>
      f(input)
        .map(Right(_))(scala.concurrent.ExecutionContext.global)
        .recover { error =>
          Left(
            OrchestrationError.NodeExecutionError(
              AgentId.generate().value,
              agentName,
              s"Unsafe async operation failed: ${error.getMessage}",
              error
            )
          )
        }(scala.concurrent.ExecutionContext.global)
    }

  /**
   * Create an agent that always succeeds with a constant value
   */
  def constant[I, O](agentName: String, value: O): Agent[I, O] =
    fromFunction(agentName)(_ => Right(value))

  /**
   * Create an agent that always fails with a specific error
   */
  def failure[I, O](agentName: String, error: OrchestrationError): Agent[I, O] =
    fromFunction(agentName)(_ => Left(error))

  /**
   * Create an agent that always fails with a simple error message
   */
  def simpleFailure[I, O](agentName: String, errorMessage: String): Agent[I, O] = {
    val agentId = AgentId.generate()
    failure(
      agentName,
      OrchestrationError.NodeExecutionError.nonRecoverable(
        agentId.value,
        agentName,
        errorMessage
      )
    )
  }
}
