package org.llm4s.llmconnect

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.provider.MetricsRecording
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mixin trait providing standard lifecycle management and metrics wrapping
 * for [[LLMClient]] implementations.
 *
 * Tracks whether the client has been closed via an `AtomicBoolean` and provides:
 *  - `validateNotClosed` — a pre-check returning `Left(ConfigurationError)` once closed.
 *  - `close()` — an idempotent close that delegates to `releaseResources()` exactly once.
 *  - `completeWithMetrics` — combines lifecycle validation with metrics recording for
 *    [[Completion]] results, eliminating boilerplate in `complete` / `streamComplete`.
 *
 * Concrete clients mix in this trait, supply `providerName` and `modelName`,
 * and optionally override `releaseResources()` to free provider-specific
 * resources (HTTP clients, SDK connections, etc.).
 */
trait BaseLifecycleLLMClient extends LLMClient with MetricsRecording {

  /** Human-readable label used in the "already closed" error message. */
  protected def clientDescription: String

  /** Metrics label for this provider (e.g. `"openai"`, `"anthropic"`). */
  protected def providerName: String

  /** The model identifier forwarded to the metrics collector. */
  protected def modelName: String

  /**
   * Hook for releasing provider-specific resources.
   * Called at most once, inside `close()`. The default is a no-op.
   */
  protected def releaseResources(): Unit = ()

  private val closed: AtomicBoolean = new AtomicBoolean(false)

  protected def validateNotClosed: Result[Unit] =
    if (closed.get()) Left(ConfigurationError(s"$clientDescription is already closed"))
    else Right(())

  override def close(): Unit =
    if (closed.compareAndSet(false, true)) releaseResources()

  /**
   * Validates that the client is open, executes the operation, and records
   * standard completion metrics (latency, token usage, estimated cost).
   *
   * Use this in `complete` and `streamComplete` implementations to avoid
   * repeating the lifecycle-check + metrics-wrapping boilerplate.
   *
   * @param operation The provider-specific completion logic to execute.
   *                  Called only when the client is open.
   * @return The completion result with metrics recorded as a side-effect.
   */
  protected def completeWithMetrics(operation: => Result[Completion]): Result[Completion] =
    withMetrics(
      provider = providerName,
      model = modelName,
      operation = validateNotClosed.flatMap(_ => operation),
      extractUsage = (c: Completion) => c.usage,
      extractCost = (c: Completion) => c.estimatedCost
    )
}
