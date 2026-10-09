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
   * The run ended in a failure that is its outcome, not an interruption (a node returned
   * [[NodeResult.Block]], as a guardrail does): the thread is usable, `start` applies a new input to
   * this state exactly as after `Completed`, and `recover` has nothing to continue.
   */
  case Failed

/**
 * One durable point in a thread's execution - data only. Closures, codecs and update functions
 * are rebound from the compiled graph on restore; every value inside carries the version of the
 * codec that wrote it ([[VersionedJson]]).
 *
 * `parent` is the checkpoint this one supersedes. A checkpointer accepts a new checkpoint only if
 * `parent` is the thread's latest, so two writers cannot both advance one thread: the latest
 * checkpoint's id is the thread's version. `fencingToken` is the token of the claim whose run wrote
 * it ([[RunClaim]]); `None` on checkpoints written before claims existed (format 4 and earlier).
 *
 * Persist with [[Checkpoint.toJson]] and read with [[Checkpoint.fromJson]], which migrates older
 * `formatVersion`s and refuses newer ones.
 */
final case class Checkpoint private (
  formatVersion: Int,
  id: String,
  parent: Option[String],
  threadId: String,
  runId: String,
  status: CheckpointStatus,
  createdAt: Instant,
  snapshot: GraphSnapshot,
  /** The tenant the thread belongs to; checked at admission. */
  tenantId: Option[String] = None,
  /** The token of the claim whose run wrote this checkpoint. */
  fencingToken: Option[FencingToken] = None
):
  def withFormatVersion(v: Int): Checkpoint                 = copy(formatVersion = v)
  def withId(i: String): Checkpoint                         = copy(id = i)
  def withParent(p: Option[String]): Checkpoint             = copy(parent = p)
  def withThreadId(t: String): Checkpoint                   = copy(threadId = t)
  def withRunId(r: String): Checkpoint                      = copy(runId = r)
  def withStatus(s: CheckpointStatus): Checkpoint           = copy(status = s)
  def withCreatedAt(at: Instant): Checkpoint                = copy(createdAt = at)
  def withSnapshot(s: GraphSnapshot): Checkpoint            = copy(snapshot = s)
  def withTenantId(t: String): Checkpoint                   = copy(tenantId = Some(t))
  def withTenantId(t: Option[String]): Checkpoint           = copy(tenantId = t)
  def withFencingToken(t: FencingToken): Checkpoint         = copy(fencingToken = Some(t))
  def withFencingToken(t: Option[FencingToken]): Checkpoint = copy(fencingToken = t)

object Checkpoint:

  def apply(
    formatVersion: Int,
    id: String,
    parent: Option[String],
    threadId: String,
    runId: String,
    status: CheckpointStatus,
    createdAt: Instant,
    snapshot: GraphSnapshot,
    tenantId: Option[String] = None,
    fencingToken: Option[FencingToken] = None
  ): Checkpoint =
    new Checkpoint(formatVersion, id, parent, threadId, runId, status, createdAt, snapshot, tenantId, fencingToken)

  /** The format this build writes. */
  val CurrentFormat: Int = 5

  /**
   * Migrations of the checkpoint format itself, keyed by the version they upgrade from.
   * 1 -> 2: suspension (#1269) added parked continuations, the paused flag and task origins.
   * 2 -> 3: the tenant (#1277) became part of a thread's identity; earlier checkpoints have none.
   * 3 -> 4: the terminal `Failed` status (#1328); nothing to rewrite, but a build that predates it refuses format 4
   * rather than misread the status.
   * 4 -> 5: run-claim fencing (#1700) records the writing claim's token; earlier checkpoints have none.
   */
  private val formatVersion: SchemaVersion =
    SchemaVersion(5)(1 -> addSuspension, 2 -> addTenant, 3 -> addFailedStatus, 4 -> addFencingToken)

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

  private def addTenant(json: ujson.Value): Result[ujson.Value] =
    Try {
      val upgraded = ujson.copy(json)
      upgraded("tenantId") = upickle.default.writeJs(Option.empty[String])
      upgraded("formatVersion") = 3
      upgraded
    }.toResult

  private def addFailedStatus(json: ujson.Value): Result[ujson.Value] =
    Try {
      val upgraded = ujson.copy(json)
      upgraded("formatVersion") = 4
      upgraded
    }.toResult

  private def addFencingToken(json: ujson.Value): Result[ujson.Value] =
    Try {
      val upgraded = ujson.copy(json)
      upgraded("fencingToken") = upickle.default.writeJs(Option.empty[Long])
      upgraded("formatVersion") = 5
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
 * One atomic write to a thread, fenced by the writing run's claim. The checkpointer applies all of it
 * or none of it:
 *
 *  - `token` must be the [[FencingToken]] of the thread's current claim ([[GraphError.StaleClaim]]
 *    otherwise, checked first): a writer that lost its claim records nothing.
 *  - `checkpoint`, if present, becomes the thread's latest, provided its `parent` is the current
 *    latest - the thread version the writer expects ([[GraphError.CheckpointConflict]]); pending
 *    writes recorded against the old checkpoint are dropped.
 *  - `pendingWrites` are recorded against the (resulting) latest checkpoint, and must name it - the
 *    version they expect ([[GraphError.InvalidCommit]]).
 *  - `events` are appended to the thread's durable log, each given the next sequence number.
 */
final case class Commit private (
  token: FencingToken,
  checkpoint: Option[Checkpoint],
  pendingWrites: Vector[PendingWrite],
  events: Vector[EventDraft]
):
  def withToken(t: FencingToken): Commit                 = copy(token = t)
  def withCheckpoint(c: Checkpoint): Commit              = copy(checkpoint = Some(c))
  def withCheckpoint(c: Option[Checkpoint]): Commit      = copy(checkpoint = c)
  def withPendingWrites(w: Vector[PendingWrite]): Commit = copy(pendingWrites = w)
  def withEvents(e: Vector[EventDraft]): Commit          = copy(events = e)

object Commit:
  def apply(
    token: FencingToken,
    checkpoint: Option[Checkpoint] = None,
    pendingWrites: Vector[PendingWrite] = Vector.empty,
    events: Vector[EventDraft] = Vector.empty
  ): Commit = new Commit(token, checkpoint, pendingWrites, events)
