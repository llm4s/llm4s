package org.llm4s.llmconnect

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mixin trait providing standard lifecycle management for [[LLMClient]] implementations.
 *
 * Tracks whether the client has been closed via an `AtomicBoolean` and provides:
 *  - `validateNotClosed` — a pre-check returning `Left(ConfigurationError)` once closed.
 *  - `close()` — an idempotent close that delegates to `releaseResources()` exactly once.
 *
 * Concrete clients mix in this trait and optionally override `releaseResources()` to
 * free provider-specific resources (HTTP clients, SDK connections, etc.).
 */
trait BaseLifecycleLLMClient extends LLMClient {

  /** Human-readable label used in the "already closed" error message. */
  protected def clientDescription: String

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
}
