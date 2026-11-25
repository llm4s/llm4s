package org.llm4s.llmconnect

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.annotation.Stable
import org.llm4s.error.{ CancelledError, ConfigurationError }
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.provider.MetricsRecording
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.ProviderId

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mixin trait providing standard lifecycle management and metrics wrapping
 * for [[LLMClient]] implementations.
 *
 * Tracks whether the client has been closed via an `AtomicBoolean` and provides:
 *  - `validateNotClosed` — a pre-check returning `Left(ConfigurationError)` once closed.
 *  - `close()` — an idempotent close that delegates to `releaseResources()` exactly once.
 *  - `completeWithMetrics` — combines lifecycle validation with metrics recording for
 *    [[org.llm4s.llmconnect.model.Completion Completion]] results, eliminating boilerplate in `complete` / `streamComplete`.
 *
 * Concrete clients mix in this trait, supply `provider` and `modelName`,
 * and optionally override `releaseResources()` to free provider-specific
 * resources (HTTP clients, SDK connections, etc.).
 */
@Stable
trait BaseLifecycleLLMClient extends LLMClient with MetricsRecording {

  /** Human-readable label used in the "already closed" error message. */
  protected def clientDescription: String

  /**
   * The provider this client calls (e.g. `ProviderId("openai")`): the provider id tracing reports, the metrics
   * label, as its string, and the provider a cancellation names.
   */
  protected def provider: ProviderId

  /** The model identifier forwarded to the metrics collector. */
  protected def modelName: ModelName

  /**
   * Hook for releasing provider-specific resources.
   * Called at most once, inside `close()`. The default is a no-op.
   */
  protected def releaseResources(): Unit = ()

  /** This client's [[provider]]. */
  override def providerId: Option[ProviderId] = Some(provider)

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
   * An interrupted call - one that throws `InterruptedException`, or fails while the thread is
   * interrupted - is returned as `Left(CancelledError)` with the interrupt flag kept, whatever the
   * provider SDK did with it. A call made with the flag already set returns `Left(CancelledError)`
   * at once, without running `operation`: an SDK that ignores the flag would otherwise send the
   * (billed) request anyway.
   *
   * @param operation The provider-specific completion logic to execute.
   *                  Called only when the client is open and the thread is not interrupted.
   * @return The completion result with metrics recorded as a side-effect.
   */
  protected def completeWithMetrics(operation: => Result[Completion]): Result[Completion] =
    withMetrics(
      provider = provider,
      model = modelName,
      operation =
        if (Thread.currentThread().isInterrupted) Left(CancelledError(s"${provider.asString}.complete"))
        else CancelledError.attempt(s"${provider.asString}.complete")(validateNotClosed.flatMap(_ => operation)),
      extractUsage = (c: Completion) => c.usage,
      // the metrics contract counts cost as a Double
      extractCost = (c: Completion) => c.estimatedCost.map(_.toDouble)
    )
}
