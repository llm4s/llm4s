# Reliable LLM Calling

Production-grade fault tolerance for LLM provider calls through retry logic, circuit breakers, and deadline enforcement. Automatic failure recovery with intelligent retry strategies, circuit breakers to prevent cascading failures, deadline enforcement to prevent unbounded waiting, and built-in metrics for monitoring.

## Architecture

The reliability layer wraps any `LLMClient` implementation, intercepting all operations to apply:

1. **Retry Policy**: Determines if/when to retry failed requests
2. **Circuit Breaker**: Tracks failure patterns and fails fast when service is unhealthy
3. **Deadline Enforcement**: Ensures operations complete within time bounds
4. **Local Rate Limiting** (opt-in): A token bucket consulted before every attempt, retries included
5. **Metrics Collection**: Records retry attempts, circuit state transitions, and error types

```
User Code → ReliableClient → Circuit Breaker → Retry Logic → Deadline → Rate Limit → Provider Client → LLM API
                    ↓                 ↓              ↓
              Metrics Collector  State Tracking  Timeout Control
```

## Features

**Configurable Retry Policies** - Multiple retry strategies with intelligent error classification:

- **Exponential Backoff** (default): `1s → 2s → 4s → 8s → ...`
- **Linear Backoff**: `2s → 4s → 6s → 8s → ...`
- **Fixed Delay**: `3s → 3s → 3s → ...`
- **Custom Logic**: User-defined delay functions
- **Server-Aware**: Respects `Retry-After` headers from providers

**Retryable Errors:**
- `RateLimitError` - 429 responses (uses server retry delay if provided)
- `TimeoutError` - Connection/read timeouts
- `ServiceError` - 5xx server errors
- `NetworkError` - Connection failures, DNS issues

**Non-Retryable Errors:**
- `AuthenticationError` - Invalid credentials (401/403)
- `ValidationError` - Malformed requests (400)
- `ConfigurationError` - Client misconfiguration

**Circuit Breaker Pattern** - Three states for service resilience:

**1. Closed (Normal Operation)**
- All requests pass through
- Failures are counted
- Transitions to Open after N consecutive failures

**2. Open (Failing Fast)**
- Requests fail immediately without calling provider
- Returns `ServiceError(503, "circuit-breaker", ...)`
- After recovery timeout, transitions to Half-Open

**3. Half-Open (Testing Recovery)**
- Allows limited requests to test service health
- Success → back to Closed
- Failure → back to Open

**Benefits:**
- Prevents resource exhaustion from calling dead services
- Reduces latency during outages (fail fast vs. timeout)
- Automatic recovery testing

**Deadline Enforcement** - Prevents unbounded waiting with configurable per-operation timeouts:

- Tracks elapsed time across all retry attempts
- Cancels retries when deadline approaches
- Returns `TimeoutError` with context about attempts made
- Independent of provider-specific timeouts

**Use Cases:**
- Real-time applications requiring bounded latency
- Long-running batch jobs with time limits
- User-facing features with UX constraints

**Metrics Integration** - Comprehensive observability through `MetricsCollector` interface:

**Tracked Metrics:**
- Retry attempt count and delay per provider
- Circuit breaker state transitions (closed → open → half-open)
- Error classification by type
- Operation duration including retries

**Integration:**
- Compatible with any metrics backend (Prometheus, Datadog, CloudWatch)
- No-op implementation for testing
- Separate collection from business logic

## Quick Start

There is one way to make a client reliable: construct a `ReliableClient` around the
`LLMClient` you already have.

```scala
new ReliableClient(
  underlying: LLMClient,
  providerName: String,                         // label used in metrics
  config: ReliabilityConfig,
  collector: Option[MetricsCollector] = None
)
```

**From configuration (recommended)** - build the client for whichever named provider
section `application.conf` selects, then wrap it:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient }
import org.llm4s.types.Result

val clientResult: Result[LLMClient] =
  for
    providerConfig <- Llm4sConfig.defaultProvider()          // or Llm4sConfig.provider("openai-main")
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
  yield new ReliableClient(client, providerConfig.providerId.asString, ReliabilityConfig.default)

clientResult.foreach { client =>
  // Use like any LLMClient
  val result = client.complete(conversation)
}
```

**Manual Wrapping** - any `LLMClient`, with full control over configuration and metrics:

```scala
import org.llm4s.reliability.{ ReliableClient, ReliabilityConfig }

val reliableClient = new ReliableClient(
  underlying = myLLMClient,
  providerName = "openai",
  config = ReliabilityConfig.default,
  collector = Some(metricsCollector)
)
```

The provider name is not inferred: pass the provider id (`providerConfig.providerId.asString`)
or any stable label you want on your metrics.

## Configuration Examples

**Default (Recommended):**
```scala
ReliabilityConfig.default
// 3 retry attempts, 5 failures → open circuit, 5 min deadline
```

**Aggressive** - More retries, faster recovery:

```scala
val client = new ReliableClient(baseClient, "anthropic", ReliabilityConfig.aggressive)
// - 5 retry attempts (500ms base delay)
// - Circuit breaker: 10 failures → open for 15s
// - 3 min deadline
```

**Conservative** - Fewer retries, longer timeout:

```scala
ReliabilityConfig.conservative
// - 2 retry attempts
// - Circuit breaker: 3 failures → open for 60s
// - 10 min deadline
```

**Custom:**

```scala
import org.llm4s.reliability._
import scala.concurrent.duration._

val customConfig = ReliabilityConfig(
  retryPolicy = RetryPolicy.exponentialBackoff(
    maxAttempts = 5,
    baseDelay = 500.millis,
    maxDelay = 30.seconds
  ),
  circuitBreaker = CircuitBreakerConfig(
    failureThreshold = 3,
    recoveryTimeout = 45.seconds,
    successThreshold = 2
  ),
  rateLimit = RateLimitConfig(enabled = true, requestsPerMinute = 120, burstCapacity = 20),
  deadline = Some(2.minutes)
)

val client = new ReliableClient(baseClient, "openai", customConfig)
```

`rateLimit` is off by default (`RateLimitConfig.disabled`). When enabled, `ReliableClient`
takes a token before every attempt, retries included; with none available the attempt fails
with a `RateLimitError` whose origin is `RateLimitOrigin.LocalThrottle`, without reaching the
provider, and one `ErrorKind.RateLimit` metric is recorded. Being a `RateLimitError`, it is
retryable under the default policies.

## Provider Examples

`ReliableClient` wraps any `LLMClient`, so every provider works the same way - including one
supplied by a module `llm4s-core` has never heard of. Each provider lives in its own module
(`llm4s-openai`, `llm4s-anthropic`, `llm4s-gemini`, `llm4s-ollama`, `llm4s-openai-compatible`,
...); add the dependency, declare a named section in `application.conf`, and load it with
`Llm4sConfig.provider(name)` (or `defaultProvider()`) as in the Quick Start:

```hocon
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"        # key from OPENAI_API_KEY via llm4s.credentials.openai.apiKey
    }

    claude {
      provider = "anthropic"
      model    = "claude-sonnet-4-5-latest"
    }
  }
}
```

See [Configuration](getting-started/configuration.md) for every provider's section keys.

## Retry Policies

```scala
// Exponential backoff (default): 2^n * baseDelay
RetryPolicy.exponentialBackoff(
  maxAttempts = 3,
  baseDelay = 1.second,
  maxDelay = 32.seconds
)
// Delays: 1s, 2s, 4s, 8s, 16s, 32s...

// Linear backoff: n * baseDelay
RetryPolicy.linearBackoff(
  maxAttempts = 3,
  baseDelay = 2.seconds
)
// Delays: 2s, 4s, 6s, 8s...

// Fixed delay
RetryPolicy.fixedDelay(
  maxAttempts = 3,
  delay = 3.seconds
)
// Delays: 3s, 3s, 3s...

// No retry
RetryPolicy.noRetry

// Custom policy
RetryPolicy.custom(
  attempts = 5,
  delayFn = (attempt, error) => {
    error match {
      case _: RateLimitError => (attempt * 5).seconds
      case _: TimeoutError   => 1.second
      case _                 => (attempt * 2).seconds
    }
  },
  retryableFn = {
    case _: RateLimitError => true
    case _: NetworkError   => true
    case _                 => false
  }
)
```

## Circuit Breaker

Three states: **Closed** (normal), **Open** (failing fast), **Half-Open** (testing recovery).

Configuration:

```scala
CircuitBreakerConfig(
  failureThreshold = 5,      // Open after 5 consecutive failures
  recoveryTimeout = 30.seconds,  // Wait 30s before testing recovery
  successThreshold = 2       // Close after 2 successes in half-open
)
```

### Monitoring

```scala
val reliableClient: ReliableClient = ???

// Check circuit breaker state
reliableClient.currentCircuitState match {
  case CircuitState.Closed   => println("Circuit healthy")
  case CircuitState.Open     => println("Circuit open - failing fast")
  case CircuitState.HalfOpen => println("Circuit testing recovery")
}

// Reset for testing (not for production use)
reliableClient.resetCircuitBreaker()
```

## Metrics Integration

Track reliability metrics with any MetricsCollector:

```scala
import org.llm4s.metrics.MetricsCollector

class MyMetricsCollector extends MetricsCollector {
  override def recordRetryAttempt(provider: String, attemptNumber: Int): Unit = {
    println(s"$provider: Retry attempt #$attemptNumber")
  }

  override def recordCircuitBreakerTransition(provider: String, newState: String): Unit = {
    println(s"$provider: Circuit breaker → $newState")
  }

  // ... implement other methods
}

val metrics = new MyMetricsCollector
val client  = new ReliableClient(baseClient, "openai", ReliabilityConfig.default, Some(metrics))
```

`recordRetryAttempt`, `recordCircuitBreakerTransition` and `recordError` default to no-ops;
`observeRequest`, `addTokens` and `recordCost` must be implemented. To also get per-call
latency and token metrics from the provider client, pass the same collector to
`LLMConnect.getClient(providerConfig, metrics)`.

## Error Handling

The reliability layer automatically retries these errors:

- ✅ `RateLimitError` - Respects `Retry-After` header
- ✅ `TimeoutError` - Network timeouts
- ✅ `ServiceError` and `APIError` with a retryable status - any 5xx, 429 or 408; respects a 503's `Retry-After` header. An `APIError` with no status is retried too
- ✅ `NetworkError` - Connection failures
- ✅ `ExecutionError` and `SystemError` - transient execution and system failures

Non-retryable errors (fail immediately):

- ❌ `AuthenticationError` - Bad API key
- ❌ `ValidationError` - Invalid input
- ❌ `ConfigurationError` - Client misconfiguration
- ❌ `ServiceError` and `APIError` with any other 4xx status - the request itself is wrong, so repeating it cannot succeed
- ❌ `OptimisticLockFailure` - recoverable, but the caller must re-read the record first
- ❌ `CancelledError` - the caller stopped the call

**One rule.** Automatic retry is `LLMError.isRecoverable` minus the two exceptions above (a client-error response
and an `OptimisticLockFailure`). `RetryPolicy.isRetryable`, `LLMClientRetry` and an agent graph node's default
retry all use it, so they cannot disagree about an error. `isRecoverable` means the error may succeed if tried
again, perhaps after the caller does something; automatic retry is the part where repeating the identical request is
enough. `RetryPolicy.custom(attempts, delayFn)` uses the same rule unless you pass your own `retryableFn`, and
`ServiceError.isRecoverableStatus` is the same status check, so a policy that customises only the delay retries
exactly what the other policies retry.

## Best Practices

### 1. Use Default Configuration First

```scala
// Start here
val client = new ReliableClient(baseClient, providerConfig.providerId.asString, ReliabilityConfig.default)
```

Only customize if you have specific requirements.

### 2. Monitor Circuit Breaker State

```scala
// In production, log circuit breaker transitions
class ProductionMetrics extends MetricsCollector {
  override def recordCircuitBreakerTransition(provider: String, newState: String): Unit = {
    logger.warn(s"Circuit breaker for $provider transitioned to $newState")
    alerting.sendAlert(s"Circuit breaker: $provider → $newState")
  }
  // ... observeRequest, addTokens, recordCost
}
```

### 3. Set Appropriate Deadlines

```scala
// Long-running tasks
ReliabilityConfig.default.withDeadline(10.minutes)

// Real-time interactions
ReliabilityConfig.default.withDeadline(30.seconds)
```

### 4. Disable for Testing

```scala
// In tests, disable reliability for faster failures
val testClient = new ReliableClient(
  underlying = mockClient,
  providerName = "test",
  config = ReliabilityConfig.disabled,
  collector = None
)
```

## Advanced Patterns

### Pattern 1: Different Configs Per Environment

```scala
object ReliabilityProfiles {
  def forEnvironment(env: String): ReliabilityConfig = env match {
    case "production" => ReliabilityConfig.default
    case "staging"    => ReliabilityConfig.aggressive
    case "development" => ReliabilityConfig.disabled
    case _ => ReliabilityConfig.conservative
  }
}

// appEnv comes from your application's own configuration
val config = ReliabilityProfiles.forEnvironment(appEnv)
val client = new ReliableClient(baseClient, "openai", config)
```

### Pattern 2: Provider-Specific Configurations

```scala
def configForProvider(provider: String): ReliabilityConfig = provider match {
  case "openai" | "anthropic" =>
    // Reliable services: fewer retries
    ReliabilityConfig.conservative
    
  case "ollama" =>
    // Local service: more aggressive retries, no circuit breaker
    ReliabilityConfig.aggressive.withCircuitBreaker(CircuitBreakerConfig.disabled)
    
  case _ =>
    ReliabilityConfig.default
}
```

### Pattern 3: Operation-Specific Timeouts

```scala
// Long-running analysis
val analysisConfig = ReliabilityConfig.default.withDeadline(10.minutes)
val analysisClient = new ReliableClient(baseClient, "openai", analysisConfig)

// Real-time chat
val chatConfig = ReliabilityConfig.default.withDeadline(30.seconds)
val chatClient = new ReliableClient(baseClient, "openai", chatConfig)
```

### Pattern 4: Graceful Degradation

```scala
def callWithFallback(
  primaryClient: LLMClient,
  fallbackClient: LLMClient,
  conversation: Conversation
): Result[Completion] = {
  primaryClient.complete(conversation) match {
    case Right(completion) => Right(completion)
    case Left(error) =>
      logger.warn(s"Primary failed: ${error.message}, trying fallback")
      fallbackClient.complete(conversation)
  }
}

// Usage (with a given ModelRegistryService in scope, as in the Quick Start)
for
  openAIConfig    <- Llm4sConfig.provider("openai-main")
  anthropicConfig <- Llm4sConfig.provider("claude")
  openAIBase      <- LLMConnect.getClient(openAIConfig)
  anthropicBase   <- LLMConnect.getClient(anthropicConfig)
  openAI    = new ReliableClient(openAIBase, "openai", ReliabilityConfig.default)
  anthropic = new ReliableClient(anthropicBase, "anthropic", ReliabilityConfig.default)
  completion <- callWithFallback(openAI, anthropic, conversation)
yield completion
```

### Pattern 5: Circuit Breaker Monitoring

```scala
class ProductionMetrics extends MetricsCollector {
  private val circuitOpenAlerts = mutable.Set[String]()
  
  override def recordCircuitBreakerTransition(provider: String, newState: String): Unit = {
    newState match {
      case "open" =>
        if (!circuitOpenAlerts.contains(provider)) {
          alerting.sendCritical(s"Circuit breaker OPEN for $provider")
          circuitOpenAlerts += provider
        }
        
      case "closed" =>
        if (circuitOpenAlerts.contains(provider)) {
          alerting.sendInfo(s"Circuit breaker CLOSED for $provider - recovered")
          circuitOpenAlerts -= provider
        }
        
      case "half-open" =>
        logger.info(s"Circuit breaker testing recovery for $provider")
    }
  }
  // ... observeRequest, addTokens, recordCost
}
```

## Troubleshooting

### Issue: Too Many Retries

**Symptoms**: Operations take too long, excessive API calls

**Solution**:
```scala
// Reduce retry attempts
val config = ReliabilityConfig.default.withRetryPolicy(
  RetryPolicy.exponentialBackoff(maxAttempts = 2)
)

// Or disable for specific operations
val config = ReliabilityConfig.disabled
```

### Issue: Circuit Breaker Opens Too Quickly

**Symptoms**: Circuit opens during temporary issues, failing valid requests

**Solution**:
```scala
// Increase failure threshold
val config = ReliabilityConfig.default.withCircuitBreaker(
  CircuitBreakerConfig(
    failureThreshold = 10,  // More failures before opening
    recoveryTimeout = 30.seconds,
    successThreshold = 3     // More successes before closing
  )
)
```

### Issue: Timeouts Too Aggressive

**Symptoms**: Operations frequently timeout, but would succeed with more time

**Solution**:
```scala
// Increase deadline
val config = ReliabilityConfig.default.withDeadline(5.minutes)

// Or remove deadline entirely
val config = ReliabilityConfig.default.withoutDeadline
```

### Issue: Rate Limits Not Respected

**Symptoms**: Getting 429 errors despite retries

**Cause**: Provider returns rate limit but client retries too quickly

**Solution**: The framework automatically respects `Retry-After` headers. If still seeing issues:

```scala
// Use longer base delay
val config = ReliabilityConfig.default.withRetryPolicy(
  RetryPolicy.exponentialBackoff(
    maxAttempts = 3,
    baseDelay = 5.seconds,  // Start with longer delay
    maxDelay = 60.seconds
  )
)
```

To stay under the provider's quota in the first place, enable local rate limiting.
`ReliableClient` applies it itself, taking a token before every attempt (retries included):

```scala
val config = ReliabilityConfig.default.withRateLimit(
  RateLimitConfig(enabled = true, requestsPerMinute = 60, burstCapacity = 10)
)
val client = new ReliableClient(baseClient, "openai", config)
```

A request rejected locally fails with a `RateLimitError` of origin
`RateLimitOrigin.LocalThrottle` - it never reached the provider - and records one
`ErrorKind.RateLimit` metric.

### Issue: Circuit Breaker Never Closes

**Symptoms**: Circuit stuck in open state, manual intervention needed

**Diagnosis**:
```scala
val reliableClient: ReliableClient = ???
reliableClient.currentCircuitState // Check state
```

**Solution**:
```scala
// For emergencies only (not for production automation)
reliableClient.resetCircuitBreaker()

// Better: Fix underlying service issues and wait for auto-recovery
// Circuit will automatically test recovery after recoveryTimeout
```

## Migration Guide

### From Non-Reliable Clients

**Before:**
```scala
val clientResult =
  for
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
  yield client
```

**After:**
```scala
val clientResult =
  for
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
  yield new ReliableClient(client, providerConfig.providerId.asString, ReliabilityConfig.default)
```

`ReliableClient` is an `LLMClient`, so nothing that uses the client changes.

### From `ReliableProviders` / `withReliability`

`ReliableProviders.wrap`, the `ReliabilitySyntax` extension (`.withReliability(...)`) and the
`ReliableClient(...)` / `ReliableClient.withProviderName(...)` factories have been removed;
the factories guessed the provider name from the client's class name. Build the client with
`LLMConnect.getClient` and call the constructor with an explicit provider name, as above.
`ReliabilityConfig.rateLimit`, which only `ReliableProviders.wrap` used to honour, is now
applied by `ReliableClient` itself.

### Testing Strategies

**Unit Tests (Disable Reliability)**:
```scala
class MyServiceTest extends AnyFlatSpec {
  val mockClient: LLMClient = ???
  
  // No retries in tests for fast failures
  val testClient = new ReliableClient(mockClient, "test", ReliabilityConfig.disabled)
  
  "MyService" should "handle LLM responses" in {
    // Test logic
  }
}
```

**Integration Tests (Verify Reliability)**:
```scala
class ReliabilityIntegrationTest extends AnyFlatSpec {
  "ReliableClient" should "retry on rate limits" in {
    var attempts = 0
    val mockClient = new LLMClient {
      override def complete(conv: Conversation, opts: CompletionOptions) = {
        attempts += 1
        if (attempts < 3) Left(RateLimitError("test", 1.second)) // retry after 1s
        else Right(mockCompletion)
      }
      // ... other methods
    }
    
    val reliableClient = new ReliableClient(mockClient, "test", ReliabilityConfig.default)
    val result = reliableClient.complete(conversation)
    
    result shouldBe Right(mockCompletion)
    attempts shouldBe 3 // Verify it retried
  }
}
```

## Performance Considerations

### Latency Impact

**Without Reliability:**
- Single request: `100ms` (API latency only)
- Failed request: `100ms` + error handling

**With Reliability (Success):**
- Single request: `100ms` + minimal overhead
- Failed request: `100ms` + retries + backoff delays

**Example Retry Timeline:**
```
Attempt 1: 0ms → failure at 100ms
Delay: 1000ms
Attempt 2: 1100ms → failure at 1200ms  
Delay: 2000ms
Attempt 3: 3200ms → success at 3300ms
Total: 3300ms (3.3s)
```

### Memory Footprint

**Per ReliableClient:**
- Circuit breaker state: atomic integers and references for thread-safe state management
- Configuration: immutable case classes
- Memory overhead is minimal and designed to be efficient

Safe to wrap many clients without memory concerns.

### Thread Safety

All reliability features are **thread-safe**:
- Circuit breaker state uses `AtomicInteger` and `AtomicReference` for safe concurrent access
- All state transitions are atomic using compare-and-set operations
- Metrics collection is caller-controlled
- Safe to share `ReliableClient` instances across threads

### Best Practices

1. **Reuse Clients**: Create once, use many times
2. **Set Appropriate Deadlines**: Balance UX vs. success rate
3. **Monitor Circuit Breaker**: Alert on open state
4. **Log Retry Attempts**: Track retry frequency in production
5. **Test Failure Scenarios**: Verify retry logic with mocks

## Complete Example

```scala
import org.llm4s.reliability.{ CircuitBreakerConfig, ReliableClient, ReliabilityConfig, RetryPolicy }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService
import org.llm4s.llmconnect.model.{ Conversation, UserMessage, CompletionOptions }
import org.llm4s.metrics.{ MetricsCollector, Outcome, ErrorKind }
import org.llm4s.config.Llm4sConfig
import scala.concurrent.duration._

object ProductionExample {
  def main(args: Array[String]): Unit = {
    // Production metrics with alerting
    val metrics = new ProductionMetrics()

    // Production reliability config
    val reliabilityConfig = ReliabilityConfig(
      retryPolicy = RetryPolicy.exponentialBackoff(
        maxAttempts = 5,
        baseDelay = 1.second,
        maxDelay = 32.seconds
      ),
      circuitBreaker = CircuitBreakerConfig(
        failureThreshold = 5,
        recoveryTimeout = 30.seconds,
        successThreshold = 2
      ),
      deadline = Some(3.minutes)
    )

    // Create reliable client using Llm4sConfig to load provider configuration
    // (the default named section in application.conf, e.g. provider = "openai"
    // whose key comes from OPENAI_API_KEY - see getting-started/configuration)
    val clientResult = for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      baseClient     <- LLMConnect.getClient(providerConfig, metrics)
    } yield new ReliableClient(baseClient, providerConfig.providerId.asString, reliabilityConfig, Some(metrics))

    clientResult match {
      case Right(client) =>
        try {
          val conversation = Conversation(Seq(
            UserMessage("Analyze this production incident...")
          ))

          val startTime = System.currentTimeMillis()
          
          client.complete(conversation) match {
            case Right(completion) =>
              val duration = System.currentTimeMillis() - startTime
              println(s"✅ Success after ${duration}ms")
              println(s"Response: ${completion.content.take(100)}...")

            case Left(error) =>
              val duration = System.currentTimeMillis() - startTime
              println(s"❌ Failed after ${duration}ms: ${error.message}")
              // Trigger fallback logic, alerting, etc.
          }
        } finally {
          client.close()
        }

      case Left(error) =>
        println(s"❌ Failed to create client: ${error.message}")
    }
  }

  class ProductionMetrics extends MetricsCollector {
    private var retryCount = 0
    
    override def recordRetryAttempt(provider: String, attemptNumber: Int): Unit = {
      retryCount += 1
      println(s"Retry #$attemptNumber for $provider (total retries: $retryCount)")
      
      // Alert if retry rate is high
      if (retryCount > 100) {
        // alerting.sendWarning(s"High retry rate: $retryCount retries")
      }
    }

    override def recordCircuitBreakerTransition(provider: String, newState: String): Unit = {
      println(s"Circuit breaker: $provider → $newState")
      
      newState match {
        case "open" =>
          // alerting.sendCritical(s"Circuit breaker OPEN for $provider")
          println(s"❌ CRITICAL: Circuit open for $provider")
          
        case "closed" =>
          // alerting.sendInfo(s"Circuit breaker recovered for $provider")
          println(s"✅ Circuit recovered for $provider")
          
        case _ => // half-open
      }
    }

    override def recordError(errorKind: ErrorKind, provider: String): Unit = {
      println(s"❌ Error: $errorKind for $provider")
    }

    override def observeRequest(
      provider: String,
      model: String,
      outcome: Outcome,
      duration: FiniteDuration
    ): Unit = {
      // Send to metrics backend (Prometheus, Datadog, etc.)
      outcome match {
        case Outcome.Success =>
          println(s"✅ Request succeeded in ${duration.toMillis}ms")
        case Outcome.Error(kind) =>
          println(s"❌ Request failed with $kind in ${duration.toMillis}ms")
      }
    }

    override def addTokens(
      provider: String,
      model: String,
      inputTokens: Long,
      outputTokens: Long
    ): Unit = {
      println(s"Tokens: $inputTokens in, $outputTokens out")
    }

    override def recordCost(
      provider: String,
      model: String,
      costUsd: Double
    ): Unit = {
      println(f"Cost: $$${costUsd}%.4f")
    }
  }
}
```

## API Reference

### ReliabilityConfig

```scala
final case class ReliabilityConfig(
  retryPolicy: RetryPolicy = RetryPolicy.exponentialBackoff(),
  circuitBreaker: CircuitBreakerConfig = CircuitBreakerConfig.default,
  rateLimit: RateLimitConfig = RateLimitConfig.disabled,
  deadline: Option[FiniteDuration] = Some(5.minutes),
  enabled: Boolean = true
)
```

**Builders:** `withRetryPolicy`, `withCircuitBreaker`, `withRateLimit`, `withDeadline`,
`withoutDeadline`, `disabled`.

**Factory Methods:**
- `ReliabilityConfig.default` - Recommended starting point
- `ReliabilityConfig.conservative` - Fewer retries, longer timeouts
- `ReliabilityConfig.aggressive` - More retries, shorter timeouts
- `ReliabilityConfig.disabled` - No reliability features (testing)

### RetryPolicy

```scala
sealed trait RetryPolicy {
  def maxAttempts: Int
  def delayFor(attemptNumber: Int, error: LLMError): FiniteDuration
  def isRetryable(error: LLMError): Boolean // default: the recoverable errors, except a client-error response (4xx other than 408/429) and an OptimisticLockFailure
}
```

**Factory Methods:**
- `RetryPolicy.exponentialBackoff(...)` - 2^n * baseDelay
- `RetryPolicy.linearBackoff(...)` - n * baseDelay
- `RetryPolicy.fixedDelay(...)` - constant delay
- `RetryPolicy.noRetry` - Fail immediately
- `RetryPolicy.custom(...)` - User-defined logic

### CircuitBreakerConfig

```scala
final case class CircuitBreakerConfig(
  failureThreshold: Int = 5,
  recoveryTimeout: FiniteDuration = 30.seconds,
  successThreshold: Int = 2
)
```

**Factory Methods:**
- `CircuitBreakerConfig.default` - 5 failures, 30s recovery
- `CircuitBreakerConfig.conservative` - 3 failures, 60s recovery
- `CircuitBreakerConfig.aggressive` - 10 failures, 15s recovery
- `CircuitBreakerConfig.disabled` - Never opens (testing)

### RateLimitConfig

```scala
final case class RateLimitConfig(
  enabled: Boolean = false,
  requestsPerMinute: Int = 60,
  burstCapacity: Int = 60
)
```

`RateLimitConfig.disabled` is the default. Builders: `withRequestsPerMinute`,
`withBurstCapacity`, `withEnabled`.

### ReliableClient

```scala
final class ReliableClient(
  underlying: LLMClient,
  providerName: String,
  config: ReliabilityConfig,
  collector: Option[MetricsCollector] = None
) extends LLMClient

def currentCircuitState: CircuitState   // Closed | Open | HalfOpen
def resetCircuitBreaker(): Unit         // testing / emergencies only
```

## Summary

### ✅ Production-Ready Reliability

- **Automatic Failure Recovery**: Intelligent retry with exponential backoff
- **Circuit Breaker Protection**: Fail fast when services are down
- **Deadline Enforcement**: Prevent unbounded waiting and resource exhaustion
- **Comprehensive Observability**: Track retries, circuit state, and errors

### ✅ Easy Integration

- **One-Line Setup**: `new ReliableClient(client, providerName, ReliabilityConfig.default)`
- **Universal Support**: Wraps any `LLMClient`, from any provider module
- **Drop-In Replacement**: No code changes required
- **Thread-Safe**: Share clients across threads safely

### ✅ Highly Configurable

- **Flexible Retry Policies**: Exponential, linear, fixed, or custom logic
- **Tunable Circuit Breaker**: Adjust thresholds for your SLA requirements
- **Environment-Specific**: Different configs for dev/staging/production
- **Optional Features**: Enable/disable independently, including local rate limiting

### ✅ Battle-Tested Patterns

- **Graceful Degradation**: Fallback between providers
- **Provider-Specific Tuning**: Different configs per service
- **Operation-Specific Timeouts**: Balance speed vs. success rate
- **Alerting Integration**: Monitor circuit breaker health

---

**Next Steps:**

1. Start with `ReliabilityConfig.default` for immediate protection
2. Monitor circuit breaker transitions in production
3. Tune retry attempts based on observed failure rates
4. Set appropriate deadlines for your use cases
5. Integrate with your metrics backend for observability

**Additional Resources:**

- [Source code](../modules/core/src/main/scala/org/llm4s/reliability/)
- [LLM Provider Documentation](guide/)
