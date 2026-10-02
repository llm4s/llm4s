package org.llm4s.agent.graph

import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import java.time.Instant
import scala.util.Try

/** Whether a thread's latest checkpoint is mid-execution or finished its run. */
enum CheckpointStatus derives ReadWriter:
  /** Work is scheduled, or the run stopped before completing; continue with `recover`. */
  case Running

  /** The run paused on parked continuations; continue with `resume`. */
  case Suspended

  /** The run completed; a new `start` applies its input to this state. */
  case Completed

/**
 * One durable point in a thread's execution - data only. Closures, codecs and update functions
 * are rebound from the compiled graph on restore; every value inside carries the version of the
 * codec that wrote it ([[VersionedJson]]).
 *
 * `parent` is the checkpoint this one supersedes. A checkpointer accepts a new checkpoint only if
 * `parent` is the thread's latest, so two writers cannot both advance one thread.
 *
 * Persist with [[Checkpoint.toJson]] and read with [[Checkpoint.fromJson]], which migrates older
 * `formatVersion`s and refuses newer ones.
 */
final case class Checkpoint(
  formatVersion: Int,
  id: String,
  parent: Option[String],
  threadId: String,
  runId: String,
  status: CheckpointStatus,
  createdAt: Instant,
  snapshot: GraphSnapshot
)

object Checkpoint:

  /** The format this build writes. */
  val CurrentFormat: Int = 2

  /**
   * Migrations of the checkpoint format itself, keyed by the version they upgrade from.
   * 1 -> 2: suspension (#1269) added parked continuations, the paused flag and task origins.
   */
  private val formatVersion: SchemaVersion = SchemaVersion(2)(1 -> addSuspension)

  private def addSuspension(json: ujson.Value): Result[ujson.Value] =
    Try {
      val upgraded = ujson.copy(json)
      val snapshot = upgraded("snapshot")
      snapshot("parked") = ujson.Arr()
      snapshot("paused") = false
      snapshot("frontier").arr.foreach { task =>
        task("originTask") = upickle.default.writeJs(Option.empty[String])
        task("originNode") = upickle.default.writeJs(Option.empty[String])
      }
      upgraded("formatVersion") = 2
      upgraded
    }.toResult

  private given ReadWriter[Instant] = upickle.default.readwriter[String].bimap(_.toString, Instant.parse)

  given ReadWriter[Checkpoint] = upickle.default.macroRW

  def toJson(checkpoint: Checkpoint): ujson.Value = upickle.default.writeJs(checkpoint)

  def fromJson(json: ujson.Value): Result[Checkpoint] =
    val written = json.objOpt.flatMap(_.get("formatVersion")).flatMap(_.numOpt).map(_.toInt).getOrElse(0)
    formatVersion
      .upgrade(written, json)
      .left
      .map(_ => GraphError.UnsupportedCheckpointFormat(written, CurrentFormat))
      .flatMap(upgraded => Try(upickle.default.read[Checkpoint](upgraded)).toResult)

/**
 * A completed (or suspended) task's result, recorded against the checkpoint whose frontier it ran in, before the
 * superstep commits. On recovery the task is not run again: its command is decoded from here.
 */
final case class PendingWrite(
  checkpointId: String,
  taskId: String,
  nodeId: String,
  operations: Vector[EncodedOperation],
  routes: Vector[EncodedRoute],
  // defaulted so writes recorded against a format-1 checkpoint still read
  suspension: Option[EncodedSuspension] = None
) derives ReadWriter

/** A suspended task's parked continuation: where it resumes and the question, as data. */
final case class EncodedSuspension(resumeNode: String, question: VersionedJson) derives ReadWriter

/** A state operation as data: updates are encoded with the key's update codec. */
enum EncodedOperation derives ReadWriter:
  case Update(keyId: String, update: VersionedJson)
  case Remove(keyId: String)

/** A route as data: payloads are encoded with the target node's input codec. */
enum EncodedRoute derives ReadWriter:
  case Goto(nodeId: String)
  case Send(nodeId: String, payload: VersionedJson)
  case FanOut(joinId: String, nodeId: String, payloads: Vector[VersionedJson])

/** A thread's latest checkpoint with the pending writes recorded against it. */
final case class StoredCheckpoint(checkpoint: Checkpoint, pendingWrites: Vector[PendingWrite])

/**
 * One atomic write to a thread. The checkpointer applies all of it or none of it:
 *
 *  - `checkpoint`, if present, becomes the thread's latest, provided its `parent` is the current
 *    latest; pending writes recorded against the old checkpoint are dropped.
 *  - `pendingWrites` are recorded against the (resulting) latest checkpoint, and must name it.
 *  - `events` are appended to the thread's durable log, each given the next sequence number.
 */
final case class Commit(
  checkpoint: Option[Checkpoint],
  pendingWrites: Vector[PendingWrite],
  events: Vector[EventDraft]
)
