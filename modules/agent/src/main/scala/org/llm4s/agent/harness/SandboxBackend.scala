package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ ReplayGuard, ReplaySupport }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets.UTF_8
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

  /** Runs `command` in `workdir`, for at most `timeout`. */
  def run(command: String, workdir: VPath, timeout: FiniteDuration, ctx: FileContext): Result[ExecResult]

/**
 * Where commands run. Its target is `Unverifiable` (C4): every `execute` runs through
 * `ReplayGuard.unverifiable` under `ctx.effectKey("sandbox.execute", command, workdir)`, never as
 * idempotent, so a completed command replays its stored [[ExecResult]] and one whose run may have begun
 * without recording its outcome is refused (`GraphError.EffectReplayRefused`, a tool error), never run
 * again. Each stream is cut at `maxOutputBytes` UTF-8 bytes before the outcome is recorded (rule 12).
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

  def execute(command: String, workdir: VPath, timeout: FiniteDuration, ctx: FileContext): Result[ExecResult] =
    val key = ctx.effectKey("sandbox.execute", command, workdir.value)
    ReplayGuard.unverifiable[ExecResult](ctx.run, key, idempotent = false)(
      resolve(ctx).flatMap(_.run(command, workdir, timeout, ctx)).map(bounded)
    )

  private def bounded(result: ExecResult): ExecResult =
    val (out, cutOut) = SandboxBackend.cut(result.stdout, maxOutputBytes)
    val (err, cutErr) = SandboxBackend.cut(result.stderr, maxOutputBytes)
    ExecResult(result.exitCode, out, err, result.truncated || cutOut || cutErr)

object SandboxBackend:

  /** 32 KiB: the default bound on each stream. */
  val DefaultMaxOutputBytes: Int = 32 * 1024

  /** The most `maxOutputBytes` may be, so an `ExecResult` always fits a replay record. */
  val MaxOutputBytes: Int = 256 * 1024

  /** A backend over one runner: one tenant's sandbox. */
  def apply(runner: SandboxRunner, maxOutputBytes: Int = DefaultMaxOutputBytes): SandboxBackend =
    checked(maxOutputBytes)
    new SandboxBackend(maxOutputBytes, runner.locks, _ => Right(runner))

  /**
   * A backend whose runner `resolve` picks per call, typically by `ctx.run.config.tenantId` (rule 8).
   * Locks are computed before any context exists, so they are given here.
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

  /** `s` cut to at most `max` UTF-8 bytes on a character boundary, and whether it was cut. */
  private[harness] def cut(s: String, max: Int): (String, Boolean) =
    if s.length <= max / 3 || s.getBytes(UTF_8).length <= max then (s, false)
    else
      @annotation.tailrec def loop(i: Int, bytes: Int): Int =
        if i >= s.length then i
        else
          val cp    = s.codePointAt(i)
          val width = new String(Character.toChars(cp)).getBytes(UTF_8).length
          if bytes + width > max then i else loop(i + Character.charCount(cp), bytes + width)
      (s.substring(0, loop(0, 0)), true)
