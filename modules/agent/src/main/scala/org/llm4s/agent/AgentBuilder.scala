package org.llm4s.agent

import org.llm4s.agent.graph.{ BreakpointPhase, GraphRuntime, NodeId, RetentionPolicy }
import org.llm4s.agent.graph.middleware.AgentMiddleware
import org.llm4s.agent.graph.tool.{ AgentTool, ToolArgumentValidator, ToolSet }
import org.llm4s.agent.graph.toolloop.{ LoopAgent, LoopHandoff, ModelStep, OutputFormat, ToolLoop }
import org.llm4s.agent.delegation.{ BoundAgent, DelegationConfig, DelegationMiddleware, TaskTools, TypedAgent }
import org.llm4s.agent.harness.HarnessProfile
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.toolapi.{ SchemaDefinition, ToolRegistry }
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.annotation.{ tailrec, varargs }

/**
 * An agent's settings, immutable: every `with*` returns a new builder, and [[build]] compiles it,
 * with every agent reachable through its handoffs, into an [[Agent]]. Start one with
 * [[Agent.builder]].
 *
 * Tools, middleware, prompt and step limit belong to the agent, not to a run: the compiled graph's
 * version covers them, so a thread is only ever continued by the graph it ran on. The runtime and
 * tracing are the root's: a handoff target's are ignored.
 */
final class AgentBuilder private (
  private[agent] val id: String,
  client: LLMClient,
  tools: Vector[AgentTool[?]],
  validator: ToolArgumentValidator,
  systemPrompt: Option[String],
  options: CompletionOptions,
  middleware: Vector[AgentMiddleware],
  private val handoffs: Vector[Handoff],
  maxSteps: Int,
  runtime: Option[GraphRuntime],
  tracing: Option[Tracing],
  streaming: Boolean,
  private val interruptBefore: Vector[AgentNode],
  private val interruptAfter: Vector[AgentNode],
  maxPromptSectionTokens: Int,
  output: Option[OutputFormat],
  harness: Option[HarnessProfile],
  private val subagents: Vector[TypedAgent[?, ?]],
  private val delegation: DelegationConfig
):

  private def copy(
    tools: Vector[AgentTool[?]] = tools,
    validator: ToolArgumentValidator = validator,
    systemPrompt: Option[String] = systemPrompt,
    options: CompletionOptions = options,
    middleware: Vector[AgentMiddleware] = middleware,
    handoffs: Vector[Handoff] = handoffs,
    maxSteps: Int = maxSteps,
    runtime: Option[GraphRuntime] = runtime,
    tracing: Option[Tracing] = tracing,
    streaming: Boolean = streaming,
    interruptBefore: Vector[AgentNode] = interruptBefore,
    interruptAfter: Vector[AgentNode] = interruptAfter,
    maxPromptSectionTokens: Int = maxPromptSectionTokens,
    output: Option[OutputFormat] = output,
    harness: Option[HarnessProfile] = harness,
    subagents: Vector[TypedAgent[?, ?]] = subagents,
    delegation: DelegationConfig = delegation
  ): AgentBuilder =
    new AgentBuilder(
      id,
      client,
      tools,
      validator,
      systemPrompt,
      options,
      middleware,
      handoffs,
      maxSteps,
      runtime,
      tracing,
      streaming,
      interruptBefore,
      interruptAfter,
      maxPromptSectionTokens,
      output,
      harness,
      subagents,
      delegation
    )

  /** The agent's tools, replacing any set before, checked with the set's validator. */
  def withTools(tools: ToolSet): AgentBuilder = copy(tools = tools.tools, validator = tools.validator)

  /**
   * The registry's tools, replacing any set before, each adapted with
   * [[org.llm4s.agent.graph.tool.AgentTool.fromToolFunction]]; [[build]] checks them as a
   * [[org.llm4s.agent.graph.tool.ToolSet]].
   */
  def withTools(registry: ToolRegistry): AgentBuilder =
    copy(tools = registry.tools.map(AgentTool.fromToolFunction).toVector, validator = ToolArgumentValidator.default)

  /** Sent first on every model call of this agent; never stored in the thread's history. */
  def withSystemPrompt(prompt: String): AgentBuilder = copy(systemPrompt = Some(prompt))

  /** The options every model call of this agent is made with; their `tools` are replaced by the agent's. */
  def withCompletionOptions(options: CompletionOptions): AgentBuilder = copy(options = options)

  /**
   * Appends `middleware` to the agent's stack, in order. The root agent's run-boundary hooks guard
   * the whole family: its `beforeAgent` runs on every turn's query, before the active agent's own,
   * and its `afterAgent` on every final answer, after the answering agent's own - so a
   * [[org.llm4s.agent.graph.middleware.GuardrailMiddleware]] on the root still applies after a
   * handoff. A handoff target's boundary hooks apply only while it is active, and model and tool
   * wrappers (`wrapModelCall`, `wrapToolCall`) only to the agent they are given to.
   */
  def withMiddleware(middleware: AgentMiddleware*): AgentBuilder = copy(middleware = this.middleware ++ middleware)

  /** Appends `handoffs` to the agent's handoffs, in order. */
  def withHandoffs(handoffs: Handoff*): AgentBuilder = copy(handoffs = this.handoffs ++ handoffs)

  /**
   * The response format of this agent's answers (design 4.26): `schema`, rendered strict, replacing any set
   * before - a hand-written `ObjectSchema[O]`, or one derived with `llm4s-schema-derivation`:
   * `withOutput(SchemaOf[Invoice].definition)`. The system prompt states it on every model call, and a call
   * that offers no tools carries it as `responseFormat`, replacing one given with [[withCompletionOptions]]; a
   * call that offers tools carries none, since providers that cannot combine the two would refuse or suppress
   * the tool calls (see `ModelStep.fromClient`). A final answer must be JSON that matches the schema and reads as `O`, and is stored as that JSON
   * alone. An invalid answer is retried once in the same model step, with its problems; a second invalid
   * answer ends the turn with `Left(GraphError.OutputInvalid)`, the turn removed and both calls counted.
   * Read the answer with [[AgentResult.decodeOutput]]. The format belongs to this agent only: a handoff
   * target answers in its own. [[build]] refuses a schema whose root is not an object, one with a keyword
   * the tool argument validator cannot check, and a format whose prompt section alone is over
   * [[withMaxPromptSectionTokens]], which the section counts against with the middleware's.
   */
  def withOutput[O: ReadWriter](schema: SchemaDefinition[O]): AgentBuilder =
    copy(output = Some(OutputFormat.of(schema)))

  /**
   * Installs `profile` (design 4.37), replacing any set before: at [[build]], its tools join this agent's, its
   * middleware run outside this agent's own - permissions, summarisation, offload, skills, instructions, todos,
   * outermost first, each when the profile sets it - and its prompt is the system prompt, so [[withSystemPrompt]] is
   * refused beside it (use `PromptMode.Replace` or `Suffix`). On the root agent, its `retention` prunes each thread as each turn is admitted
   * (`RunConfig.withRetention`, unless the run sets its own); a handoff target's is ignored, as its runtime is. `build()` refuses what the profile refuses
   * (see [[org.llm4s.agent.harness.HarnessProfile]]).
   */
  def withHarness(profile: HarnessProfile): AgentBuilder = copy(harness = Some(profile))

  /**
   * Appends `agents` to the subagents this agent may delegate to (design 4.44), in order. At [[build]] each is bound
   * to this agent's client and runtime, and a `delegation` middleware, innermost in the stack, offers them: the
   * `task` tool for every subagent taking a `TaskRequest`, a `task_<id>` tool for each other one, and a cacheable
   * prompt section listing each subagent's id and description. A call runs the subagent on a child thread of the
   * calling thread and returns only its typed report. [[build]] refuses two subagents with one id, a subagent that does
   * not bind, and a task tool whose name clashes with another tool. Bounded by [[withDelegation]].
   */
  def withSubagents(agents: TypedAgent[?, ?]*): AgentBuilder = copy(subagents = subagents ++ agents)

  /** The bounds on this agent's delegated tasks, replacing the defaults; [[build]] refuses a bound that is not positive. */
  def withDelegation(config: DelegationConfig): AgentBuilder = copy(delegation = config)

  /** Model calls per turn, at least 1; at the limit the turn ends `StepLimitReached`. */
  def withMaxSteps(n: Int): AgentBuilder = copy(maxSteps = n)

  /**
   * The most every middleware's prompt sections may add to one model request together, at least 1 and 8192 by
   * default, estimated at four characters a token. A request over it fails the run, naming each section's size.
   */
  def withMaxPromptSectionTokens(n: Int): AgentBuilder = copy(maxPromptSectionTokens = n)

  /**
   * The runtime the agent's threads live on; by default a new [[GraphRuntime.inMemory]] per
   * [[build]]. A runtime keeps every thread - a one-shot [[Agent.run]]'s too - until
   * [[Agent.forget]] removes it.
   */
  def withRuntime(runtime: GraphRuntime): AgentBuilder = copy(runtime = Some(runtime))

  /**
   * Traces each run to `tracing`: its events as `graph.*` and `agent.*` custom events, each model
   * call's usage as `TokenUsageRecorded`, and its end as one `AgentRunEnded`.
   */
  def withTracing(tracing: Tracing): AgentBuilder = copy(tracing = Some(tracing))

  /**
   * Streams the model's answer: each model call uses `streamComplete`, and its text and thinking
   * reach subscribers as live `AgentEvents.TextDelta` and `ThinkingDelta` events. Off by default.
   * It changes nothing stored, so it is not part of the graph's version: a thread runs on with
   * streaming switched on or off.
   */
  def withStreaming(): AgentBuilder = copy(streaming = true)

  /**
   * Static breakpoints that hold each task of these nodes of this agent before it runs, on every run;
   * added to those set before, and to those a run's `RunConfig.interruptBefore` names. A held task is
   * an interrupt of its own - `AgentStatus.Suspended.breakpoints` - continued with
   * `AgentResult.proceed`. Like streaming, breakpoints change nothing stored, so they are not part of
   * the graph's version: a thread held by one agent is continued by one without them. A task continued
   * after an approval or a question is not held before again: the reviewer has just seen it. Java and
   * Kotlin call it with plain arguments: `withInterruptBefore(AgentNode.valueOf("Tool"))`.
   */
  @varargs
  def withInterruptBefore(nodes: AgentNode*): AgentBuilder = copy(interruptBefore = interruptBefore ++ nodes)

  /**
   * Static breakpoints that hold each task of these nodes of this agent after it ran, on every run: its
   * update is stored, and what follows it waits. A step continued after an approval or a question - a
   * model call after a `wrapModelCall` question, a tool call after an approval or a question, an answer
   * guarded again after an `afterAgent` question - is held after it ran, as the step itself is. Each
   * release replays the held routes in a superstep of its own, which counts against
   * `RunBudgets.maxSupersteps`. See [[withInterruptBefore]].
   */
  @varargs
  def withInterruptAfter(nodes: AgentNode*): AgentBuilder = copy(interruptAfter = interruptAfter ++ nodes)

  /**
   * Compiles this agent and every agent reachable through handoffs into one graph. Refuses an
   * invalid id, a `maxSteps` below 1, a handoff whose id is invalid or differs from its target's,
   * an id-only handoff ([[Handoff.toId]]) to an id no builder of the family defines, one id
   * defined by two builders with different settings - their handoff targets and subagents compared too - a tool named
   * like a task tool of any agent of the family (task calls are counted by name across handoffs), and whatever
   * [[org.llm4s.agent.graph.toolloop.ToolLoop.build]] refuses: tool name clashes, invalid tool
   * schemas, keys the loop owns, handoffs to self or twice to one target.
   */
  def build(): Result[Agent] =
    val graphRuntime = runtime.getOrElse(GraphRuntime.inMemory())
    for
      family <- AgentBuilder.family(this)
      _      <- AgentBuilder.idTargetsDefined(family)
      // a handoff stays in the turn, so each agent counts the whole family's task calls in it (#2067)
      taskNames = family.flatMap(b => TaskTools.names(b.subagents)).toSet
      _ <- AgentBuilder.taskNamesUnshared(family, taskNames)
      agents <- family.foldLeft[Result[Vector[LoopAgent]]](Right(Vector.empty)) { (acc, builder) =>
        // every member's subagents trace to the root's tracing, which traces the whole family's runs (design 4.47)
        acc.flatMap(done => builder.loopAgent(graphRuntime, tracing, taskNames).map(done :+ _))
      }
      root      <- AgentId.of(id)
      loop      <- ToolLoop.build(id, AgentBuilder.fingerprint(family), root, agents)
      installed <- effective
    yield new Agent(
      root,
      loop,
      graphRuntime,
      tracing,
      family.flatMap(b => b.held(loop, b.interruptBefore, BreakpointPhase.Before)).toSet,
      family.flatMap(b => b.held(loop, b.interruptAfter, BreakpointPhase.After)).toSet,
      installed.retention,
      // children are made on the family's thread by whichever agent is active: keep the tightest bound any sets
      family.filter(_.subagents.nonEmpty).map(_.delegation.childRetention).minOption
    )

  /**
   * The `delegation` middleware offering this agent's subagents, each bound to its client and `graphRuntime`; none
   * without subagents. Refuses a bound that is not positive, two subagents with one id, and a subagent that does not
   * bind.
   */
  private def delegating(
    graphRuntime: GraphRuntime,
    tracing: Option[Tracing],
    familyTaskNames: Set[String]
  ): Result[Option[DelegationMiddleware]] =
    // the bounds are checked whether or not any subagent is set, so a bad config never lies latent
    if subagents.isEmpty then delegation.validated.map(_ => None)
    else
      val ids        = subagents.map(_.id.value)
      val duplicated = ids.diff(ids.distinct).distinct
      for
        config <- delegation.validated
        _ <- Either.cond(
          duplicated.isEmpty,
          (),
          ValidationError("subagents", s"agent '$id': subagent ids defined twice: ${duplicated.mkString(", ")}")
        )
        bound <- subagents.foldLeft[Result[Vector[BoundAgent[?, ?]]]](Right(Vector.empty)) { (acc, definition) =>
          acc.flatMap(done => definition.bind(client, graphRuntime, tracing).map(done :+ _))
        }
      yield Some(new DelegationMiddleware(bound, config, depth = 0, familyTaskNames))

  /** The nodes of `loop` this agent's breakpoints on `nodes` hold in `phase`; its id is checked by `build` first. */
  private def held(loop: ToolLoop, nodes: Vector[AgentNode], phase: BreakpointPhase): Vector[NodeId] =
    nodes.flatMap(loop.breakpointNodes(AgentId.unsafe(id), _, phase))

  /**
   * The tools, middleware and prompt this agent runs with, its harness's included, and the retention its harness
   * sets; assembled once per builder, so its middleware are one set of instances however often it is built.
   */
  private lazy val effective: Result[AgentBuilder.Effective] =
    harness match
      case None => Right(AgentBuilder.Effective(tools, middleware, systemPrompt, None))
      case Some(profile) =>
        if systemPrompt.isDefined then
          Left(
            ValidationError(
              "systemPrompt",
              s"agent '$id': a harness sets the system prompt; use its PromptMode.Replace or Suffix, not withSystemPrompt"
            )
          )
        else
          profile.assembled.map(h =>
            AgentBuilder.Effective(
              h.tools ++ tools,
              h.middleware ++ middleware,
              Some(h.systemPrompt),
              Some(h.retention)
            )
          )

  private def loopAgent(
    graphRuntime: GraphRuntime,
    tracing: Option[Tracing],
    familyTaskNames: Set[String]
  ): Result[LoopAgent] =
    for
      agentId <- AgentId.of(id)
      _ <- Either.cond(
        maxSteps >= 1,
        (),
        ValidationError("maxSteps", s"agent '$id': must be at least 1, was $maxSteps")
      )
      installed  <- effective
      delegating <- delegating(graphRuntime, tracing, familyTaskNames)
      toolSet    <- ToolSet.of(validator, installed.tools*)
      loopHandoffs <- handoffs.foldLeft[Result[Vector[LoopHandoff]]](Right(Vector.empty)) { (acc, h) =>
        acc.flatMap(done => loopHandoff(h).map(done :+ _))
      }
    yield LoopAgent(agentId, ModelStep.fromClient(client, options, streaming, output.map(_.responseFormat)), toolSet)
      .withSystemPrompt(installed.systemPrompt)
      .withOutput(output)
      .withMaxSteps(maxSteps)
      .withMaxPromptSectionTokens(maxPromptSectionTokens)
      .withMiddleware(installed.middleware ++ delegating)
      .withHandoffs(loopHandoffs)

  private def loopHandoff(handoff: Handoff): Result[LoopHandoff] =
    if !Handoff.isValidId(handoff.id) then
      Left(ValidationError("handoffs", s"agent '$id': handoff id '${handoff.id}' must match [a-zA-Z0-9_-]{1,52}"))
    else
      handoff.target.filter(_.id != handoff.id) match
        case Some(target) =>
          Left(
            ValidationError(
              "handoffs",
              s"agent '$id': handoff id '${handoff.id}' must be its target's agent id '${target.id}'"
            )
          )
        case None => Right(LoopHandoff(AgentId.unsafe(handoff.id), handoff.transferReason, handoff.preserveContext))

  /**
   * The settings the graph version covers, as canonical JSON: two builders of one id must agree on them. A harness's
   * tools, middleware and prompt are covered as the agent's own; a harness that does not assemble is covered by its
   * presence only, and refused by `build()`.
   */
  /**
   * Whether `other` delegates as this builder does: the same subagent definitions, as instances, in order, and the same
   * bounds. The graph version covers only the task tools' specs, so two builders whose subagents differ behind the same
   * specs (prompts, tools, output, failure policy) would otherwise pass as one agent.
   */
  private def delegatesAs(other: AgentBuilder): Boolean =
    subagents.size == other.subagents.size &&
      subagents.lazyZip(other.subagents).forall(_ eq _) &&
      delegation == other.delegation

  private def canonical: ujson.Obj =
    val installed = effective.getOrElse(AgentBuilder.Effective(tools, middleware, systemPrompt, None))
    val settings = ujson.Obj(
      "id"           -> id,
      "systemPrompt" -> installed.systemPrompt.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "maxSteps"     -> maxSteps,
      "tools" -> ujson.Arr.from(
        installed.tools
          .sortBy(_.spec.name)
          .map(t => ujson.Obj("name" -> t.spec.name, "definition" -> t.spec.toolDefinition))
      ),
      // each middleware's id and contributed tools, so a changed middleware tool schema changes the version
      "middleware" -> ujson.Arr.from(
        installed.middleware.map(m =>
          ujson.Obj(
            "id" -> m.id.value,
            "tools" -> ujson.Arr.from(
              m.tools
                .sortBy(_.spec.name)
                .map(t => ujson.Obj("name" -> t.spec.name, "definition" -> t.spec.toolDefinition.render()))
            )
          )
        )
      ),
      "handoffs" -> ujson.Arr.from(
        handoffs.sortBy(_.id).map(h => ujson.Obj("target" -> h.id, "preserveContext" -> h.preserveContext))
      )
    )
    // only when set, so the version of an agent without a response format is what it was before formats existed
    output.foreach(format => settings("output") = format.schema)
    // only when set, likewise: the task tools the delegation middleware contributes
    if subagents.nonEmpty then
      settings("subagents") = ujson.Arr.from(
        TaskTools.specs(subagents).map(s => ujson.Obj("name" -> s.name, "definition" -> s.toolDefinition.render()))
      )
    if harness.isDefined && effective.isLeft then settings("harness") = "invalid"
    settings

object AgentBuilder:

  private[agent] def apply(id: String, client: LLMClient): AgentBuilder =
    new AgentBuilder(
      id,
      client,
      Vector.empty,
      ToolArgumentValidator.default,
      None,
      CompletionOptions(),
      Vector.empty,
      Vector.empty,
      Agent.DefaultMaxSteps,
      None,
      None,
      streaming = false,
      interruptBefore = Vector.empty,
      interruptAfter = Vector.empty,
      maxPromptSectionTokens = LoopAgent.DefaultMaxPromptSectionTokens,
      output = None,
      harness = None,
      subagents = Vector.empty,
      delegation = DelegationConfig.default
    )

  /** What an agent runs with once its harness is installed; `retention` only with a harness. */
  final private case class Effective(
    tools: Vector[AgentTool[?]],
    middleware: Vector[AgentMiddleware],
    systemPrompt: Option[String],
    retention: Option[RetentionPolicy]
  )

  /**
   * The root and every builder reachable through handoffs to builders, breadth-first, one per id.
   * A second builder of an id already reached is the same agent when it is the same builder, or has
   * the same settings and its own handoff targets are the same agents in turn - so they are walked
   * too - and is refused otherwise. Builders are immutable, so builder targets form no cycle and
   * the walk ends; each builder instance is walked once.
   */
  private def family(root: AgentBuilder): Result[Vector[AgentBuilder]] =
    @tailrec def walk(
      queue: Vector[AgentBuilder],
      found: Vector[AgentBuilder],
      walked: Vector[AgentBuilder]
    ): Result[Vector[AgentBuilder]] =
      queue match
        case next +: rest if walked.exists(_ eq next) => walk(rest, found, walked)
        case next +: rest =>
          val targets = next.handoffs.flatMap(_.target)
          found.find(_.id == next.id) match
            case None => walk(rest ++ targets, found :+ next, walked :+ next)
            case Some(seen) if seen.canonical == next.canonical && seen.delegatesAs(next) =>
              walk(rest ++ targets, found, walked :+ next)
            case Some(_) =>
              Left(ValidationError("handoffs", s"agent id '${next.id}' is defined by two different builders"))
        case _ => Right(found)
    walk(Vector(root), Vector.empty, Vector.empty)

  /** Every id-only handoff names an agent some builder of the family defines. */
  /**
   * No agent of the family has an ordinary tool named like a task tool of any agent of it (#2067): the per-turn count
   * counts the family's task calls by name across handoffs, so such a tool's calls would count as tasks.
   */
  private def taskNamesUnshared(family: Vector[AgentBuilder], taskNames: Set[String]): Result[Unit] =
    family
      .foldLeft[Result[Vector[(String, String)]]](Right(Vector.empty)) { (acc, b) =>
        acc.flatMap(found =>
          b.effective.map { e =>
            // the tools the loop offers: the agent's own and its middleware's, not the task tools themselves
            val offered = e.tools ++ e.middleware.filterNot(_.isInstanceOf[DelegationMiddleware]).flatMap(_.tools)
            found ++ offered.map(_.spec.name).filter(taskNames).distinct.map(b.id -> _)
          }
        )
      }
      .flatMap {
        case clashes if clashes.isEmpty => Right(())
        case clashes =>
          Left(
            ValidationError(
              "tools",
              clashes
                .map((agent, name) =>
                  s"agent '$agent' has a tool named '$name', the name of a task tool in its handoff family; rename it"
                )
                .toList
            )
          )
      }

  private def idTargetsDefined(family: Vector[AgentBuilder]): Result[Unit] =
    val ids = family.map(_.id).toSet
    family.flatMap(b => b.handoffs.collect { case h if h.target.isEmpty && !ids(h.id) => b.id -> h.id }).toList match
      case Nil => Right(())
      case missing =>
        Left(
          ValidationError(
            "handoffs",
            missing.map((from, to) =>
              s"agent '$from' hands off to '$to' by id, and no builder in the family defines it"
            )
          )
        )

  /**
   * The graph version of a family: the hex SHA-256 of its agents' settings, sorted by id - each
   * agent's id, system prompt and max steps, its response format's schema when it has one, its tools' names and definitions sorted by name, its
   * middleware ids in registration order with each middleware's contributed tools' names and
   * definitions, and its handoff targets with `preserveContext`. Completion
   * options, streaming, breakpoints and the model client are not part of it: they are rebound when a thread is
   * restored. A thread in flight (suspended, or `Running` after a crash) restores only into the same version; a thread
   * at rest into a changed family too, unless a node it was built with or a key it holds a value for is gone (design
   * §4.29).
   */
  private[agent] def fingerprint(family: Vector[AgentBuilder]): String =
    val canonical = ujson.Arr.from(family.sortBy(_.id).map(_.canonical)).render()
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString
