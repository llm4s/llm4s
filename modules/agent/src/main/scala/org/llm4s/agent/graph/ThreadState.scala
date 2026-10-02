package org.llm4s.agent.graph

import org.llm4s.types.{ Result, TryOps }

import scala.util.Try

/**
 * The committed state of a graph thread: a typed key/value store over the graph's registered
 * [[StateKey]]s. It is immutable, so every task in a superstep reads the same snapshot.
 *
 * Reads are checked: a key the graph did not register - or a different key instance sharing a
 * registered key's id - is an [[GraphError.UnknownStateKey]]. A registered key with no stored
 * entry reads as its initial value. The erasure to `Any` stays private to this boundary.
 */
final class ThreadState private[graph] (
  private[graph] val keys: Map[StateKeyId, StateKey[?, ?]],
  private[graph] val values: Map[StateKeyId, Any]
):

  /** The key's committed value, or its initial value when no entry is stored. */
  def get[A](key: StateKey[A, ?]): Result[A] =
    if isRegistered(key) then Right(values.getOrElse(key.id, key.initial).asInstanceOf[A])
    else Left(GraphError.UnknownStateKey(key.id))

  /** Whether the key has a stored entry, as opposed to reading its initial value. */
  def isSet(key: StateKey[?, ?]): Boolean = isRegistered(key) && values.contains(key.id)

  private[graph] def isRegistered(key: StateKey[?, ?]): Boolean = keys.get(key.id).exists(_ eq key)

  private[graph] def applyOperation(operation: StateOperation): Result[ThreadState] =
    if !isRegistered(operation.key) then Left(GraphError.UnknownStateKey(operation.key.id))
    else
      operation match
        case update: StateOperation.Update[?, ?] =>
          // the update function is user code: a throw is a failed update, not an escaped exception
          Try(update.applyTo(values.getOrElse(update.key.id, update.key.initial))).toResult.flatten.left
            .map(cause => GraphError.StateUpdateFailed(update.key.id, cause))
            .map(next => new ThreadState(keys, values.updated(update.key.id, next)))
        case StateOperation.Remove(key) =>
          Right(new ThreadState(keys, values - key.id))

  private[graph] def applyUpdate(update: StateUpdate): Result[ThreadState] =
    update.operations.foldLeft[Result[ThreadState]](Right(this))((state, op) => state.flatMap(_.applyOperation(op)))

  override def toString: String =
    values.toSeq.sortBy(_._1.value).map((id, v) => s"${id.value}=$v").mkString("ThreadState(", ", ", ")")

private[graph] object ThreadState:
  def empty(keys: Map[StateKeyId, StateKey[?, ?]]): ThreadState = new ThreadState(keys, Map.empty)
