package org.llm4s.agent.graph.spi

import org.llm4s.agent.graph.Checkpointer
import org.llm4s.agent.service.RunQueue
import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicBoolean

/**
 * An open checkpoint store, as a [[CheckpointerBackend]] returns it: the [[Checkpointer]] to build a `GraphRuntime`
 * over, the store's [[org.llm4s.agent.service.RunQueue RunQueue]] when it has one, and the means to close what the
 * backend opened for them (a file, a connection pool).
 *
 * The queue lives in the same database as the checkpoints and shares the checkpointer's connection pool (design 4.55),
 * so a queue opened from `application.conf` repeats no store setting under `llm4s.agent.queue`: open the store with
 * `CheckpointerConfigLoader.open()` and take [[runQueue]] from it. The built-in `memory`, `sqlite` and `postgres` stores
 * all have one.
 *
 * Close it when the application stops, after every runtime and worker over it has finished: `close()` releases the
 * backend's resources once, and later calls do nothing. Calls to the checkpointer or the queue after `close` fail as
 * the store documents.
 *
 * @param kind the `llm4s.agent.checkpointer.kind` the store was opened for
 */
@Experimental
final class CheckpointStore private (
  val kind: String,
  val checkpointer: Checkpointer,
  queue: Option[RunQueue],
  release: () => Unit,
  closed: AtomicBoolean
) extends AutoCloseable:

  /**
   * The store's run queue, in the same database as [[checkpointer]] - a `RunSubmitter` and W2's workers are built over
   * the two - or a `ConfigurationError` for a store whose backend gave it none ([[withRunQueue]]).
   */
  def runQueue: Result[RunQueue] =
    queue.toRight(
      ConfigurationError(
        s"The '$kind' checkpoint store has no run queue: llm4s.agent.checkpointer.kind must name a store with one " +
          "(memory, sqlite or postgres)",
        List("llm4s.agent.checkpointer.kind")
      )
    )

  /**
   * This store, with `queue` as its run queue: a backend whose store has a queue in the same database returns its open
   * store this way. The result is the same store - closing either closes it once.
   */
  def withRunQueue(queue: RunQueue): CheckpointStore =
    new CheckpointStore(kind, checkpointer, Some(queue), release, closed)

  /** Closes what the backend opened for the store; only the first call does anything. */
  def close(): Unit = if closed.compareAndSet(false, true) then release()

  /** Whether [[close]] has been called. */
  def isClosed: Boolean = closed.get

  override def toString: String = s"CheckpointStore($kind)"

object CheckpointStore:

  /** `checkpointer`, with `close` releasing what was opened for it, and no run queue until [[withRunQueue]]. */
  def apply(kind: String, checkpointer: Checkpointer, close: () => Unit): CheckpointStore =
    new CheckpointStore(kind, checkpointer, None, close, new AtomicBoolean(false))

  /** A store whose checkpointer closes itself. */
  def apply(kind: String, checkpointer: Checkpointer & AutoCloseable): CheckpointStore =
    apply(kind, checkpointer, () => checkpointer.close())

  /** A store with nothing to close, such as an in-memory one. */
  def unmanaged(kind: String, checkpointer: Checkpointer): CheckpointStore = apply(kind, checkpointer, () => ())
