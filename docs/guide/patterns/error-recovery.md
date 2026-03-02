---
layout: page
title: Error Recovery
parent: Real-World Application Patterns
nav_order: 5
---

# Error Recovery Patterns

> **Note:** Code examples in this guide are illustrative pseudocode showing recommended patterns. For working examples using the actual LLM4S API, see [modules/samples](../../../modules/samples/).

Resilience strategies for handling failures, recovering from errors, and gracefully degrading functionality. This guide covers retries, circuit breakers, fallbacks, and failure scenarios.

## Retry Strategies

### Exponential Backoff with Jitter

```text
object RetryStrategies {
  
  import scala.concurrent.{Future, ExecutionContext}
  import scala.concurrent.duration._
  import scala.util.{Try, Success, Failure}
  
  case class RetryConfig(
    maxAttempts: Int = 5,
    initialDelayMs: Long = 100,
    maxDelayMs: Long = 32000,
    multiplier: Double = 2.0,
    jitterFactor: Double = 0.1
  )
  
  def exponentialBackoffRetry[T](
    config: RetryConfig,
    operation: () => Result[T]
  )(implicit ec: ExecutionContext): Future[Result[T]] = {
    
    def retry(attempt: Int): Future[Result[T]] = {
      operation() match {
        case success @ Result.Success(_) => 
          Future.successful(success)
        case failure @ Result.Failure(_) if attempt >= config.maxAttempts =>
          Future.successful(failure)
        case failure @ Result.Failure(error) =>
          // Calculate delay with exponential backoff and jitter
          val exponentialDelay = 
            Math.min(
              config.maxDelayMs,
              config.initialDelayMs * Math.pow(config.multiplier, attempt - 1)
            ).toLong
          
          val jitter = (Math.random() - 0.5) * 2 * exponentialDelay * config.jitterFactor
          val totalDelay = Math.max(0, exponentialDelay + jitter.toLong)
          
          println(s"Attempt $attempt failed: $error. Retrying in ${totalDelay}ms...")
          
          // Wait and retry
          akka.pattern.after(totalDelay.millis, 
            scala.concurrent.ExecutionContext.Implicits.global
          )(retry(attempt + 1))
      }
    }
    
    retry(1)
  }
}
```

### Retry with Timeout

```text
object TimeoutRetry {
  
  def retryWithTimeout[T](
    operation: () => Result[T],
    timeoutMs: Long = 30000,
    maxRetries: Int = 3
  ): Result[T] = {
    var lastError: String = ""
    
    for (attempt <- 1 to maxRetries) {
      try {
        val future = scala.concurrent.Future {
          operation()
        }(scala.concurrent.ExecutionContext.global)
        
        val result = scala.concurrent.Await.result(
          future,
          scala.concurrent.duration.Duration(timeoutMs, "ms")
        )
        
        return result
      } catch {
        case e: scala.concurrent.TimeoutException =>
          lastError = s"Timeout after ${timeoutMs}ms"
          if (attempt < maxRetries) {
            Thread.sleep(1000L * attempt) // Increasing delay
          }
        case e: Exception =>
          lastError = e.getMessage
          if (attempt < maxRetries) {
            Thread.sleep(1000L * attempt)
          }
      }
    }
    
    Result.failure(s"Failed after $maxRetries attempts: $lastError")
  }
}
```

---

## Circuit Breaker Pattern

### Implementation

```text
object CircuitBreaker {
  
  import scala.concurrent.duration._
  
  sealed trait CircuitState
  case object Closed extends CircuitState // Operating normally
  case object Open extends CircuitState // Failing, reject requests
  case object HalfOpen extends CircuitState // Testing if recovered
  
  case class CircuitBreakerConfig(
    failureThreshold: Int = 5, // failures before opening
    successThreshold: Int = 2, // successes in half-open before closing
    timeout: Duration = 60.seconds // time before trying half-open
  )
  
  class CircuitBreakerImpl[T](config: CircuitBreakerConfig) {
    private var state: CircuitState = Closed
    private var failureCount = 0
    private var successCount = 0
    private var lastFailureTime = System.currentTimeMillis()
    
    def execute[A](operation: () => Result[A]): Result[A] = {
      state match {
        case Closed =>
          executeInClosedState(operation)
        case Open =>
          executeInOpenState(operation)
        case HalfOpen =>
          executeInHalfOpenState(operation)
      }
    }
    
    private def executeInClosedState[A](
      operation: () => Result[A]
    ): Result[A] = {
      operation() match {
        case success @ Result.Success(_) =>
          failureCount = 0
          success
        case failure @ Result.Failure(error) =>
          failureCount += 1
          if (failureCount >= config.failureThreshold) {
            state = Open
            println(s"Circuit breaker opened after $failureCount failures")
          }
          failure
      }
    }
    
    private def executeInOpenState[A](
      operation: () => Result[A]
    ): Result[A] = {
      val timeSinceFailure = System.currentTimeMillis() - lastFailureTime
      
      if (timeSinceFailure > config.timeout.toMillis) {
        state = HalfOpen
        successCount = 0
        println("Circuit breaker half-open, testing recovery...")
        executeInHalfOpenState(operation)
      } else {
        Result.failure(
          s"Circuit breaker is OPEN. Service unavailable. Retry in " +
          s"${config.timeout.toMillis - timeSinceFailure}ms"
        )
      }
    }
    
    private def executeInHalfOpenState[A](
      operation: () => Result[A]
    ): Result[A] = {
      operation() match {
        case success @ Result.Success(_) =>
          successCount += 1
          if (successCount >= config.successThreshold) {
            state = Closed
            failureCount = 0
            println("Circuit breaker closed, service recovered")
          }
          success
        case failure @ Result.Failure(error) =>
          state = Open
          lastFailureTime = System.currentTimeMillis()
          println("Circuit breaker reopened")
          failure
      }
    }
  }
}
```

### Usage with Agents

```text
object CircuitBreakerAgent {
  
  def safeAgentCall(
    agent: Agent,
    query: String,
    breaker: CircuitBreaker
  ): Result[String] = {
    breaker.execute { () =>
      agent.run(query).map(_.message)
    }
  }
}
```

---

## Fallback Strategies

### Model Fallback

```text
object ModelFallback {
  
  case class ModelFallbackConfig(
    primaryModel: LlmClient,
    secondaryModel: LlmClient,
    tertiaryModel: LlmClient
  )
  
  def runWithFallback(
    query: String,
    config: ModelFallbackConfig
  ): Result[String] = {
    println("Attempting primary model...")
    config.primaryModel.generate(query) match {
      case success @ Result.Success(_) => success
      case Result.Failure(error1) =>
        println(s"Primary failed: $error1. Trying secondary...")
        config.secondaryModel.generate(query) match {
          case success @ Result.Success(_) => success
          case Result.Failure(error2) =>
            println(s"Secondary failed: $error2. Trying tertiary...")
            config.tertiaryModel.generate(query) match {
              case success @ Result.Success(_) => success
              case Result.Failure(error3) =>
                Result.failure(
                  s"All models failed. Primary: $error1, Secondary: $error2, Tertiary: $error3"
                )
            }
        }
    }
  }
  
  // Cost-aware fallback: use cheaper models first
  def costAwareFallback(
    query: String,
    models: List[(LlmClient, Double)] // (model, costPer1kTokens)
  ): Result[String] = {
    val sortedByPrice = models.sortBy(_._2) // Cheapest first
    
    sortedByPrice.foldLeft[Result[String]](
      Result.failure("No models available")
    ) { case (acc, (model, price)) =>
      acc match {
        case success @ Result.Success(_) => success
        case Result.Failure(_) => 
          println(s"Trying ${model.modelName} (price: $$${price}/1k tokens)")
          model.generate(query)
      }
    }
  }
}
```

### Cached Fallback

```text
object CachedFallback {
  
  def runWithCacheFallback(
    query: String,
    agent: Agent,
    cache: Cache[String],
    circuitBreaker: CircuitBreaker
  ): Result[String] = {
    // Try circuit breaker with cache fallback
    circuitBreaker.execute { () =>
      agent.run(query)
    } match {
      case success @ Result.Success(_) =>
        // Cache the successful response
        cache.put(query, success.message, ttl = 3600)
        success
      case Result.Failure(error) =>
        // Fall back to cache
        cache.get(query) match {
          case Some(cachedResponse) =>
            println(s"Service failed, using cached response")
            Result.success(cachedResponse)
          case None =>
            println(s"Service failed and no cache available")
            Result.failure(error)
        }
    }
  }
}
```

---

## Graceful Degradation

### Feature Degradation

```text
object GracefulDegradation {
  
  case class RAGResult(
    answer: String,
    sources: List[String],
    groundingScore: Double,
    degradedMode: Boolean = false
  )
  
  def answerWithDegradation(
    query: String,
    ragSystem: RAGSystem,
    agent: Agent
  ): RAGResult = {
    // Try full RAG pipeline
    try {
      val sources = ragSystem.retrieve(query)
      val context = sources.map(_.content).mkString("\n")
      val answer = agent.run(
        s"Answer this question using the context:\n$context\n\nQuestion: $query"
      )
      
      val groundingScore = ragSystem.checkGrounding(answer.message, sources)
      
      RAGResult(
        answer = answer.message,
        sources = sources.map(_.url),
        groundingScore = groundingScore,
        degradedMode = false
      )
    } catch {
      case e: Exception =>
        println(s"RAG pipeline failed: ${e.getMessage}. Using degraded mode.")
        // Fall back to simple generation without RAG
        val answer = agent.run(query)
        RAGResult(
          answer = answer.message,
          sources = List(), // No sources
          groundingScore = 0.0, // Unknown grounding
          degradedMode = true
        )
    }
  }
  
  // Progressive degradation - disable expensive features
  def progressiveDegradation(
    operationCost: Double,
    costBudgetRemaining: Double
  ): List[String] = {
    val disabledFeatures = scala.collection.mutable.ListBuffer[String]()
    
    if (operationCost > costBudgetRemaining * 0.5) {
      disabledFeatures += "grounding_check" // Disable grounding check
    }
    
    if (operationCost > costBudgetRemaining * 0.7) {
      disabledFeatures += "reranking" // Disable reranking
    }
    
    if (operationCost > costBudgetRemaining * 0.9) {
      disabledFeatures += "multi_stage_retrieval" // Use single-stage
    }
    
    disabledFeatures.toList
  }
}
```

---

## Failure Scenarios & Responses

### Timeout Handling

```text
object TimeoutHandling {
  
  def handleTimeout[T](
    operation: String,
    timeoutMs: Long
  ): Result[String] = {
    Result.failure(
      s"$operation timed out after ${timeoutMs}ms. " +
      s"This may indicate the service is overloaded or network is slow. " +
      s"Please try again in a moment."
    )
  }
}
```

### Rate Limit Handling

```text
object RateLimitHandling {
  
  def handleRateLimit(
    retryAfterSeconds: Int
  ): Result[String] = {
    Result.failure(
      s"Rate limit exceeded. Please retry after $retryAfterSeconds seconds. " +
      s"Consider implementing exponential backoff and jitter in your client."
    )
  }
}
```

### Provider Outage Handling

```text
object OutageHandling {
  
  def handleProviderOutage(
    provider: String,
    fallbackAvailable: Boolean
  ): Result[String] = {
    if (fallbackAvailable) {
      Result.failure(
        s"$provider is temporarily unavailable. Using fallback provider."
      )
    } else {
      Result.failure(
        s"$provider is temporarily unavailable and no fallback is configured. " +
        s"Please try again later."
      )
    }
  }
}
```

---

## Best Practices

### ✅ Do's

- **Retry transient errors only**: Don't retry 401 authentication errors
- **Use exponential backoff**: Prevents overwhelming failed services
- **Add jitter**: Prevents thundering herd problems
- **Circuit break early**: Don't wait for timeouts
- **Log all retries**: For debugging and monitoring
- **Have multiple fallbacks**: Don't rely on single fallback
- **Test failure paths**: Ensure graceful degradation works
- **Set sensible timeouts**: Not too short (false failures), not too long (user wait)

### ❌ Don'ts

- **Don't retry forever**: Set max attempt limit
- **Don't hardcode delays**: Make retry config configurable
- **Don't lose context**: Include error details in logs
- **Don't assume fixed backoff works**: Different errors need different strategies
- **Don't ignore context limits**: LLM calls may fail near context window limit
- **Don't forget to reset state**: Circuit breaker needs periodic recovery attempts
- **Don't cascade failures**: Use circuit breakers to isolate failures

---

## Error Recovery Checklist

### ✅ Implementation checklist

- [ ] All API calls wrapped with retry logic?
- [ ] Circuit breaker implemented for external services?
- [ ] Fallback models/providers defined?
- [ ] Timeout configured appropriately?
- [ ] Error messages are user-friendly?
- [ ] Errors are logged with full context?
- [ ] Monitoring alerts for error rates?
- [ ] Graceful degradation tested?
- [ ] Failure scenarios documented?

---

## See Also

- [Production Monitoring](./production-monitoring.md) - Monitor error rates
- [Scaling Strategies](./scaling-strategies.md) - Handle failures under load
- [Multi-Agent Orchestration](./multi-agent-orchestration.md) - Fallback agents

---

**Last Updated:** February 2026  
**Status:** Production Ready
