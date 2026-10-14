package org.llm4s.agent.graph

import org.llm4s.types.Result

/**
 * An ordered list of state operations emitted by one node task.
 *
 * Operations apply in emission order. `combine` is sequential composition: `a.combine(b)` applies
 * `a`'s operations and then `b`'s, so for a [[StateKey.replace]] key the later value wins and for
 * an operation-valued key both operations apply. Updates from different tasks in one superstep
 * are never combined; the scheduler applies them task by task in frontier order.
 */
final class StateUpdate private[graph] (private[graph] val operations: Vector[StateOperation]):

  /** Appends an update operation for `key`. */
  def update[A, U](key: StateKey[A, U], value: U): StateUpdate =
    new StateUpdate(operations :+ StateOperation.Update(key, value))

  /**
   * Appends a removal of `key`'s stored entry; later reads observe the key's initial value.
   * It does not remove one item from a collection - that is an ordinary typed update.
   */
  def remove(key: StateKey[?, ?]): StateUpdate =
    new StateUpdate(operations :+ StateOperation.Remove(key))

  /**
   * Appends a restore of `key` to `value`, a value it held before, or the removal of its entry for `None`. It
   * bypasses the key's update function, which has no update for "go back": only a [[NodeResult.Block]] update
   * uses it, to restore a blocked turn, and a Block update is never a pending write.
   */
  private[graph] def restore[A](key: StateKey[A, ?], value: Option[A]): StateUpdate =
    new StateUpdate(
      operations :+ value.fold[StateOperation](StateOperation.Remove(key))(StateOperation.Restore(key, _))
    )

  /** Sequential composition: this update's operations, then `next`'s. */
  def combine(next: StateUpdate): StateUpdate =
    new StateUpdate(operations ++ next.operations)

  def isEmpty: Boolean = operations.isEmpty

  override def toString: String = operations.mkString("StateUpdate(", ", ", ")")

object StateUpdate:
  val empty: StateUpdate = new StateUpdate(Vector.empty)

  def update[A, U](key: StateKey[A, U], value: U): StateUpdate = empty.update(key, value)

  def remove(key: StateKey[?, ?]): StateUpdate = empty.remove(key)

  private[graph] def restore[A](key: StateKey[A, ?], value: Option[A]): StateUpdate = empty.restore(key, value)

sealed private[graph] trait StateOperation:
  def key: StateKey[?, ?]

private[graph] object StateOperation:

  final case class Update[A, U](key: StateKey[A, U], value: U) extends StateOperation:
    def applyTo(current: Any): Result[A] = key.applyUpdate(current.asInstanceOf[A], value)

  final case class Remove(key: StateKey[?, ?]) extends StateOperation

  /** Sets `key`'s stored value to `value`, without its update function; see [[StateUpdate.restore]]. */
  final case class Restore[A](key: StateKey[A, ?], value: A) extends StateOperation
