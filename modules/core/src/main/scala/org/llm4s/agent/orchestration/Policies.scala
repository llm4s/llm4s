// scalafix:off
package org.llm4s.agent.orchestration

import org.llm4s.types.{ AsyncResult, AgentId }
import org.slf4j.{ LoggerFactory, MDC }
import scala.concurrent.{ Future, ExecutionContext, Promise }
import scala.concurrent.duration._
import scala.util.{ Success, Try }
import java.util.concurrent.{ Executors, ScheduledExecutorService, TimeUnit }

/**
 * Execution policies for agents (retry, timeout, fallback) following LLM4S patterns.
 *
 * Uses:
 * - AsyncResult[_] (Future[Result[_]]) for async operations
 * - Structured logging with MDC context
 * - ErrorRecovery patterns for intelligent retry
 * - Proper OrchestrationError types
 * - Result.safely for exception handling
 */
object Policies {
  private val logger = LoggerFactory.getLogger(getClass)

  // Shared scheduler for delays - using a small pool to avoid thread exhaustion
  private lazy val scheduler: ScheduledExecutorService = {
    val executor = Executors.newScheduledThreadPool(
      2,
      (r: Runnable) => {
        val t = new Thread(r, "orchestration-scheduler")
        t.setDaemon(true)
        t
      }
    )

    // Register shutdown hook to clean up executor
    sys.addShutdownHook {
      executor.shutdown()
      try
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          executor.shutdownNow()
        }
      catch {
        case _: InterruptedException =>
          executor.shutdownNow()
          Thread.currentThread().interrupt()
      }
    }

    executor
  }

  /**
   * Create a Future that completes after the specified delay without blocking threads
   */
  private def delayedFuture[T](delay: FiniteDuration)(value: => T): Future[T] = {
    val promise = Promise[T]()
    scheduler.schedule(
      new Runnable {
        def run(): Unit = promise.complete(Try(value))
      },
      delay.toMillis,
      TimeUnit.MILLISECONDS
    )
    promise.future
  }

  /**
   * Add retry policy to an agent using LLM4S ErrorRecovery patterns
   */
  def withRetry[I, O](
    agent: Agent[I, O],
    maxAttempts: Int,
    backoff: FiniteDuration = 1.second
  ): Agent[I, O] =
    new Agent[I, O] {
      val id: AgentId                          = AgentId.generate()
      val name: String                         = s"${agent.name}-retry"
      override val description: Option[String] = Some(s"Retry wrapper for ${agent.name} (max: $maxAttempts)")

      def execute(input: I)(implicit ec: ExecutionContext): AsyncResult[O] = {
        MDC.put("policy", "retry")
        MDC.put("maxAttempts", maxAttempts.toString)
        MDC.put("backoffMs", backoff.toMillis.toString)

        def attempt(attemptsLeft: Int, attemptNumber: Int): AsyncResult[O] = {
          logger.debug("Retry attempt {} of {} for agent {}", attemptNumber, maxAttempts, agent.name)

          agent.execute(input).flatMap {
            case success @ Right(_) =>
              if (attemptNumber > 1) {
                logger.info("Agent {} succeeded on attempt {} of {}", agent.name, attemptNumber, maxAttempts)
              }
              Future.successful(success)

            case Left(error) if attemptsLeft > 1 =>
              // Use ErrorRecovery patterns to determine if we should retry
              error match {
                case orchError: OrchestrationError.NodeExecutionError if orchError.recoverable =>
                  logger.warn(
                    "Agent {} failed on attempt {} of {}, retrying: {}",
                    agent.name,
                    attemptNumber,
                    maxAttempts,
                    orchError.formatted
                  )

                  // Exponential backoff with proper async delay
                  val delay = backoff * attemptNumber
                  delayedFuture(delay)(()).flatMap(_ => attempt(attemptsLeft - 1, attemptNumber + 1))

                case _ =>
                  logger.error(
                    "Agent {} failed with non-recoverable error on attempt {}: {}",
                    agent.name,
                    attemptNumber,
                    error.toString
                  )
                  Future.successful(Left(error))
              }

            case failure @ Left(error) =>
              logger
                .error("Agent {} exhausted all {} attempts, final error: {}", agent.name, maxAttempts, error.toString)
              Future.successful(failure)
          }
        }

        val resultFuture = attempt(maxAttempts, 1)

        // Cleanup MDC context when done
        resultFuture.andThen { case _ =>
          MDC.remove("policy")
          MDC.remove("maxAttempts")
          MDC.remove("backoffMs")
        }
      }
    }

  /**
   * Add timeout policy to an agent using proper error types
   */
  def withTimeout[I, O](
    agent: Agent[I, O],
    timeout: FiniteDuration
  ): Agent[I, O] =
    new Agent[I, O] {
      val id: AgentId                          = AgentId.generate()
      val name: String                         = s"${agent.name}-timeout"
      override val description: Option[String] = Some(s"Timeout wrapper for ${agent.name} (${timeout.toString})")

      def execute(input: I)(implicit ec: ExecutionContext): AsyncResult[O] = {
        MDC.put("policy", "timeout")
        MDC.put("timeoutMs", timeout.toMillis.toString)

        // Proper timeout using scheduled executor
        val timeoutPromise = Promise[AsyncResult[O]]()
        val timeoutTask = scheduler.schedule(
          new Runnable {
            def run(): Unit =
              timeoutPromise.trySuccess(
                Future.successful(Left(OrchestrationError.AgentTimeoutError(agent.name, timeout.toMillis)))
              )
          },
          timeout.toMillis,
          TimeUnit.MILLISECONDS
        )

        val agentFuture = agent.execute(input)

        // Cancel timeout if agent completes first
        agentFuture.onComplete { _ =>
          timeoutTask.cancel(false)
          timeoutPromise.trySuccess(agentFuture)
        }

        val resultFuture = timeoutPromise.future.flatten

        resultFuture
          .andThen {
            case Success(Left(_: OrchestrationError.AgentTimeoutError)) =>
              logger.warn("Agent {} timed out after {}", agent.name, timeout)
            case _ =>
          }
          .andThen { case _ =>
            MDC.remove("policy")
            MDC.remove("timeoutMs")
          }
      }
    }

  /**
   * Add fallback policy to an agent with proper error context
   */
  def withFallback[I, O](
    primary: Agent[I, O],
    fallback: Agent[I, O]
  ): Agent[I, O] =
    new Agent[I, O] {
      val id: AgentId                          = AgentId.generate()
      val name: String                         = s"${primary.name}-fallback"
      override val description: Option[String] = Some(s"Fallback from ${primary.name} to ${fallback.name}")

      def execute(input: I)(implicit ec: ExecutionContext): AsyncResult[O] = {
        MDC.put("policy", "fallback")
        MDC.put("primaryAgent", primary.name)
        MDC.put("fallbackAgent", fallback.name)

        val resultFuture = primary.execute(input).flatMap {
          case success @ Right(_) =>
            logger.debug("Primary agent {} succeeded, no fallback needed", primary.name)
            Future.successful(success)

          case Left(primaryError) =>
            logger.warn(
              "Primary agent {} failed, trying fallback {}: {}",
              primary.name,
              fallback.name,
              primaryError.toString
            )

            fallback.execute(input).map {
              case Right(result) =>
                logger.info("Fallback agent {} succeeded after primary {} failed", fallback.name, primary.name)
                Right(result)

              case Left(fallbackError) =>
                logger.error(
                  "Both primary {} and fallback {} agents failed. Primary: {}, Fallback: {}",
                  primary.name,
                  fallback.name,
                  primaryError.toString,
                  fallbackError.toString
                )
                // Return the original primary error as it's usually more meaningful
                Left(primaryError)
            }
        }

        resultFuture.andThen { case _ =>
          MDC.remove("policy")
          MDC.remove("primaryAgent")
          MDC.remove("fallbackAgent")
        }
      }
    }

  /**
   * Combine multiple policies with proper ordering and error handling
   */
  def withPolicies[I, O](
    agent: Agent[I, O],
    retry: Option[(Int, FiniteDuration)] = None,
    timeout: Option[FiniteDuration] = None,
    fallback: Option[Agent[I, O]] = None
  ): Agent[I, O] = {
    logger.debug(
      "Applying policies to agent {}: retry={}, timeout={}, fallback={}",
      agent.name,
      retry.isDefined,
      timeout.isDefined,
      fallback.isDefined
    )

    var enhanced = agent

    // Apply policies in order: timeout -> retry -> fallback
    // This ensures timeouts are enforced per retry attempt, and fallback is the last resort

    timeout.foreach { duration =>
      enhanced = withTimeout(enhanced, duration)
      logger.debug("Applied timeout policy ({}ms) to agent {}", duration.toMillis, agent.name)
    }

    retry.foreach { case (maxAttempts, backoff) =>
      enhanced = withRetry(enhanced, maxAttempts, backoff)
      logger.debug(
        "Applied retry policy ({} attempts, {}ms backoff) to agent {}",
        maxAttempts,
        backoff.toMillis,
        agent.name
      )
    }

    fallback.foreach { fallbackAgent =>
      enhanced = withFallback(enhanced, fallbackAgent)
      logger.debug("Applied fallback policy (fallback: {}) to agent {}", fallbackAgent.name, agent.name)
    }

    enhanced
  }
}
