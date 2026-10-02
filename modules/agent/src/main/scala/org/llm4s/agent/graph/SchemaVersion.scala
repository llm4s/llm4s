package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * The current version of a persisted JSON shape, with the migrations that bring older versions
 * up to it. A state key's value, a state key's update and a node's input each carry one; their
 * stable codec id is the owner's id with this version (`<keyId>@<version>`), and every encoded
 * value records the version it was written with (see [[VersionedJson]]).
 *
 * A step keyed `n` migrates JSON written at version `n` to version `n + 1`. Steps are plain
 * functions of the compiled graph, never persisted.
 */
final class SchemaVersion private (val current: Int, steps: Map[Int, ujson.Value => Result[ujson.Value]]):

  /** Migrates JSON written at version `from` to [[current]], one step at a time. */
  def upgrade(from: Int, json: ujson.Value): Result[ujson.Value] =
    if from == current then Right(json)
    else if from > current || from < 1 then
      Left(ValidationError("version", s"version $from cannot be read by a codec at version $current"))
    else
      steps
        .get(from)
        .toRight(ValidationError("version", s"no migration from version $from to ${from + 1}"))
        .flatMap(step => step(json))
        .flatMap(upgrade(from + 1, _))

object SchemaVersion:
  val initial: SchemaVersion = new SchemaVersion(1, Map.empty)

  /** Version `current`, with a migration step from each earlier version to the next. */
  def apply(current: Int)(steps: (Int, ujson.Value => Result[ujson.Value])*): SchemaVersion =
    new SchemaVersion(current, steps.toMap)

/** A persisted value and the version of the codec that wrote it. */
final case class VersionedJson(version: Int, value: ujson.Value) derives ReadWriter
