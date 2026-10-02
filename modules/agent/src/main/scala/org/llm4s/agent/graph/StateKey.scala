package org.llm4s.agent.graph

import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import scala.util.Try

/**
 * A typed slot in a graph's thread state.
 *
 * `A` is the stored value and `U` the update a node emits for it. Every update - including a
 * single writer's - is applied to the committed value with `applyUpdate`, in deterministic
 * task and emission order; replacement is the special case `U = A` (see [[StateKey.replace]]).
 * The function belongs to the compiled graph, never to a checkpoint: checkpoints hold values
 * encoded with `stateCodec` and pending updates encoded with `updateCodec`, each tagged with the
 * codec's [[SchemaVersion]] so an older checkpoint is migrated before it is decoded.
 *
 * Keys are compared by identity. A graph rejects two distinct keys with the same `id`.
 */
final class StateKey[A, U] private (
  val id: StateKeyId,
  val stateCodec: ReadWriter[A],
  val updateCodec: ReadWriter[U],
  val initial: A,
  val applyUpdate: (A, U) => Result[A],
  val stateVersion: SchemaVersion,
  val updateVersion: SchemaVersion
):

  /** This key with a new state codec version and its migrations; build keys once, at definition. */
  def withStateVersion(version: SchemaVersion): StateKey[A, U] =
    new StateKey(id, stateCodec, updateCodec, initial, applyUpdate, version, updateVersion)

  /** This key with a new update codec version and its migrations; build keys once, at definition. */
  def withUpdateVersion(version: SchemaVersion): StateKey[A, U] =
    new StateKey(id, stateCodec, updateCodec, initial, applyUpdate, stateVersion, version)

  override def toString: String = s"StateKey(${id.value})"

  private[graph] def encode(value: Any): VersionedJson =
    VersionedJson(stateVersion.current, upickle.default.writeJs(value.asInstanceOf[A])(using stateCodec))

  private[graph] def decode(json: VersionedJson): Result[A] =
    stateVersion
      .upgrade(json.version, json.value)
      .flatMap(v => Try(upickle.default.read[A](v)(using stateCodec)).toResult)

  private[graph] def encodeUpdate(update: Any): VersionedJson =
    VersionedJson(updateVersion.current, upickle.default.writeJs(update.asInstanceOf[U])(using updateCodec))

  private[graph] def decodeUpdate(json: VersionedJson): Result[U] =
    updateVersion
      .upgrade(json.version, json.value)
      .flatMap(v => Try(upickle.default.read[U](v)(using updateCodec)).toResult)

object StateKey:

  /** A key whose updates are operations applied to the current value. */
  def apply[A, U](id: String, initial: A)(applyUpdate: (A, U) => Result[A])(using
    stateCodec: ReadWriter[A],
    updateCodec: ReadWriter[U]
  ): StateKey[A, U] =
    new StateKey(
      StateKeyId(id),
      stateCodec,
      updateCodec,
      initial,
      applyUpdate,
      SchemaVersion.initial,
      SchemaVersion.initial
    )

  /** A key whose update is its next value: the last update applied wins. */
  def replace[A](id: String, initial: A)(using codec: ReadWriter[A]): StateKey[A, A] =
    apply[A, A](id, initial)((_, next) => Right(next))

  /** A key that collects every update, in commit order, starting empty. */
  def appending[A](id: String)(using codec: ReadWriter[A]): StateKey[Vector[A], A] =
    apply[Vector[A], A](id, Vector.empty)((values, next) => Right(values :+ next))
