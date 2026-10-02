package org.llm4s.agent.graph

import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * A typed slot in a graph's thread state.
 *
 * `A` is the stored value and `U` the update a node emits for it. Every update - including a
 * single writer's - is applied to the committed value with `applyUpdate`, in deterministic
 * task and emission order; replacement is the special case `U = A` (see [[StateKey.replace]]).
 * The function belongs to the compiled graph, never to a snapshot: snapshots hold only the
 * value encoded with `stateCodec`.
 *
 * Keys are compared by identity. A graph rejects two distinct keys with the same `id`.
 */
final class StateKey[A, U] private (
  val id: StateKeyId,
  val stateCodec: ReadWriter[A],
  val updateCodec: ReadWriter[U],
  val initial: A,
  val applyUpdate: (A, U) => Result[A]
):
  override def toString: String = s"StateKey(${id.value})"

  private[graph] def encode(value: Any): ujson.Value = upickle.default.writeJs(value.asInstanceOf[A])(using stateCodec)
  private[graph] def decode(json: ujson.Value): A    = upickle.default.read[A](json)(using stateCodec)

object StateKey:

  /** A key whose updates are operations applied to the current value. */
  def apply[A, U](id: String, initial: A)(applyUpdate: (A, U) => Result[A])(using
    stateCodec: ReadWriter[A],
    updateCodec: ReadWriter[U]
  ): StateKey[A, U] =
    new StateKey(StateKeyId(id), stateCodec, updateCodec, initial, applyUpdate)

  /** A key whose update is its next value: the last update applied wins. */
  def replace[A](id: String, initial: A)(using codec: ReadWriter[A]): StateKey[A, A] =
    apply[A, A](id, initial)((_, next) => Right(next))

  /** A key that collects every update, in commit order, starting empty. */
  def appending[A](id: String)(using codec: ReadWriter[A]): StateKey[Vector[A], A] =
    apply[Vector[A], A](id, Vector.empty)((values, next) => Right(values :+ next))
