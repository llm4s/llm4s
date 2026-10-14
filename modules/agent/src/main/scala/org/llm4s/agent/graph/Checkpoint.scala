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
  val CurrentFormat: Int = 7

  /**
   * Migrations of the checkpoint format itself, keyed by the version they upgrade from.
   * 1 -> 2: suspension (#1269) added parked continuations, the paused flag and task origins.
   * 2 -> 3: the tenant (#1277) became part of a thread's identity; earlier checkpoints have none.
   * 3 -> 4: the terminal `Failed` status (#1328); nothing to rewrite, but a build that predates it refuses format 4
   * rather than misread the status.
   * 4 -> 5: run-claim fencing (#1700) records the writing claim's token; earlier checkpoints have none.
   * 5 -> 6: static breakpoints (#1704) park held tasks and mark their continuations; nothing to rewrite, as
   * the new fields default to none, but a build that predates them refuses format 6 rather than run a held
   * task's continuation as an ordinary task.
   * 6 -> 7: task lineages (#1921 follow-up) give each pending task and continuation origin a digest of what
   * scheduled it; nothing to rewrite, as tasks written before read with an empty lineage, but a build that
   * predates them refuses format 7 rather than key a task's effects without its lineage.
   */
  private val formatVersion: SchemaVersion =
    SchemaVersion(7)(
      1 -> addSuspension,
      2 -> addTenant,
      3 -> addFailedStatus,
      4 -> addFencingToken,
      5 -> addBreakpoints,
      6 -> addTaskLineages
    )

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

  private def addBreakpoints(json: ujson.Value): Result[ujson.Value] =
    Try {
      val upgraded = ujson.copy(json)
      upgraded("formatVersion") = 6
      upgraded
    }.toResult

  private def addTaskLineages(json: ujson.Value): Result[ujson.Value] =
    Try {
      val upgraded = ujson.copy(json)
      upgraded("formatVersion") = 7
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
 *    latest - the thread version the writer expects ([[GraphError.CheckpointConflict]]) - and its id
 *    is not already in the thread's history ([[GraphError.InvalidCommit]]); the previous latest stays
 *    in the history, and the pending writes recorded against it are dropped.
 *  - `pendingWrites` are recorded against the (resulting) latest checkpoint, and must name it - the
 *    version they expect ([[GraphError.InvalidCommit]]).
 *  - `events` are appended to the thread's durable log, each given the next sequence number.
 *  - `retractTurn`, which needs a `checkpoint` ([[GraphError.InvalidCommit]] otherwise), first removes
 *    from the history every checkpoint after the thread's latest settled one - the newest whose status
 *    is `Completed` or `Failed`, or all of them if there is none - so that nothing of a turn a
 *    guardrail blocked stays readable. The runtime sets it on the `Failed` checkpoint that closes a run
 *    a node blocked ([[NodeResult.Block]]).
 *  - `effects` are upserted by key into the thread's effect records ([[Checkpointer.effect]]), each
 *    remembered with its task's id and lineage. Before they are, a commit with a `checkpoint` releases every
 *    record for which [[releasesEffect]] holds, so replay evidence lives exactly as long as its task can
 *    run again (design §4.24). The runtime writes each record of a [[ReplayGuard]] as a commit of its
 *    own, with no checkpoint, pending write or event.
 */
final case class Commit private (
  token: FencingToken,
  checkpoint: Option[Checkpoint],
  pendingWrites: Vector[PendingWrite],
  events: Vector[EventDraft],
  retractTurn: Boolean,
  effects: Vector[EffectRecord]
):
  def withToken(t: FencingToken): Commit                 = copy(token = t)
  def withCheckpoint(c: Checkpoint): Commit              = copy(checkpoint = Some(c))
  def withCheckpoint(c: Option[Checkpoint]): Commit      = copy(checkpoint = c)
  def withPendingWrites(w: Vector[PendingWrite]): Commit = copy(pendingWrites = w)
  def withEvents(e: Vector[EventDraft]): Commit          = copy(events = e)
  def withRetractTurn(r: Boolean): Commit                = copy(retractTurn = r)
  def withEffects(e: Vector[EffectRecord]): Commit       = copy(effects = e)

  /**
   * The tasks the new checkpoint can still run. A settled checkpoint (`Completed`, or `Failed`, which a Block
   * writes) runs none, whatever its snapshot lists: `recover` and `resume` refuse it, so a sibling parked in
   * the superstep a Block closed can never run, and its records go too.
   */
  private lazy val live: Set[(String, String)] = checkpoint.fold(Set.empty[(String, String)]) { c =>
    if c.status == CheckpointStatus.Completed || c.status == CheckpointStatus.Failed then Set.empty
    else c.snapshot.liveTasks
  }

  /**
   * Whether this commit releases an effect record of the task `taskId` of lineage `lineage` ([[EffectRecord]]):
   * whether that task is provably settled, so no run can look the record up again (design §4.24). Only a commit
   * with a checkpoint releases anything. It releases a record whose task - id and lineage - is not live in the
   * new checkpoint ([[GraphSnapshot.liveTasks]]; a settled checkpoint runs nothing) and was scheduled no later
   * than its superstep. That checkpoint is durable once committed, so the superstep that scheduled the task
   * never runs again: the task finished, or the durable plan scheduled other work under its id, and nothing
   * can plan it again. A task scheduled later is kept, whoever recorded it:
   *  - this run's, running ahead of a commit that lands late (`Async`);
   *  - a run's that a takeover fenced out, scheduled after its last durable checkpoint (`Async`, `OnExit`): the
   *    successor plans that work again, and from the same results it is the same task - the same lineage and
   *    keys - so the first plan's evidence answers for it, even while the stale run's effect is still under way.
   * A store applies this to every record of the thread inside the commit.
   */
  def releasesEffect(taskId: TaskId, lineage: String): Boolean =
    checkpoint.exists { c =>
      !live.contains(taskId.value -> lineage) && Commit.scheduledAt(taskId) <= c.snapshot.superstep
    }

object Commit:
  def apply(
    token: FencingToken,
    checkpoint: Option[Checkpoint] = None,
    pendingWrites: Vector[PendingWrite] = Vector.empty,
    events: Vector[EventDraft] = Vector.empty,
    retractTurn: Boolean = false,
    effects: Vector[EffectRecord] = Vector.empty
  ): Commit = new Commit(token, checkpoint, pendingWrites, events, retractTurn, effects)

  /** The superstep that scheduled a task: its id is `"<superstep>.<ordinal>"`; another id reads as long past. */
  private def scheduledAt(taskId: TaskId): Int =
    taskId.value.takeWhile(_ != '.').toIntOption.getOrElse(Int.MinValue)
