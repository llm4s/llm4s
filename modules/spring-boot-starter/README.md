# llm4s-spring-boot-starter

Spring Boot auto-configuration for llm4s (`org.llm4s:llm4s-spring-boot-starter_3`, built on `llm4s-java-api`).

```properties
# openai | anthropic | ollama
llm4s.provider=openai
llm4s.model=gpt-4o
llm4s.api-key=${OPENAI_API_KEY}
```

```java
@Autowired LLM4STemplate llm;
String reply = llm.complete("Summarise this quarter's results.");
CompletableFuture<String> later = llm.completeAsync("Translate to French.");
```

## Beans

| Bean | Replace it by defining a bean of the same type (name for the executor) |
|------|------|
| `llm4sClient` (`JLlmClient`) | closed on context shutdown |
| `llm4sTaskExecutor` (`ExecutorService`) | defined by name: a bean called `llm4sTaskExecutor` replaces it |
| `llm4sTemplate` (`LLM4STemplate`) | |
| `llmHealthIndicator` (`LlmHealthIndicator`, only with Spring Boot Actuator) | |

Set `llm4s.enabled=false` to switch all of them off.

## `completeAsync`

`completeAsync` never blocks the calling thread. The blocking provider call runs on the
`llm4sTaskExecutor` bean; the returned `CompletableFuture` completes from there and fails with
`LlmException` (the original error is kept). `future.cancel(true)` interrupts the provider call,
which is how llm4s cancels work.

The default executor is a bounded pool of daemon threads named `llm4s-async-N` (idle threads time
out) with a bounded queue; when the queue is full the returned future fails with
`RejectedExecutionException` instead of growing without limit. It is shut down with
`shutdownNow` when the context closes, interrupting calls still running. It is a plain pool rather
than virtual threads so the starter also runs on JDK 17. Your own `llm4sTaskExecutor` must be an
`ExecutorService` whose `submit(..).cancel(true)` interrupts the task (every JDK pool does).

| Property | Default | |
|----------|---------|---|
| `llm4s.async.max-threads` | `16` | worker threads of the default executor |
| `llm4s.async.queue-capacity` | `1000` | queued calls before rejection |

## Health

By default the indicator makes **no provider call**: it is `UP` with `probe=disabled`, `provider` and
`model`, which means "configured", not "reachable". The API key is never reported.

With `llm4s.health.probe=true` it sends a one-token completion (prompt `ping`, `maxTokens=1`) on the
executor, waits at most `probe-timeout`, and caches the outcome (also a failure) for `probe-ttl`, so
health polling does not hit or bill the provider each time. A failed or timed-out probe is `DOWN`
with `probe=failed|timeout` and an `error` message with the API key and token-like strings redacted.
A health check whose thread is interrupted while it waits (Actuator shutting down, say) is `DOWN`
with `probe=cancelled`, the interrupt flag left set; `health()` never throws `InterruptedException`.
A cancelled probe is not cached: it says something about that thread, not the provider, so the next
check probes again. The probe is billed: keep the TTL generous.

| Property | Default | |
|----------|---------|---|
| `llm4s.health.probe` | `false` | opt in to the real probe |
| `llm4s.health.probe-ttl` | `60s` | how long a probe result is reused |
| `llm4s.health.probe-timeout` | `10s` | a slower probe is cancelled and reports DOWN |

Other settings: `llm4s.base-url`, `llm4s.organization`, `llm4s.context-window`,
`llm4s.reserve-completion`. All keys are in `META-INF/additional-spring-configuration-metadata.json`.
