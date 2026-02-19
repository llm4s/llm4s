# Reliable LLM Calling

Production-grade fault tolerance for LLM provider calls through retry logic, circuit breakers, and deadline enforcement. Automatic failure recovery with intelligent retry strategies, circuit breakers to prevent cascading failures, deadline enforcement to prevent unbounded waiting, and built-in metrics for monitoring.

## Architecture

The reliability layer wraps any `LLMClient` implementation, intercepting all operations to apply:

1. **Retry Policy**: Determines if/when to retry failed requests
2. **Circuit Breaker**: Tracks failure patterns and fails fast when service is unhealthy
3. **Deadline Enforcement**: Ensures operations complete within time bounds
4. **Metrics Collection**: Records retry attempts, circuit state transitions, and error types

```
User Code → ReliableClient → Circuit Breaker → Retry Logic → Deadline → Provider Client → LLM API
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

**Using ReliableProviders (Recommended)**

```scala
import org.llm4s.reliability.ReliableProviders
import org.llm4s.llmconnect.config.OpenAIConfig

// Create reliable OpenAI client with default settings
val clientResult = ReliableProviders.openai(
  OpenAIConfig(
    apiKey = "sk-...",
    model = "gpt-4o"
  )
)

clientResult.foreach { client =>
  // Use like any LLMClient
  val result = client.complete(conversation)
}
```

**Using ReliabilitySyntax** - Add `.withReliability()` to any existing client:

```scala
import org.llm4s.reliability.ReliabilitySyntax._
import org.llm4s.llmconnect.provider.OpenAIClient

val client = OpenAIClient(config, metrics).map(_.withReliability())
```

**Manual Wrapping** - Full control over configuration:

```scala
import org.llm4s.reliability.{ ReliableClient, ReliabilityConfig }

val reliableClient = new ReliableClient(
  underlying = myLLMClient,
  providerName = "openai",
  config = ReliabilityConfig.default,
  collector = Some(metricsCollector)
)
```

## Configuration Examples

**Default (Recommended):**
```scala
ReliabilityConfig.default
// 3 retry attempts, 5 failures → open circuit, 5 min deadline
```

**Aggressive** - More retries, faster recovery:

```scala
import org.llm4s.reliability.ReliableProviders

val client = ReliableProviders.anthropic(
  config = anthropicConfig,
  reliabilityConfig = ReliabilityConfig.aggressive
)
// - 5 retry attempts
// - Circuit breaker: 10 failures → open for 15s
// 5 retry attempts, 10 failures → open circuit, 3 min deadline
```

**Conservative** - Fewer retries, longer timeout:

```scala
ReliabilityConfig.conservative
// - 2 retry attempts
// - Circuit breaker: 3 failures → open for 60s
// 2 retry attempts, 3 failures → open circuit, 10 min deadline
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
  deadline = Some(2.minutes)
)

val client = ReliableProviders.openai(
  config = openAIConfig,
  reliabilityConfig = customConfig
)
```

## Provider Examples

```scala
// OpenAI
import org.llm4s.reliability.ReliableProviders
import org.llm4s.llmconnect.config.OpenAIConfig

val client = ReliableProviders.openai(
  OpenAIConfig(
    apiKey = "sk-...",
    model = "gpt-4o",
    baseUrl = Some("https://api.openai.com/v1")
  )
)

// Azure OpenAI
  AzureConfig(
    apiKey = "...",
    endpoint = "https://your-resource.openai.azure.com/",
    deploymentName = "gpt-4o"
  )
)

// Anthropic
  AnthropicConfig(
    apiKey = "sk-ant-...",
    model = "claude-3-5-sonnet-20241022"
  )
)

// Gemini
  GeminiConfig(
    apiKey = "...",
    model = "gemini-2.0-flash-exp"
  )
)

// Ollama
  OllamaConfig(
    baseUrl = "http://localhost:11434",
    model = "llama3.1"
  )
)

// OpenRouter
  OpenAIConfig(
    apiKey = "sk-or-...",
    model = "anthropic/claude-3.5-sonnet",
    baseUrl = Some("https://openrouter.ai/api/v1")
  )
)

// Zai
  ZaiConfig(
    apiKey = "...",
    model = "meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo"
  )
)
```

## Retry Policies

```scala
// Exponential backoff (default): 2^n * baseDelay
RetryPolicy.exponentialBackoff(
  maxAttempts = 3,
  baseDelay = 1.second,
  maxDelay = 32.seconds
// Delays: 1s, 2s, 4s, 8s, 16s, 32s...

// Linear backoff: n * baseDelay
RetryPolicy.linearBackoff(
  maxAttempts = 3,
  baseDelay = 2.seconds
// Delays: 2s, 4s, 6s, 8s...

// Fixed delay
RetryPolicy.fixedDelay(
  maxAttempts = 3,
  delay = 3.seconds
// Delays: 3s, 3s, 3s...

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

val client = ReliableProviders.openai(
  config = openAIConfig,
  metrics = new MyMetricsCollector
)
```

## Error Handling

The reliability layer automatically retries these errors:

- ✅ `RateLimitError` - Respects `Retry-After` header
- ✅ `TimeoutError` - Network timeouts
- ✅ `ServiceError` - 5xx server errors
- ✅ `NetworkError` - Connection failures

Non-retryable errors (fail immediately):

- ❌ `AuthenticationError` - Bad API key
- ❌ `ValidationError` - Invalid input
- ❌ `ConfigurationError` - Client misconfiguration

## Best Practices

### 1. Use Default Configuration First

```scala
// Start here
val client = ReliableProviders.openai(config)
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

val config = ReliabilityProfiles.forEnvironment(sys.env.getOrElse("ENV", "production"))
val client = ReliableProviders.openai(openAIConfig, config)
```

### Pattern 2: Provider-Specific Configurations

```scala
def configForProvider(provider: String): ReliabilityConfig = provider match {
  case "openai" | "anthropic" =>
    // Reliable services: fewer retries
    ReliabilityConfig.conservative
    
  case "ollama" =>
    // Local service: more aggressive retries, no circuit breaker
    ReliabilityConfig.aggressive.copy(
      circuitBreaker = CircuitBreakerConfig.disabled
    )
    
  case _ =>
    ReliabilityConfig.default
}
```

### Pattern 3: Operation-Specific Timeouts

```scala
// Long-running analysis
val analysisConfig = ReliabilityConfig.default.withDeadline(10.minutes)
val analysisClient = ReliableClient.withProviderName(baseClient, "openai", analysisConfig)

// Real-time chat
val chatConfig = ReliabilityConfig.default.withDeadline(30.seconds)
val chatClient = ReliableClient.withProviderName(baseClient, "openai", chatConfig)
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

// Usage
val openAI = ReliableProviders.openai(openAIConfig).toOption.get
val anthropic = ReliableProviders.anthropic(anthropicConfig).toOption.get

callWithFallback(openAI, anthropic, conversation)
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
val client = OpenAIClient(config, metrics).toOption.get
val result = client.complete(conversation)
```

**After (Option 1 - Direct replacement):**
```scala
val client = ReliableProviders.openai(config).toOption.get
val result = client.complete(conversation)
```

**After (Option 2 - Gradual migration):**
```scala
import org.llm4s.reliability.ReliabilitySyntax._

val baseClient = OpenAIClient(config, metrics).toOption.get
val reliableClient = baseClient.withReliability()
val result = reliableClient.complete(conversation)
```

### Testing Strategies

**Unit Tests (Disable Reliability)**:
```scala
class MyServiceTest extends AnyFlatSpec {
  val mockClient: LLMClient = ???
  
  // No retries in tests for fast failures
  val testClient = ReliableClient.withProviderName(
    mockClient,
    "test",
    ReliabilityConfig.disabled
  )
  
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
        if (attempts < 3) Left(RateLimitError("test", Some(1000)))
        else Right(mockCompletion)
      }
      // ... other methods
    }
    
    val reliableClient = ReliableClient.withProviderName(mockClient, "test", ReliabilityConfig.default)
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
import org.llm4s.reliability.{ ReliableProviders, ReliabilityConfig, RetryPolicy }
import org.llm4s.llmconnect.{ LLMConnect }
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
    // (set LLM_MODEL=openai/gpt-4o and OPENAI_API_KEY in the environment)
    val clientResult = for {
      providerConfig <- Llm4sConfig.provider()
      baseClient     <- LLMConnect.getClient(providerConfig, metrics)
    } yield ReliableProviders.wrap(baseClient, "openai", reliabilityConfig, Some(metrics))

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
case class ReliabilityConfig(
  retryPolicy: RetryPolicy,
  circuitBreaker: CircuitBreakerConfig,
  deadline: Option[Duration],
  enabled: Boolean
)
```

**Factory Methods:**
- `ReliabilityConfig.default` - Recommended starting point
- `ReliabilityConfig.conservative` - Fewer retries, longer timeouts
- `ReliabilityConfig.aggressive` - More retries, shorter timeouts
- `ReliabilityConfig.disabled` - No reliability features (testing)

### RetryPolicy

```scala
trait RetryPolicy {
  def maxAttempts: Int
  def delayFor(attemptNumber: Int, error: LLMError): Duration
  def isRetryable(error: LLMError): Boolean
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
case class CircuitBreakerConfig(
  failureThreshold: Int,
  recoveryTimeout: Duration,
  successThreshold: Int
)
```

**Factory Methods:**
- `CircuitBreakerConfig.default` - 5 failures, 30s recovery
- `CircuitBreakerConfig.conservative` - 3 failures, 60s recovery
- `CircuitBreakerConfig.aggressive` - 10 failures, 15s recovery
- `CircuitBreakerConfig.disabled` - Never opens (testing)

### ReliableProviders

Factory methods for all providers:
- `ReliableProviders.openai(...)`
- `ReliableProviders.azureOpenAI(...)`
- `ReliableProviders.anthropic(...)`
- `ReliableProviders.gemini(...)`
- `ReliableProviders.ollama(...)`
- `ReliableProviders.openRouter(...)`
- `ReliableProviders.zai(...)`
- `ReliableProviders.wrap(...)` - Wrap any LLMClient

### ReliabilitySyntax

```scala
import org.llm4s.reliability.ReliabilitySyntax._

client.withReliability()                           // Default config, provider name derived from class
client.withReliability(providerName)               // Explicit provider name (recommended)
client.withReliability(providerName, config)       // With custom config
client.withReliability(providerName, config, metrics) // With custom config and metrics
```

## Summary

### ✅ Production-Ready Reliability

- **Automatic Failure Recovery**: Intelligent retry with exponential backoff
- **Circuit Breaker Protection**: Fail fast when services are down
- **Deadline Enforcement**: Prevent unbounded waiting and resource exhaustion
- **Comprehensive Observability**: Track retries, circuit state, and errors

### ✅ Easy Integration

- **One-Line Setup**: `ReliableProviders.openai(config)`
- **Universal Support**: Works with all 7 LLM providers
- **Drop-In Replacement**: No code changes required
- **Thread-Safe**: Share clients across threads safely

### ✅ Highly Configurable

- **Flexible Retry Policies**: Exponential, linear, fixed, or custom logic
- **Tunable Circuit Breaker**: Adjust thresholds for your SLA requirements
- **Environment-Specific**: Different configs for dev/staging/production
- **Optional Features**: Enable/disable independently

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
- [LLM Provider Documentation](../docs/guide/)
