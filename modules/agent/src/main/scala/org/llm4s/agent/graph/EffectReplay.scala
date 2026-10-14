package org.llm4s.agent.graph

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import scala.util.Try

/** SHA-256 over length-prefixed parts, as 64 lowercase hex characters: no two part lists share a digest input. */
private[graph] object KeyDigest:
  def of(parts: Seq[String]): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    parts.foreach { part =>
      val bytes = part.getBytes(StandardCharsets.UTF_8)
      digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array())
      digest.update(bytes)
    }
    hex(digest.digest())

  def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

/**
 * One external effect, the identity its replay evidence is kept under (design §4.24): the SHA-256 of
 * the thread that performs it, a base key - the tool call's [[IdempotencyKey]] or the task's
 * [[RunContext.taskKey]] - the `operation` (the kind of effect, such as `file.edit`) and a
 * discriminator naming what it touches (a path, sorted source ids). Effects of one task differ in
 * operation or discriminator, so they never suppress one another; a fork's re-run of its source's
 * call is a new effect, because the thread is part of the key.
 *
 * Built only by `RunContext.effectKey` and `ToolContext.effectKey`. `value` is the identity;
 * `operation` stays readable so that refusals can name it.
 */
@Experimental
final case class EffectKey private (value: String, operation: String):
  override def toString: String = s"EffectKey($operation, $value)"

object EffectKey:

  /** The key of `operation` on `discriminator`, by thread `threadId`, under `base`. */
  private[graph] def derive(
    threadId: ThreadId,
    base: IdempotencyKey,
    operation: String,
    discriminator: Seq[String]
  ): EffectKey =
    val parts = Seq("llm4s-effect-v1", threadId.value, base.value, operation, discriminator.size.toString)
    new EffectKey(KeyDigest.of(parts ++ discriminator), operation)

  /** A key read back from a stored record. */
  private[graph] def stored(value: String, operation: String): EffectKey = new EffectKey(value, operation)

/** How a target lets the harness replay an effect on it; every external target declares one (design §4.24). */
@Experimental
enum ReplaySupport:

  /** The target records the effect's key with its outcome in the same transaction as the effect: [[ReplayGuard.transactional]]. */
  case Transactional

  /** The target can read a digest of what the effect touches, so a replay can tell whether it landed: [[ReplayGuard.verifiable]]. */
  case Verifiable

  /** Nothing can be checked: a replay of an effect that may have run is refused, unless it is idempotent: [[ReplayGuard.unverifiable]]. */
  case Unverifiable

/** A digest of what a `Verifiable` effect touches, as its target reads it: compared, never interpreted. */
opaque type StateCheck = String

@Experimental
object StateCheck:

  /** The target does not exist (a missing file). */
  val absent: StateCheck = "absent"

  /** The SHA-256 of `bytes`. */
  def of(bytes: Array[Byte]): StateCheck =
    "sha256:" + KeyDigest.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

  /** The SHA-256 of `text` as UTF-8. */
  def of(text: String): StateCheck = of(text.getBytes(StandardCharsets.UTF_8))

  extension (check: StateCheck) def value: String = check

  given ReadWriter[StateCheck] =
    upickle.default.ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)

/**
 * A `Transactional` target's applied-keys table: the target records `(key, outcome)` in the same
 * transaction as the effect, and, given a key it has applied, returns the recorded outcome and writes
 * nothing. [[ReplayGuard.transactional]] trusts an applied key only while the thread's store holds
 * the effect's begun record, and forgets it once the outcome is recorded there.
 */
@Experimental
trait AppliedKeys:

  /** Removes `key` from the applied-keys table; `Right(())` if it is not there. */
  def forget(key: EffectKey): Result[Unit]

/**
 * The replay evidence of one effect, as the thread's [[Checkpointer]] keeps it: begun (an intent) or
 * completed (an outcome). `taskId` is the task the evidence belongs to - for a continuation, the task
 * it stands in for - which decides when a commit releases it ([[Commit.releasesEffect]]). A store
 * persists [[EffectRecord.toJson]] and reads it back with [[EffectRecord.fromJson]]; the body is
 * the guard's.
 */
@Experimental
final class EffectRecord private[graph] (val key: EffectKey, val taskId: TaskId, private[graph] val body: EffectBody):

  /** Whether the effect's outcome is recorded. */
  def completed: Boolean = body match
    case _: EffectBody.Begun => false
    case _                   => true

  override def equals(other: Any): Boolean = other match
    case that: EffectRecord => EffectRecord.toJson(this) == EffectRecord.toJson(that)
    case _                  => false

  override def hashCode: Int = EffectRecord.toJson(this).hashCode

  override def toString: String = s"EffectRecord(${ujson.write(EffectRecord.toJson(this))})"

object EffectRecord:

  /** The format this build writes; [[fromJson]] refuses a newer one. */
  val CurrentFormat: Int = 1

  def toJson(record: EffectRecord): ujson.Value =
    val json = ujson.Obj(
      "format"    -> CurrentFormat,
      "key"       -> record.key.value,
      "operation" -> record.key.operation,
      "taskId"    -> record.taskId.value,
      "intent"    -> EffectBody.intentJson(record.body.intent)
    )
    record.body match
      case EffectBody.Begun(_)           => ()
      case EffectBody.Completed(_, out)  => json("outcome") = ujson.Obj("value" -> out)
      case EffectBody.Omitted(_, length) => json("outcome") = ujson.Obj("omittedBytes" -> ujson.Num(length.toDouble))
    json

  def fromJson(json: ujson.Value): Result[EffectRecord] =
    Try(json("format").num.toInt).toResult.flatMap { format =>
      if format > CurrentFormat then
        Left(
          ValidationError(
            "effectRecord",
            s"effect record format $format is newer than the $CurrentFormat this build reads"
          )
        )
      else
        Try {
          val key = EffectKey.stored(json("key").str, json("operation").str)
          EffectBody.intentFrom(json("intent")).map { intent =>
            val body = json.obj.get("outcome") match
              case None => EffectBody.Begun(intent)
              case Some(outcome) =>
                outcome.obj.get("value") match
                  case Some(value) => EffectBody.Completed(intent, value)
                  case None        => EffectBody.Omitted(intent, outcome("omittedBytes").num.toLong)
            new EffectRecord(key, TaskId(json("taskId").str), body)
          }
        }.toResult.flatten
    }

/** What the guard keeps for one effect: the intent, and once completed, the outcome. */
private[graph] enum EffectBody:
  case Begun(intent: EffectIntent)
  case Completed(intent: EffectIntent, outcome: ujson.Value)
  case Omitted(intent: EffectIntent, length: Long)

  def intent: EffectIntent

private[graph] enum EffectIntent:
  case Transactional
  case Verifiable(before: StateCheck, after: StateCheck, presumed: ujson.Value)
  case Unverifiable

  def support: ReplaySupport = this match
    case Transactional             => ReplaySupport.Transactional
    case _: Verifiable             => ReplaySupport.Verifiable
    case EffectIntent.Unverifiable => ReplaySupport.Unverifiable

private[graph] object EffectBody:
  def intentJson(intent: EffectIntent): ujson.Value = intent match
    case EffectIntent.Transactional => ujson.Obj("support" -> "transactional")
    case EffectIntent.Unverifiable  => ujson.Obj("support" -> "unverifiable")
    case EffectIntent.Verifiable(before, after, presumed) =>
      ujson.Obj("support" -> "verifiable", "before" -> before.value, "after" -> after.value, "presumed" -> presumed)

  def intentFrom(json: ujson.Value): Result[EffectIntent] = json("support").str match
    case "transactional" => Right(EffectIntent.Transactional)
    case "unverifiable"  => Right(EffectIntent.Unverifiable)
    case "verifiable" =>
      Right(
        EffectIntent.Verifiable(
          upickle.default.read[StateCheck](json("before")),
          upickle.default.read[StateCheck](json("after")),
          json("presumed")
        )
      )
    case other => Left(ValidationError("effectRecord", s"unknown effect support '$other'"))

/** Where a task's guards read and write effect records: the thread's store in a runtime, memory outside one. */
private[graph] trait EffectJournal:
  def lookup(key: EffectKey): Result[Option[EffectRecord]]
  def write(record: EffectRecord): Result[Unit]

private[graph] object EffectJournal:

  /** Records kept for one `RunContext`'s lifetime: retries of its node replay, nothing else does. */
  def local(): EffectJournal = new EffectJournal:
    private val records                                      = new ConcurrentHashMap[String, EffectRecord]()
    def lookup(key: EffectKey): Result[Option[EffectRecord]] = Right(Option(records.get(key.value)))
    def write(record: EffectRecord): Result[Unit]            = Right(records.put(record.key.value, record): Unit)

/**
 * The only way the harness performs an effect outside thread state (design §4.24, runtime rule 4). A
 * task can run more than once - `recover`, a retry, a re-run after approval or an answer - so each
 * effect keeps evidence in the thread's store under its [[EffectKey]]: an intent before the effect
 * and the outcome after it. A completed effect replays its stored outcome, decoded with `A`'s codec;
 * what a replay of an intent alone does depends on the target's [[ReplaySupport]], one entry point
 * each. The evidence lives exactly as long as the task can run again: the commit that ends that
 * releases it ([[Commit.releasesEffect]]), never age.
 *
 * Every `Left` is either [[GraphError.EffectReplayRefused]] - a refusal the model should see, which a
 * tool maps to `ToolOutcome.Error` - or a failure that leaves the outcome unknown or unrecorded (the
 * store, or the effect itself), which a tool maps to `ToolOutcome.Fatal` and a hook to a failed node,
 * so the task commits nothing and `recover` replays it from the evidence. An `effect` returns `Left`
 * only when it did not take effect, or cannot tell: the guard never stores it.
 *
 * Intents and outcomes are capped at `maxOutcomeBytes` encoded. An oversized intent refuses the effect
 * before it runs; an oversized outcome is recorded as omitted, and its replay is refused, never run
 * again.
 */
@Experimental
object ReplayGuard:

  /** The default cap on one effect's encoded intent or outcome: 1 MiB. */
  val DefaultMaxOutcomeBytes: Int = 1 << 20

  /**
   * A `Verifiable` effect about to run: the target's digest `before` and `after` it, the outcome it
   * reports when it lands (`presumed`, returned by a replay that finds it landed), and the effect.
   */
  final class Plan[A] private (
    val before: StateCheck,
    val after: StateCheck,
    val presumed: A,
    val effect: () => Result[A]
  )

  object Plan:
    def apply[A](before: StateCheck, after: StateCheck, presumed: A)(effect: => Result[A]): Plan[A] =
      new Plan(before, after, presumed, () => effect)

  /**
   * Performs `effect` on a target that records `(key, outcome)` in its own transaction ([[AppliedKeys]]).
   * A completed effect replays its stored outcome; a begun one runs `effect` again, which the target
   * answers with its recorded outcome if the first attempt applied. The target's applied key lives only
   * until the outcome is recorded in the thread's store: a key with no begun record is stale and is
   * forgotten before the effect begins.
   */
  def transactional[A: ReadWriter](
    context: RunContext,
    key: EffectKey,
    applied: AppliedKeys,
    maxOutcomeBytes: Int = DefaultMaxOutcomeBytes
  )(effect: => Result[A]): Result[A] =
    val journal = context.journal
    def apply(owner: TaskId): Result[A] =
      for
        _       <- notCancelled(context, key)
        outcome <- effect
        _       <- complete(journal, key, owner, EffectIntent.Transactional, outcome, maxOutcomeBytes)
        _       <- applied.forget(key)
      yield outcome
    journal.lookup(key).flatMap {
      case None =>
        val owner = context.effectOwner
        for
          _       <- applied.forget(key)
          _       <- journal.write(new EffectRecord(key, owner, EffectBody.Begun(EffectIntent.Transactional)))
          outcome <- apply(owner)
        yield outcome
      case Some(record) =>
        record.body match
          case EffectBody.Begun(EffectIntent.Transactional) => apply(record.taskId)
          case EffectBody.Begun(other) => Left(mismatch(key, other.support, ReplaySupport.Transactional))
          case done => applied.forget(key).flatMap(_ => stored[A](key, done, ReplaySupport.Transactional))
    }

  /**
   * Performs a `Verifiable` effect. `plan` reads the target once and returns its digests, the outcome
   * it will report and the effect; it runs only when the effect must run, so a check against the
   * current state (an edit's "`old` not found", a quota) belongs inside it. `probe` reads the target's
   * current digest; it runs only on a replay of an effect that began and did not record its outcome:
   * matching `after`, the effect landed and `presumed` is returned; matching `before`, it did not, and
   * it is planned and run again; anything else is [[GraphError.EffectReplayRefused]] naming the conflict.
   */
  def verifiable[A: ReadWriter](context: RunContext, key: EffectKey, maxOutcomeBytes: Int = DefaultMaxOutcomeBytes)(
    probe: => Result[StateCheck]
  )(plan: => Result[Plan[A]]): Result[A] =
    val journal = context.journal
    def fresh(owner: TaskId): Result[A] =
      plan.flatMap { planned =>
        val presumed = upickle.default.writeJs(planned.presumed)
        val intent   = EffectIntent.Verifiable(planned.before, planned.after, presumed)
        for
          _       <- withinCap(key, "its presumed outcome", presumed, maxOutcomeBytes)
          _       <- journal.write(new EffectRecord(key, owner, EffectBody.Begun(intent)))
          _       <- notCancelled(context, key)
          outcome <- planned.effect()
          _       <- complete(journal, key, owner, intent, outcome, maxOutcomeBytes)
        yield outcome
      }
    journal.lookup(key).flatMap {
      case None => fresh(context.effectOwner)
      case Some(record) =>
        record.body match
          case EffectBody.Begun(intent @ EffectIntent.Verifiable(before, after, presumed)) =>
            probe.flatMap { current =>
              if current == after then
                decode[A](key, presumed).flatMap { outcome =>
                  journal
                    .write(new EffectRecord(key, record.taskId, EffectBody.Completed(intent, presumed)))
                    .map(_ => outcome)
                }
              else if current == before then fresh(record.taskId)
              else
                Left(
                  GraphError.EffectReplayRefused(
                    key.operation,
                    s"its target changed after the effect began: it is now ${current.value}, neither " +
                      s"${before.value} (not applied) nor ${after.value} (applied)"
                  )
                )
            }
          case EffectBody.Begun(other) => Left(mismatch(key, other.support, ReplaySupport.Verifiable))
          case done                    => stored[A](key, done, ReplaySupport.Verifiable)
    }

  /**
   * Performs an effect nothing can check. A completed effect replays its stored outcome; one that began
   * and recorded no outcome may have run, so its replay is [[GraphError.EffectReplayRefused]] - unless
   * `idempotent` (the tool's `ToolHints.idempotent`), when it runs again.
   */
  def unverifiable[A: ReadWriter](
    context: RunContext,
    key: EffectKey,
    idempotent: Boolean,
    maxOutcomeBytes: Int = DefaultMaxOutcomeBytes
  )(effect: => Result[A]): Result[A] =
    val journal = context.journal
    def fresh(owner: TaskId): Result[A] =
      for
        _       <- journal.write(new EffectRecord(key, owner, EffectBody.Begun(EffectIntent.Unverifiable)))
        _       <- notCancelled(context, key)
        outcome <- effect
        _       <- complete(journal, key, owner, EffectIntent.Unverifiable, outcome, maxOutcomeBytes)
      yield outcome
    journal.lookup(key).flatMap {
      case None => fresh(context.effectOwner)
      case Some(record) =>
        record.body match
          case EffectBody.Begun(EffectIntent.Unverifiable) =>
            if idempotent then fresh(record.taskId)
            else Left(GraphError.EffectReplayRefused(key.operation, "it may already have run; it is not run again"))
          case EffectBody.Begun(other) => Left(mismatch(key, other.support, ReplaySupport.Unverifiable))
          case done                    => stored[A](key, done, ReplaySupport.Unverifiable)
    }

  private def complete[A: ReadWriter](
    journal: EffectJournal,
    key: EffectKey,
    owner: TaskId,
    intent: EffectIntent,
    outcome: A,
    maxOutcomeBytes: Int
  ): Result[Unit] =
    val json   = upickle.default.writeJs(outcome)
    val length = encodedLength(json)
    val body =
      if length > maxOutcomeBytes then EffectBody.Omitted(intent, length) else EffectBody.Completed(intent, json)
    journal.write(new EffectRecord(key, owner, body))

  private def stored[A: ReadWriter](key: EffectKey, body: EffectBody, expected: ReplaySupport): Result[A] =
    if body.intent.support != expected then Left(mismatch(key, body.intent.support, expected))
    else
      body match
        case EffectBody.Completed(_, outcome) => decode[A](key, outcome)
        case EffectBody.Omitted(_, length) =>
          Left(
            GraphError.EffectReplayRefused(
              key.operation,
              s"it completed, but its outcome of $length bytes was over the cap and was not kept"
            )
          )
        case EffectBody.Begun(_) => Left(GraphError.EffectReplayRefused(key.operation, "it has no recorded outcome"))

  private def decode[A: ReadWriter](key: EffectKey, json: ujson.Value): Result[A] =
    Try(upickle.default.read[A](json)).toResult.left.map(e =>
      GraphError.EffectReplayRefused(key.operation, s"its stored outcome does not decode: ${e.message}")
    )

  private def withinCap(key: EffectKey, what: String, json: ujson.Value, cap: Int): Result[Unit] =
    val length = encodedLength(json)
    Either.cond(
      length <= cap,
      (),
      GraphError.EffectReplayRefused(key.operation, s"$what is $length bytes encoded, over the $cap-byte cap")
    )

  private def encodedLength(json: ujson.Value): Long = ujson.write(json).getBytes(StandardCharsets.UTF_8).length.toLong

  private def mismatch(key: EffectKey, recorded: ReplaySupport, expected: ReplaySupport): GraphError =
    GraphError.EffectReplayRefused(key.operation, s"it was begun as $recorded, and is now replayed as $expected")

  /** Whether the task may still start an effect it has recorded: a cancelled or claim-lost run must not. */
  private def notCancelled(context: RunContext, key: EffectKey): Result[Unit] =
    Either.cond(!context.isCancelled, (), CancelledError(s"effect '${key.operation}' before it ran"))
