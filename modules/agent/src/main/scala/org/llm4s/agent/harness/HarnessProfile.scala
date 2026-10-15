package org.llm4s.agent.harness

import org.llm4s.agent.graph.RetentionPolicy
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, PermissionMiddleware, PermissionRule }
import org.llm4s.agent.graph.tool.AgentTool
import org.llm4s.annotation.Experimental
import org.llm4s.context.ConversationTokenCounter
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.Try

/** Which of a [[HarnessProfile]]'s tools - the file tools and `execute` - the agent gets. */
@Experimental
enum ToolSelection:

  /** Every one: the seven file tools, and `execute` when the profile has a sandbox. */
  case All

  /** Only these, by name; each must be one of the profile's tools. */
  case Only(names: Set[String])

  /** All but these, by name; each must be one of the profile's tools. */
  case Except(names: Set[String])

/** Where a [[HarnessProfile]]'s system prompt comes from. */
@Experimental
enum PromptMode:

  /** The profile's prompt, [[HarnessProfile.defaultPrompt]]. */
  case Default

  /** `text` instead of the profile's prompt; it must not be blank. */
  case Replace(text: String)

  /** The profile's prompt, then `text`. */
  case Suffix(text: String)

/** The settings of a harness's [[TodoMiddleware]]; see its parameters. */
@Experimental
final case class TodoConfig private (maxTodos: Int, maxTodoChars: Int):
  def withMaxTodos(n: Int): TodoConfig     = TodoConfig(n, maxTodoChars)
  def withMaxTodoChars(n: Int): TodoConfig = TodoConfig(maxTodos, n)

@Experimental
object TodoConfig:
  def apply(
    maxTodos: Int = TodoMiddleware.DefaultMaxTodos,
    maxTodoChars: Int = TodoMiddleware.DefaultMaxTodoChars
  ): TodoConfig = new TodoConfig(maxTodos, maxTodoChars)

/**
 * The settings of a harness's [[SummarizationMiddleware]]; see its parameters. `compressor` `None` is
 * `SummaryCompressor.digest` over core's `cl100k_base` counter, which makes no model call; give a
 * `SummaryCompressor.model(client)` for a summary the model writes.
 */
@Experimental
final case class SummarizationConfig private (
  trigger: SummaryTrigger,
  keepRecent: Int,
  maxSummaryTokens: Int,
  compressor: Option[SummaryCompressor]
):
  def withTrigger(t: SummaryTrigger): SummarizationConfig =
    SummarizationConfig(t, keepRecent, maxSummaryTokens, compressor)
  def withKeepRecent(n: Int): SummarizationConfig       = SummarizationConfig(trigger, n, maxSummaryTokens, compressor)
  def withMaxSummaryTokens(n: Int): SummarizationConfig = SummarizationConfig(trigger, keepRecent, n, compressor)
  def withCompressor(c: SummaryCompressor): SummarizationConfig =
    SummarizationConfig(trigger, keepRecent, maxSummaryTokens, Some(c))
  def withCompressor(c: Option[SummaryCompressor]): SummarizationConfig =
    SummarizationConfig(trigger, keepRecent, maxSummaryTokens, c)

@Experimental
object SummarizationConfig:

  /** The default trigger's bound: a request over this many estimated tokens is summarised. Set it below your model's window. */
  val DefaultTriggerTokens: Int = 100000

  def apply(
    trigger: SummaryTrigger = SummaryTrigger.Tokens(DefaultTriggerTokens),
    keepRecent: Int = SummarizationMiddleware.DefaultKeepRecent,
    maxSummaryTokens: Int = SummarizationMiddleware.DefaultMaxSummaryTokens,
    compressor: Option[SummaryCompressor] = None
  ): SummarizationConfig = new SummarizationConfig(trigger, keepRecent, maxSummaryTokens, compressor)

/** The settings of a harness's [[ToolOutputOffload]]; see its parameters. */
@Experimental
final case class OffloadConfig private (thresholdTokens: Int, maxResultBytes: Long, maxTotalBytes: Long):
  def withThresholdTokens(n: Int): OffloadConfig = OffloadConfig(n, maxResultBytes, maxTotalBytes)
  def withMaxResultBytes(n: Long): OffloadConfig = OffloadConfig(thresholdTokens, n, maxTotalBytes)
  def withMaxTotalBytes(n: Long): OffloadConfig  = OffloadConfig(thresholdTokens, maxResultBytes, n)

@Experimental
object OffloadConfig:
  def apply(
    thresholdTokens: Int = ToolOutputOffload.DefaultThresholdTokens,
    maxResultBytes: Long = ToolOutputOffload.DefaultMaxResultBytes,
    maxTotalBytes: Long = ToolOutputOffload.DefaultMaxTotalBytes
  ): OffloadConfig = new OffloadConfig(thresholdTokens, maxResultBytes, maxTotalBytes)

/** The settings of a harness's [[SkillsMiddleware]], which reads through the profile's backend; see its parameters. */
@Experimental
final case class SkillsConfig private (
  sources: Vector[VPath],
  maxSkills: Int,
  maxDescriptionChars: Int,
  maxSectionTokens: Int,
  maxScanned: Int,
  maxCachedRuns: Int
):
  def withSources(s: Vector[VPath]): SkillsConfig =
    SkillsConfig(s, maxSkills, maxDescriptionChars, maxSectionTokens, maxScanned, maxCachedRuns)
  def withMaxSkills(n: Int): SkillsConfig =
    SkillsConfig(sources, n, maxDescriptionChars, maxSectionTokens, maxScanned, maxCachedRuns)
  def withMaxDescriptionChars(n: Int): SkillsConfig =
    SkillsConfig(sources, maxSkills, n, maxSectionTokens, maxScanned, maxCachedRuns)
  def withMaxSectionTokens(n: Int): SkillsConfig =
    SkillsConfig(sources, maxSkills, maxDescriptionChars, n, maxScanned, maxCachedRuns)
  def withMaxScanned(n: Int): SkillsConfig =
    SkillsConfig(sources, maxSkills, maxDescriptionChars, maxSectionTokens, n, maxCachedRuns)
  def withMaxCachedRuns(n: Int): SkillsConfig =
    SkillsConfig(sources, maxSkills, maxDescriptionChars, maxSectionTokens, maxScanned, n)

@Experimental
object SkillsConfig:

  /** `/skills`: where the preset looks for skills. */
  val DefaultSource: VPath = VPath("/skills").toOption.get

  def apply(
    sources: Vector[VPath] = Vector(DefaultSource),
    maxSkills: Int = SkillsMiddleware.DefaultMaxSkills,
    maxDescriptionChars: Int = SkillsMiddleware.DefaultMaxDescriptionChars,
    maxSectionTokens: Int = SkillsMiddleware.DefaultMaxSectionTokens,
    maxScanned: Int = SkillsMiddleware.DefaultMaxScanned,
    maxCachedRuns: Int = SkillsMiddleware.DefaultMaxCachedRuns
  ): SkillsConfig =
    new SkillsConfig(sources, maxSkills, maxDescriptionChars, maxSectionTokens, maxScanned, maxCachedRuns)

/**
 * The settings of a harness's [[InstructionMemoryMiddleware]], which reads through the profile's backend; see its
 * parameters.
 */
@Experimental
final case class InstructionsConfig private (
  paths: Vector[VPath],
  maxFileTokens: Int,
  maxTotalTokens: Int,
  maxCachedRuns: Int
):
  def withPaths(p: Vector[VPath]): InstructionsConfig =
    InstructionsConfig(p, maxFileTokens, maxTotalTokens, maxCachedRuns)
  def withMaxFileTokens(n: Int): InstructionsConfig  = InstructionsConfig(paths, n, maxTotalTokens, maxCachedRuns)
  def withMaxTotalTokens(n: Int): InstructionsConfig = InstructionsConfig(paths, maxFileTokens, n, maxCachedRuns)
  def withMaxCachedRuns(n: Int): InstructionsConfig  = InstructionsConfig(paths, maxFileTokens, maxTotalTokens, n)

@Experimental
object InstructionsConfig:

  /** `/AGENTS.md`: the instruction file the preset reads. */
  val DefaultPath: VPath = VPath("/AGENTS.md").toOption.get

  def apply(
    paths: Vector[VPath] = Vector(DefaultPath),
    maxFileTokens: Int = InstructionMemoryMiddleware.DefaultMaxFileTokens,
    maxTotalTokens: Int = InstructionMemoryMiddleware.DefaultMaxTotalTokens,
    maxCachedRuns: Int = InstructionMemoryMiddleware.DefaultMaxCachedRuns
  ): InstructionsConfig = new InstructionsConfig(paths, maxFileTokens, maxTotalTokens, maxCachedRuns)

/**
 * A harness for an agent (design §4.37): file tools over a backend, optionally `execute` over a sandbox, and the
 * middleware that plan, manage context, load skills and instructions and check permissions - assembled in a fixed
 * order by `AgentBuilder.withHarness`. Start from [[HarnessProfile.deepAgents]], or from `HarnessProfile()` (the file
 * tools alone), and change it with the `with*` setters; [[org.llm4s.config.HarnessConfigLoader]] loads the preset's settings from
 * `llm4s.agent.harness`.
 *
 * `build()` installs, outermost first and before the builder's own middleware: a `PermissionMiddleware` over
 * `permissions` (none when they are empty, since a `PermissionMiddleware` denies every call no rule matches), then
 * summarisation, offload, skills, instructions and todos, each when set. The selected file tools run over `backend`,
 * or over a `CompositeBackend` that also mounts `OffloadBackend` at `/large_tool_results` when `offload` is set; skills
 * and instructions read through the same backend. `execute` is added only when `sandbox` is set. Each tool of the
 * profile that declares no timeout is bounded by `toolTimeout`. The system prompt follows `systemPrompt`. After each
 * turn the root agent prunes the thread's earlier turns to `retention`, which bounds the full-snapshot checkpoints
 * it keeps.
 *
 * `build()` refuses an invalid setting (a limit a component refuses, a blank `PromptMode.Replace`), a tool name the
 * selection does not know, `execute` selected without a sandbox, and `withSystemPrompt` beside a harness: set the
 * prompt through `systemPrompt`.
 *
 * @param backend where the file tools, skills and instructions read and write
 * @param sandbox where `execute` runs commands; `None` adds no `execute` tool
 * @param tools which of the file tools and `execute` the agent gets
 * @param todos the plan (`write_todos`); `None` for none
 * @param summarization summarisation of older messages; `None` for none
 * @param offload offload of large tool results; `None` for none
 * @param skills Agent Skills; `None` for none
 * @param instructions instruction memory (`AGENTS.md`); `None` for none
 * @param systemPrompt the system prompt
 * @param permissions permission rules, first match wins; empty for no permission checks
 * @param toolTimeout the bound on each run of a profile tool that declares none
 * @param retention what a thread keeps of its earlier turns' checkpoints and events, applied before each turn
 */
@Experimental
final case class HarnessProfile private (
  backend: FileBackend,
  sandbox: Option[SandboxBackend],
  tools: ToolSelection,
  todos: Option[TodoConfig],
  summarization: Option[SummarizationConfig],
  offload: Option[OffloadConfig],
  skills: Option[SkillsConfig],
  instructions: Option[InstructionsConfig],
  systemPrompt: PromptMode,
  permissions: Vector[PermissionRule],
  toolTimeout: Option[FiniteDuration],
  retention: RetentionPolicy
):
  def withBackend(b: FileBackend): HarnessProfile                       = copy(backend = b)
  def withSandbox(s: SandboxBackend): HarnessProfile                    = copy(sandbox = Some(s))
  def withSandbox(s: Option[SandboxBackend]): HarnessProfile            = copy(sandbox = s)
  def withTools(t: ToolSelection): HarnessProfile                       = copy(tools = t)
  def withTodos(c: TodoConfig): HarnessProfile                          = copy(todos = Some(c))
  def withTodos(c: Option[TodoConfig]): HarnessProfile                  = copy(todos = c)
  def withSummarization(c: SummarizationConfig): HarnessProfile         = copy(summarization = Some(c))
  def withSummarization(c: Option[SummarizationConfig]): HarnessProfile = copy(summarization = c)
  def withOffload(c: OffloadConfig): HarnessProfile                     = copy(offload = Some(c))
  def withOffload(c: Option[OffloadConfig]): HarnessProfile             = copy(offload = c)
  def withSkills(c: SkillsConfig): HarnessProfile                       = copy(skills = Some(c))
  def withSkills(c: Option[SkillsConfig]): HarnessProfile               = copy(skills = c)
  def withInstructions(c: InstructionsConfig): HarnessProfile           = copy(instructions = Some(c))
  def withInstructions(c: Option[InstructionsConfig]): HarnessProfile   = copy(instructions = c)
  def withSystemPrompt(m: PromptMode): HarnessProfile                   = copy(systemPrompt = m)
  def withPermissions(rules: Vector[PermissionRule]): HarnessProfile    = copy(permissions = rules)
  def withToolTimeout(t: FiniteDuration): HarnessProfile                = copy(toolTimeout = Some(t))
  def withToolTimeout(t: Option[FiniteDuration]): HarnessProfile        = copy(toolTimeout = t)
  def withRetention(r: RetentionPolicy): HarnessProfile                 = copy(retention = r)

  /** The names of the tools this profile can give: the file tools, and `execute` when it has a sandbox. */
  def toolNames: Vector[String] = FileTools.Names ++ sandbox.map(_ => ExecuteTool.Name)

  /** The tools, middleware, prompt and retention this profile installs, each checked; see the class's Scaladoc. */
  private[llm4s] def assembled: Result[Harness] =
    for
      selected   <- selection
      prompt     <- promptText
      _          <- timeoutValid
      files      <- filesBackend
      middleware <- middlewareOver(files)
    yield
      val chosen = (FileTools(files) ++ sandbox.map(ExecuteTool(_))).filter(t => selected(t.spec.name))
      Harness(chosen.map(bounded), middleware, prompt, retention)

  private def bounded[A](tool: AgentTool[A]): AgentTool[A] =
    toolTimeout.fold(tool)(AgentTool.withDefaultTimeout(tool, _))

  private def selection: Result[Set[String]] =
    val known = toolNames.toSet
    val (named, chosen) = tools match
      case ToolSelection.All           => (Set.empty[String], known)
      case ToolSelection.Only(names)   => (names, names)
      case ToolSelection.Except(names) => (names, known -- names)
    val executeWithout = Option.when(sandbox.isEmpty && named(ExecuteTool.Name))(
      s"'${ExecuteTool.Name}' needs a sandbox: set one with withSandbox"
    )
    val unknown = (named - ExecuteTool.Name)
      .diff(known)
      .toVector
      .sorted
      .map(n => s"'$n' is not a harness tool; the harness tools are ${toolNames.mkString(", ")}")
    (executeWithout.toVector ++ unknown).toList match
      case Nil      => Right(chosen)
      case problems => Left(ValidationError("harness.tools", problems))

  private def promptText: Result[String] =
    systemPrompt match
      case PromptMode.Default => Right(HarnessProfile.defaultPrompt(this))
      case PromptMode.Replace(text) =>
        if text.trim.isEmpty then
          Left(ValidationError("harness.systemPrompt", "a replacement prompt must not be blank"))
        else Right(text)
      case PromptMode.Suffix(text) =>
        Right(
          if text.trim.isEmpty then HarnessProfile.defaultPrompt(this)
          else s"${HarnessProfile.defaultPrompt(this)}\n\n$text"
        )

  private def timeoutValid: Result[Unit] =
    toolTimeout.filter(_ <= Duration.Zero) match
      case Some(t) => Left(ValidationError("harness.toolTimeout", s"must be positive, was $t"))
      case None    => Right(())

  /** The backend the file tools, skills and instructions use: `backend`, with the offloaded results mounted when offloading. */
  private def filesBackend: Result[FileBackend] =
    if offload.isEmpty then Right(backend)
    else CompositeBackend(backend, ToolOutputOffload.Mount -> OffloadBackend)

  private def middlewareOver(files: FileBackend): Result[Vector[AgentMiddleware]] =
    val parts: Vector[Result[Option[AgentMiddleware]]] = Vector(
      HarnessProfile.checked("permissions")(Option.when(permissions.nonEmpty)(new PermissionMiddleware(permissions))),
      summarization.fold(Right(None))(c =>
        c.compressor
          .fold(ConversationTokenCounter.openAI().map(SummaryCompressor.digest))(Right(_))
          .flatMap(compressor =>
            HarnessProfile.checked("summarization")(
              Some(new SummarizationMiddleware(c.trigger, c.keepRecent, compressor, c.maxSummaryTokens))
            )
          )
      ),
      HarnessProfile.checked("offload")(
        offload.map(c => new ToolOutputOffload(c.thresholdTokens, c.maxResultBytes, c.maxTotalBytes))
      ),
      HarnessProfile.checked("skills")(
        skills.map(c =>
          new SkillsMiddleware(
            c.sources,
            files,
            c.maxSkills,
            c.maxDescriptionChars,
            c.maxSectionTokens,
            c.maxScanned,
            c.maxCachedRuns
          )
        )
      ),
      HarnessProfile.checked("instructions")(
        instructions.map(c =>
          new InstructionMemoryMiddleware(c.paths, files, c.maxFileTokens, c.maxTotalTokens, c.maxCachedRuns)
        )
      ),
      HarnessProfile.checked("todos")(todos.map(c => new TodoMiddleware(c.maxTodos, c.maxTodoChars)))
    )
    parts.collect { case Left(e) => e }.toList match
      case Nil    => Right(parts.collect { case Right(Some(m)) => m })
      case errors => Left(ValidationError("harness", errors.map(_.message)))

/** What a [[HarnessProfile]] installs on an agent, assembled and checked. */
final private[llm4s] case class Harness(
  tools: Vector[AgentTool[?]],
  middleware: Vector[AgentMiddleware],
  systemPrompt: String,
  retention: RetentionPolicy
)

@Experimental
object HarnessProfile:

  /** How many checkpoints a thread keeps by default: every checkpoint is a full snapshot of its state (rule 6). */
  val DefaultRetainedCheckpoints: Int = 20

  /** The default retention: the latest [[DefaultRetainedCheckpoints]] checkpoints. */
  def defaultRetention: RetentionPolicy = RetentionPolicy(maxCheckpoints = Some(DefaultRetainedCheckpoints))

  /** A profile; every argument defaults to the minimal profile's: the file tools over a new `StateFileBackend`. */
  def apply(
    backend: FileBackend = StateFileBackend(),
    sandbox: Option[SandboxBackend] = None,
    tools: ToolSelection = ToolSelection.All,
    todos: Option[TodoConfig] = None,
    summarization: Option[SummarizationConfig] = None,
    offload: Option[OffloadConfig] = None,
    skills: Option[SkillsConfig] = None,
    instructions: Option[InstructionsConfig] = None,
    systemPrompt: PromptMode = PromptMode.Default,
    permissions: Vector[PermissionRule] = Vector.empty,
    toolTimeout: Option[FiniteDuration] = None,
    retention: RetentionPolicy = defaultRetention
  ): HarnessProfile =
    new HarnessProfile(
      backend,
      sandbox,
      tools,
      todos,
      summarization,
      offload,
      skills,
      instructions,
      systemPrompt,
      permissions,
      toolTimeout,
      retention
    )

  /**
   * The opinionated preset: the file tools over a new `StateFileBackend`, todos, summarisation (with the model-free
   * digest compressor), offload of large results, skills under `/skills` and instructions in `/AGENTS.md`, each at
   * its component's defaults, with the default prompt and no permission rules. Each call has its own backend.
   */
  def deepAgents: HarnessProfile =
    HarnessProfile(
      todos = Some(TodoConfig()),
      summarization = Some(SummarizationConfig()),
      offload = Some(OffloadConfig()),
      skills = Some(SkillsConfig()),
      instructions = Some(InstructionsConfig())
    )

  /**
   * The prompt `PromptMode.Default` gives `profile`: how to plan, act and verify, manage context and use tools safely,
   * with a part for each of the file tools, the plan, context management and `execute` that the profile turns on.
   * Its parts are fixed text, so its size is bounded by them.
   */
  def defaultPrompt(profile: HarnessProfile): String =
    val selected = profile.selection.getOrElse(Set.empty)
    val files    = FileTools.Names.exists(selected)
    val parts = Vector(
      Some(Prompt.Intro),
      Some(Prompt.work(profile.todos.isDefined)),
      Option.when(files)(Prompt.Files),
      Option.when(profile.summarization.isDefined || profile.offload.isDefined)(
        Prompt.context(profile.summarization.isDefined, profile.offload.isDefined && selected(FileTools.ReadFile))
      ),
      Option.when(profile.sandbox.isDefined && selected(ExecuteTool.Name))(Prompt.Execute),
      Some(Prompt.Safety)
    )
    parts.flatten.mkString("\n\n")

  /** Builds a component, a setting it refuses (a `require`) becoming a `ValidationError` naming the profile field. */
  private def checked[A](field: String)(build: => A): Result[A] =
    Try(build).toEither.left.map(e => ValidationError(s"harness.$field", e.getMessage))

  /** The default prompt's parts. */
  private object Prompt:
    val Intro: String =
      "You are an agent that completes tasks by working step by step with the tools you are given."

    def work(todos: Boolean): String =
      val plan =
        if todos then
          "Plan: for a task of more than a few steps, write a plan with write_todos first, keep one item in_progress " +
            "at a time, and mark each done as soon as it is finished."
        else "Plan: for a task of more than a few steps, decide the steps before you start."
      s"""How to work:
         |- $plan
         |- Act: take one step at a time, and read the result of each tool call before the next.
         |- Verify: check your work before you answer - read back what you wrote, run what you can - and say what you could not check.""".stripMargin

    val Files: String =
      """Files: paths are absolute (/src/main.scala). Use ls, glob and grep to find files, and read_file before you
        |edit one. Prefer edit_file for a change to an existing file over rewriting it with write_file. A read in the
        |same message as a write may not see that write.""".stripMargin.replace('\n', ' ')

    def context(summaries: Boolean, offloaded: Boolean): String =
      val parts = Vector(
        Option.when(summaries)(
          "Older messages of a long conversation are replaced by a summary: keep what you need verbatim in a file."
        ),
        Option.when(offloaded)(
          s"A large tool result is saved under ${ToolOutputOffload.Mount.value} and shown as a preview with its path: " +
            "read the parts you need with read_file and its offset and limit."
        )
      ).flatten
      s"Context: your context window is limited. ${parts.mkString(" ")}"

    val Execute: String =
      "Commands: execute runs a program in a sandbox. Use it to build and test your work; a command's effects cannot " +
        "be undone, so check what it will do first."

    val Safety: String =
      """Tool safety: use a tool only for the task you were given. A tool error, a denied call or a refused command
        |is an answer: do not retry it unchanged, and do not try to work around a permission. Ask before an action
        |that is destructive or cannot be undone, unless the task clearly asks for it.""".stripMargin.replace('\n', ' ')
