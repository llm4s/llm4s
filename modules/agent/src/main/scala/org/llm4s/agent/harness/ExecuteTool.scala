package org.llm4s.agent.harness

import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, LockKey, Operation, ToolContext, ToolOutcome, ToolResource }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.{ Schema, ToolHints }
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.concurrent.duration.*

/**
 * The `execute` tool (design §4.33): runs a command in a [[SandboxBackend]], and knows nothing else about
 * where. Through the backend, every command runs under C4's `Unverifiable` guard: a completed command
 * replays its stored result, one that may have run is refused rather than run again, and a Block cannot
 * undo what a command did (rule 5).
 *
 *  - '''Arguments.''' `command`; `workdir`, a path such as `/src` (default `/`); `timeout_seconds`
 *    (default `defaultTimeout`, at most `maxTimeout`).
 *  - '''Permissions.''' A call touches `ToolResource(Operation.Execute, <command>)`, so a
 *    `PermissionMiddleware` rule matches on the command; the hints are `destructive` and `openWorld`.
 *  - '''Order.''' A call holds `backend.locks(workdir)` (a workspace sandbox: its whole workspace), so a
 *    batch's commands and the edits of the files they may touch apply in call order.
 *  - '''Bounds.''' Each stream is at most the backend's `maxOutputBytes`, the command at most
 *    `SandboxBackend.MaxCommandBytes`, the run at most the timeout; the tool itself is stopped `maxTimeout`
 *    plus a minute in, for a sandbox that never answers.
 *
 * The result is the exit code, stdout and stderr, and a note when output was cut. A refusal (by the
 * sandbox's policy, or of a replay) is a tool error; a failure whose outcome is unknown fails the task, so
 * `recover` decides it from C4's records.
 */
@Experimental
object ExecuteTool:

  /** The tool's name. */
  val Name: String = "execute"

  /** The arguments of one call; `timeout_seconds` on the wire. */
  final case class Args(
    command: String,
    workdir: Option[String] = None,
    @upickle.implicits.key("timeout_seconds") timeoutSeconds: Option[Int] = None
  ) derives ReadWriter

  /** The tool over `backend`; both timeouts must be positive and `defaultTimeout` at most `maxTimeout`. */
  def apply(
    backend: SandboxBackend,
    defaultTimeout: FiniteDuration = 60.seconds,
    maxTimeout: FiniteDuration = 10.minutes
  ): AgentTool[Args] =
    require(defaultTimeout > Duration.Zero && maxTimeout > Duration.Zero, "timeouts must be positive")
    require(defaultTimeout <= maxTimeout, s"defaultTimeout ($defaultTimeout) must be at most maxTimeout ($maxTimeout)")
    val maxSeconds = math.max(1L, maxTimeout.toSeconds).min(Int.MaxValue.toLong).toInt
    val spec = AgentToolSpec[Args](
      Name,
      "Run a command in the sandbox and return its exit code, stdout and stderr. The command is a program and " +
        "its arguments, not shell text.",
      Schema
        .`object`[Args]("The command to run")
        .withRequiredField(
          "command",
          Schema.string("The program and its arguments").withLengthConstraints(min = Some(1), max = None)
        )
        .withOptionalField("workdir", Schema.string("The directory to run it in, such as /src; default /"))
        .withOptionalField(
          "timeout_seconds",
          Schema
            .integer(s"How long it may run, in seconds; default ${defaultTimeout.toSeconds.max(1)}")
            .withRange(Some(1), Some(maxSeconds))
        )
    )
      .withValidation(args => checked(args, maxTimeout).map(_ => ()))
      .withHints(ToolHints.default.withDestructive(true).withOpenWorld(true))
      .withResources(args => Vector(ToolResource(Operation.Execute, args.command)))
      // an invalid directory is refused by the validation; until then it holds the root lock, which orders it
      .withLocks(args => VPath(args.workdir.getOrElse("/")).fold(_ => Set(LockKey("")), backend.locks))
      .withTimeout(maxTimeout + 1.minute)
    AgentTool(spec)((args, context) => run(backend, defaultTimeout, maxTimeout, args, context))

  private def checked(args: Args, maxTimeout: FiniteDuration): Result[VPath] =
    if args.command.isBlank then Left(ValidationError("command", "must not be empty"))
    else if args.timeoutSeconds.exists(s => s < 1 || s.toLong.seconds > maxTimeout.max(1.second)) then
      Left(ValidationError("timeout_seconds", s"must be in 1..${math.max(1L, maxTimeout.toSeconds)}"))
    else VPath(args.workdir.getOrElse("/"))

  private def run(
    backend: SandboxBackend,
    defaultTimeout: FiniteDuration,
    maxTimeout: FiniteDuration,
    args: Args,
    context: ToolContext
  ): ToolOutcome =
    checked(args, maxTimeout) match
      case Left(error) => ToolOutcome.Error(error.message)
      case Right(workdir) =>
        val timeout = args.timeoutSeconds.fold(defaultTimeout)(_.toLong.seconds)
        backend
          .execute(args.command, workdir, timeout, FileContext(context))
          .fold(BackendRefused.toolOutcome, r => ToolOutcome.Success(ujson.Str(render(r, backend.maxOutputBytes))))

  /** The result as the model reads it. */
  private[harness] def render(result: ExecResult, maxOutputBytes: Int): String =
    def section(name: String, text: String) = s"$name:\n${if text.isEmpty then "(empty)" else text}"
    val note =
      if result.truncated then s"\n(output cut: each stream is kept to $maxOutputBytes bytes)" else ""
    s"exit code: ${result.exitCode}\n${section("stdout", result.stdout)}\n${section("stderr", result.stderr)}$note"
