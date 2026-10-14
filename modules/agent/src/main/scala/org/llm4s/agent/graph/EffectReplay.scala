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
 * `operation` stays readable so that refusals can name it. `scope` names the thread that performs the
 * effect, without revealing it (the SHA-256 of its id): a `Transactional` target fences its keys per
 * scope, because a thread's claim tokens only grow ([[AppliedKeys]]).
 */
@Experimental
final case class EffectKey private (value: String, operation: String, scope: String):
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
    new EffectKey(KeyDigest.of(parts ++ discriminator), operation, scopeOf(threadId))

  /** The scope of every effect key of thread `threadId`. */
  private[graph] def scopeOf(threadId: ThreadId): String = KeyDigest.of(Seq("llm4s-effect-scope-v1", threadId.value))

  /** A key read back from a stored record. */
  private[graph] def stored(value: String, operation: String, scope: String): EffectKey =
    new EffectKey(value, operation, scope)

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
 * A `Transactional` target's applied-keys table, fenced by the claim token of the run acting on it
 * (design §4.24). The target keeps, for each key `scope` (one per thread), the newest
 * [[FencingToken]] any operation on that scope has carried, and every operation on the scope - the
 * effect given to [[ReplayGuard.transactional]] and [[forget]] - does this in one transaction:
 *
 *  - a token older than the scope's newest is refused with a `Left`, changing nothing: a run that lost
 *    its claim can neither apply, nor read back, nor forget anything after its successor touched the
 *    target, whatever it saw in the thread's store before it was taken over;
 *  - otherwise the scope's newest token becomes this one, and the operation goes ahead. The effect
 *    records `(key, outcome)` with the effect, or, for a key already applied, returns the recorded
 *    outcome and writes nothing.
 *
 * The newest token per scope is kept for as long as the target keeps data of that thread: a small
 * record per thread, as the thread store's tombstones are. [[ReplayGuard.transactional]] trusts an
 * applied key only while the thread's store holds the effect's begun record, and forgets it once the
 * outcome is recorded there.
 */
@Experimental
trait AppliedKeys:

  /**
   * Removes `key` from the applied-keys table, fenced by `token` as described above: `Left` if `token`
   * is older than the newest token seen for `key.scope`; `Right(())` if the key is not there.
   */
  def forget(key: EffectKey, token: FencingToken): Result[Unit]

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
      "scope"     -> record.key.scope,
      "taskId"    -> record.taskId.value,
      // copied out, and copied in by fromJson: ujson values are mutable, so no caller shares a stored one
      "intent" -> ujson.copy(EffectBody.intentJson(record.body.intent))
    )
    record.body match
      case EffectBody.Begun(_)           => ()
      case EffectBody.Completed(_, out)  => json("outcome") = ujson.Obj("value" -> ujson.copy(out))
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
          val key = EffectKey.stored(json("key").str, json("operation").str, json("scope").str)
          EffectBody.intentFrom(json("intent")).map { intent =>
            val body = json.obj.get("outcome") match
              case None => EffectBody.Begun(intent)
              case Some(outcome) =>
                outcome.obj.get("value") match
                  case Some(value) => EffectBody.Completed(intent, ujson.copy(value))
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
          ujson.copy(json("presumed"))
        )
      )
    case other => Left(ValidationError("effectRecord", s"unknown effect support '$other'"))

/** Where a task's guards read and write effect records: the thread's store in a runtime, memory outside one. */
private[graph] trait EffectJournal:
  def lookup(key: EffectKey): Result[Option[EffectRecord]]
  def write(record: EffectRecord): Result[Unit]

private[graph] object EffectJournal:

  /** Records in memory: an in-memory execution's, carried across its supersteps and continuations. */
  final class Local private[EffectJournal] () extends EffectJournal:
    private val records                                      = new ConcurrentHashMap[String, EffectRecord]()
    def lookup(key: EffectKey): Result[Option[EffectRecord]] = Right(Option(records.get(key.value)))
    def write(record: EffectRecord): Result[Unit]            = Right(records.put(record.key.value, record): Unit)

    /** Drops the records of every task not in `live`: they can no longer run. */
    def keepOnly(live: Set[String]): Unit = records.values.removeIf(r => !live.contains(r.taskId.value)): Unit

  def local(): Local = new Local()

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
   *
   * The effect must be a compare-and-set against `before`: atomically at the target - under a lock
   * every writer of the target takes, an OS file lock across processes - it checks that the digest is
   * still `before`, and only then writes; otherwise it writes nothing and returns
   * [[GraphError.EffectReplayRefused]]. That is what fences a run that lost its claim with its effect
   * already under way: whichever of it and its successor writes second finds the digest moved and
   * changes nothing. A target that cannot compare and set atomically is not `Verifiable`.
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
   * Performs `effect` on a target that records `(key, outcome)` in its own transaction, fenced by the
   * run's claim token ([[AppliedKeys]]): `effect` is given that token, and must refuse it, changing
   * nothing, if it is older than the newest the target has seen for `key.scope`. A completed effect
   * replays its stored outcome; a begun one runs `effect` again, which the target answers with its
   * recorded outcome if the first attempt applied. The target's applied key lives only until the
   * outcome is recorded in the thread's store: a key with no begun record is stale and is forgotten,
   * fenced like everything else, before the effect begins. Outside a runtime the token is `0`.
   */
  def transactional[A: ReadWriter](
    context: RunContext,
    key: EffectKey,
    applied: AppliedKeys,
    maxOutcomeBytes: Int = DefaultMaxOutcomeBytes
  )(effect: FencingToken => Result[A]): Result[A] =
    val journal = context.journal
    val token   = context.position.fencingToken.getOrElse(FencingToken(0L))
    def apply(owner: TaskId): Result[A] =
      for
        _       <- notCancelled(context, key)
        outcome <- caught(effect(token))
        _       <- complete(journal, key, owner, EffectIntent.Transactional, outcome, maxOutcomeBytes)
        _       <- caught(applied.forget(key, token))
      yield outcome
    journal.lookup(key).flatMap {
      case None =>
        val owner = context.effectOwner
        for
          _       <- caught(applied.forget(key, token))
          _       <- journal.write(new EffectRecord(key, owner, EffectBody.Begun(EffectIntent.Transactional)))
          outcome <- apply(owner)
        yield outcome
      case Some(record) =>
        record.body match
          case EffectBody.Begun(EffectIntent.Transactional) => apply(record.taskId)
          case EffectBody.Begun(other) => Left(mismatch(key, other.support, ReplaySupport.Transactional))
          case done =>
            caught(applied.forget(key, token)).flatMap(_ => stored[A](key, done, ReplaySupport.Transactional))
    }

  /**
   * Performs a `Verifiable` effect. `plan` reads the target once and returns its digests, the outcome
   * it will report and the effect; it runs only when the effect must run, so a check against the
   * current state (an edit's "`old` not found", a quota) belongs inside it. `probe` reads the target's
   * current digest; it runs only on a replay of an effect that began and did not record its outcome:
   * matching `before`, it did not land, and it is planned and run again; matching `after`, it landed and
   * `presumed` is returned; anything else is [[GraphError.EffectReplayRefused]] naming the conflict.
   * `before` is checked first, so a plan whose two digests are equal (a write of what is already
   * there) is run again rather than presumed: the probe cannot tell whether it ran, and running it
   * again changes no digest.
   */
  def verifiable[A: ReadWriter](context: RunContext, key: EffectKey, maxOutcomeBytes: Int = DefaultMaxOutcomeBytes)(
    probe: => Result[StateCheck]
  )(plan: => Result[Plan[A]]): Result[A] =
    val journal = context.journal
    // `baseline`: on a replay, the `before` the effect began against. A plan that read anything else saw a
    // change made after the probe, which the effect must not overwrite
    def fresh(owner: TaskId, baseline: Option[StateCheck]): Result[A] =
      caught(plan)
        .flatMap(planned => caught(Right(ujson.copy(upickle.default.writeJs(planned.presumed)))).map(planned -> _))
        .flatMap { (planned, presumed) =>
          val intent = EffectIntent.Verifiable(planned.before, planned.after, presumed)
          for
            _ <- baseline.filter(_ != planned.before).fold[Result[Unit]](Right(())) { expected =>
              Left(
                GraphError.EffectReplayRefused(
                  key.operation,
                  s"its target changed after the effect began: it is now ${planned.before.value}, not " +
                    s"${expected.value} (not applied)"
                )
              )
            }
            _       <- withinCap(key, "its presumed outcome", presumed, maxOutcomeBytes)
            _       <- journal.write(new EffectRecord(key, owner, EffectBody.Begun(intent)))
            _       <- notCancelled(context, key)
            outcome <- caught(planned.effect())
            _       <- complete(journal, key, owner, intent, outcome, maxOutcomeBytes)
          yield outcome
        }
    journal.lookup(key).flatMap {
      case None => fresh(context.effectOwner, None)
      case Some(record) =>
        record.body match
          case EffectBody.Begun(intent @ EffectIntent.Verifiable(before, after, presumed)) =>
            caught(probe).flatMap { current =>
              // `before` first: when the two digests are equal the probe cannot tell whether the effect
              // ran, and running it again - a compare-and-set that changes no digest - is what is safe
              if current == before then fresh(record.taskId, Some(before))
              else if current == after then
                decode[A](key, presumed).flatMap { outcome =>
                  journal
                    .write(new EffectRecord(key, record.taskId, EffectBody.Completed(intent, presumed)))
                    .map(_ => outcome)
                }
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
        outcome <- caught(effect)
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
    // an outcome that does not encode is a failure after the effect: the record stays begun, the task fails
    caught(Right(ujson.copy(upickle.default.writeJs(outcome)))).flatMap { json =>
      val length = encodedLength(json)
      val body =
        if length > maxOutcomeBytes then EffectBody.Omitted(intent, length) else EffectBody.Completed(intent, json)
      journal.write(new EffectRecord(key, owner, body))
    }

  /**
   * `body` - the caller's effect, plan, probe or codec, or a target's `forget` - with anything it throws as a
   * `Left`: never a refusal, so the task fails, commits nothing, and keeps its evidence for `recover`. A
   * throw must not escape the guard, where a tool loop would turn it into an error result the task commits.
   */
  private def caught[A](body: => Result[A]): Result[A] = Try(body).toResult.flatten

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
    Try(upickle.default.read[A](ujson.copy(json))).toResult.left.map(e =>
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
