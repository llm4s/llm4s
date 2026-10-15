package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ ReplayGuard, ReplaySupport }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.concurrent.duration.FiniteDuration

/** What a command did; each stream cut at the backend's `maxOutputBytes`, `truncated` when one was. */
@Experimental
final case class ExecResult private (exitCode: Int, stdout: String, stderr: String, truncated: Boolean)

object ExecResult:
  def apply(exitCode: Int, stdout: String, stderr: String, truncated: Boolean = false): ExecResult =
    new ExecResult(exitCode, stdout, stderr, truncated)

  given ReadWriter[ExecResult] =
    upickle.default
      .readwriter[ujson.Value]
      .bimap[ExecResult](
        r =>
          ujson.Obj(
            "exitCode"  -> r.exitCode,
            "stdout"    -> r.stdout,
            "stderr"    -> r.stderr,
            "truncated" -> r.truncated
          ),
        json => ExecResult(json("exitCode").num.toInt, json("stdout").str, json("stderr").str, json("truncated").bool)
      )

/**
 * What runs commands for a [[SandboxBackend]] (F2's workspace sandbox): it only runs. The backend wraps
 * every run in C4's guard, so a runner cannot run a command twice on replay by mistake.
 */
@Experimental
trait SandboxRunner:

  /** The locks a command run in `workdir` holds (F2: `workspace/<id>`). */
  def locks(workdir: VPath): Set[LockKey]

  /**
   * Runs `command` in `workdir`, for at most `timeout`, capturing at most `maxOutputBytes` of each of
   * stdout and stderr (bytes of the stream as a JSON string, as [[ExecResult]] is recorded) and
   * discarding the rest, with `truncated` set: the bound holds while the output is read, not after it is
   * all in memory (rule 12). The backend cuts anything over it again.
   */
  def run(
    command: String,
    workdir: VPath,
    timeout: FiniteDuration,
    maxOutputBytes: Int,
    ctx: FileContext
  ): Result[ExecResult]

/**
 * Where commands run. Its target is `Unverifiable` (C4): every `execute` runs through
 * `ReplayGuard.unverifiable` under `ctx.effectKey("sandbox.execute", command, workdir)`, never as
 * idempotent, so a completed command replays its stored [[ExecResult]] and one whose run may have begun
 * without recording its outcome is refused (`GraphError.EffectReplayRefused`, a tool error), never run
 * again. Each stream is cut, on a character boundary, so that its JSON encoding - what the guard records -
 * is at most `maxOutputBytes`, before the outcome is recorded; so a recorded `ExecResult` always fits C4's
 * record, whatever the output's characters (rule 12).
 * A command's effects stay when a Block retracts its turn (rule 5).
 */
@Experimental
final class SandboxBackend private (
  val maxOutputBytes: Int,
  lockOf: VPath => Set[LockKey],
  resolve: FileContext => Result[SandboxRunner]
):

  /** C4's class for this target. */
  val replaySupport: ReplaySupport = ReplaySupport.Unverifiable

  def locks(workdir: VPath): Set[LockKey] = lockOf(workdir)

  /**
   * Resolution is a preflight: a runner that cannot be resolved has run nothing, so the call fails before
   * the guard records an intent, and a retry runs it afresh rather than being refused as ambiguous. A
   * completed command whose runner cannot be resolved now fails the same way, until it can be.
   */
  def execute(command: String, workdir: VPath, timeout: FiniteDuration, ctx: FileContext): Result[ExecResult] =
    // bounded before the effect key digests it or a runner sees it
    (if command.length.toLong > SandboxBackend.MaxCommandBytes || Text.utf8Bytes(
         command
       ) > SandboxBackend.MaxCommandBytes
     then
       Left(
         BackendRefused(
           "execute",
           Text.quoted(command),
           s"the command is longer than ${SandboxBackend.MaxCommandBytes} bytes"
         )
       )
     else Right(())).flatMap(_ => resolve(ctx)).flatMap(covered(workdir)).flatMap { runner =>
      val key = ctx.effectKey("sandbox.execute", command, workdir.value)
      ReplayGuard.unverifiable[ExecResult](ctx.run, key, idempotent = false)(
        runner.run(command, workdir, timeout, maxOutputBytes, ctx).map(bounded)
      )
    }

  /**
   * `runner`, if the locks this backend declares for `workdir` cover every lock the runner needs there;
   * otherwise a failure before the guard records anything. A resolved backend declares its locks before
   * any runner is chosen, so a runner needing a lock they do not cover could run beside another command
   * on its workspace, unordered.
   */
  private def covered(workdir: VPath)(runner: SandboxRunner): Result[SandboxRunner] =
    val declared = lockOf(workdir)
    val missing =
      runner.locks(workdir).filterNot(needed => declared.exists(d => needed.segments.startsWith(d.segments)))
    if missing.isEmpty then Right(runner)
    else
      Left(
        ValidationError(
          "locks",
          s"the sandbox runner for ${workdir.value} needs ${missing.map(_.segments.mkString("/")).mkString(", ")}, " +
            "which the backend's declared locks do not cover"
        )
      )

  private def bounded(result: ExecResult): ExecResult =
    val (out, cutOut) = Text.cutEncoded(result.stdout, maxOutputBytes.toLong)
    val (err, cutErr) = Text.cutEncoded(result.stderr, maxOutputBytes.toLong)
    ExecResult(result.exitCode, out, err, result.truncated || cutOut || cutErr)

object SandboxBackend:

  /** 32 KiB: the default bound on each stream. */
  val DefaultMaxOutputBytes: Int = 32 * 1024

  /** The most `maxOutputBytes` may be, so an `ExecResult` always fits a replay record. */
  val MaxOutputBytes: Int = 256 * 1024

  /** The longest command `execute` takes, in UTF-8 bytes. */
  val MaxCommandBytes: Int = 64 * 1024

  /** A backend over one runner: one tenant's sandbox. */
  def apply(runner: SandboxRunner, maxOutputBytes: Int = DefaultMaxOutputBytes): SandboxBackend =
    checked(maxOutputBytes)
    new SandboxBackend(maxOutputBytes, runner.locks, _ => Right(runner))

  /**
   * A backend whose runner `resolve` picks per call, typically by `ctx.run.config.tenantId` (rule 8).
   * Locks are computed before any context exists, so they are given here, and must cover the locks of
   * every runner `resolve` can pick.
   */
  def resolved(locks: VPath => Set[LockKey], maxOutputBytes: Int = DefaultMaxOutputBytes)(
    resolve: FileContext => Result[SandboxRunner]
  ): SandboxBackend =
    checked(maxOutputBytes)
    new SandboxBackend(maxOutputBytes, locks, resolve)

  private def checked(maxOutputBytes: Int): Unit =
    require(
      maxOutputBytes > 0 && maxOutputBytes <= MaxOutputBytes,
      s"maxOutputBytes must be in 1..$MaxOutputBytes, was $maxOutputBytes"
    )
