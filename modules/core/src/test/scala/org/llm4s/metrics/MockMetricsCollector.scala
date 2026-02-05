package org.llm4s.metrics

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

/**
 * Mock implementation of MetricsCollector for testing.
 * Tracks all calls for verification in tests.
 */
class MockMetricsCollector extends MetricsCollector {
  // Track all calls to metrics methods
  val requestCalls: mutable.Buffer[(String, String, Outcome, FiniteDuration)] = mutable.Buffer.empty
  val tokenCalls: mutable.Buffer[(String, String, Long, Long)]                = mutable.Buffer.empty
  val costCalls: mutable.Buffer[(String, String, Double)]                     = mutable.Buffer.empty

  override def observeRequest(
    provider: String,
    model: String,
    outcome: Outcome,
    duration: FiniteDuration
  ): Unit =
    requestCalls += ((provider, model, outcome, duration))

  override def addTokens(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long
  ): Unit =
    tokenCalls += ((provider, model, inputTokens, outputTokens))

  override def recordCost(
    provider: String,
    model: String,
    costUsd: Double
  ): Unit =
    costCalls += ((provider, model, costUsd))

  // Helper methods for test assertions
  def clearAll(): Unit = {
    requestCalls.clear()
    tokenCalls.clear()
    costCalls.clear()
  }

  def hasSuccessRequest(provider: String, model: String): Boolean =
    requestCalls.exists {
      case (p, m, Outcome.Success, _) => p == provider && m == model
      case _                          => false
    }

  def hasErrorRequest(provider: String, errorKind: ErrorKind): Boolean =
    requestCalls.exists {
      case (p, _, Outcome.Error(kind), _) => p == provider && kind == errorKind
      case _                              => false
    }

  def totalRequests: Int   = requestCalls.size
  def totalTokenCalls: Int = tokenCalls.size
  def totalCostCalls: Int  = costCalls.size
}
