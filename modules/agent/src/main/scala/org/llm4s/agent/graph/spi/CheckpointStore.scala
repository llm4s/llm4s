package org.llm4s.agent.graph.spi

import org.llm4s.agent.graph.Checkpointer
import org.llm4s.annotation.Experimental

import java.util.concurrent.atomic.AtomicBoolean

/**
 * An open checkpoint store, as a [[CheckpointerBackend]] returns it: the [[Checkpointer]] to build a `GraphRuntime`
 * over, and the means to close what the backend opened for it (a file, a connection pool).
 *
 * Close it when the application stops, after every runtime over it has finished: `close()` releases the backend's
 * resources once, and later calls do nothing. Calls to the checkpointer after `close` fail as the store documents.
 *
 * @param kind the `llm4s.agent.checkpointer.kind` the store was opened for
 */
@Experimental
final class CheckpointStore private (val kind: String, val checkpointer: Checkpointer, release: () => Unit)
    extends AutoCloseable:

  private val closed = new AtomicBoolean(false)

  /** Closes what the backend opened for the store; only the first call does anything. */
  def close(): Unit = if closed.compareAndSet(false, true) then release()

  /** Whether [[close]] has been called. */
  def isClosed: Boolean = closed.get

  override def toString: String = s"CheckpointStore($kind)"

object CheckpointStore:

  /** `checkpointer`, with `close` releasing what was opened for it. */
  def apply(kind: String, checkpointer: Checkpointer, close: () => Unit): CheckpointStore =
    new CheckpointStore(kind, checkpointer, close)

  /** A store whose checkpointer closes itself. */
  def apply(kind: String, checkpointer: Checkpointer & AutoCloseable): CheckpointStore =
    new CheckpointStore(kind, checkpointer, () => checkpointer.close())

  /** A store with nothing to close, such as an in-memory one. */
  def unmanaged(kind: String, checkpointer: Checkpointer): CheckpointStore =
    new CheckpointStore(kind, checkpointer, () => ())
