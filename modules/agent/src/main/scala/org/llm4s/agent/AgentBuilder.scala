package org.llm4s.agent

import org.llm4s.agent.graph.{ GraphRuntime, NodeId }
import org.llm4s.agent.graph.middleware.AgentMiddleware
import org.llm4s.agent.graph.tool.{ AgentTool, ToolArgumentValidator, ToolSet }
import org.llm4s.agent.graph.toolloop.{ LoopAgent, LoopHandoff, ModelStep, ToolLoop }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.trace.Tracing
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.annotation.tailrec

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
  private val interruptAfter: Vector[AgentNode]
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
    interruptAfter: Vector[AgentNode] = interruptAfter
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
      interruptAfter
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

  /** Model calls per turn, at least 1; at the limit the turn ends `StepLimitReached`. */
  def withMaxSteps(n: Int): AgentBuilder = copy(maxSteps = n)

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
   * the graph's version: a thread held by one agent is continued by one without them.
   */
  def withInterruptBefore(nodes: AgentNode*): AgentBuilder = copy(interruptBefore = interruptBefore ++ nodes)

  /**
   * Static breakpoints that hold each task of these nodes of this agent after it ran, on every run: its
   * update is stored, and what follows it waits. See [[withInterruptBefore]].
   */
  def withInterruptAfter(nodes: AgentNode*): AgentBuilder = copy(interruptAfter = interruptAfter ++ nodes)

  /**
   * Compiles this agent and every agent reachable through handoffs into one graph. Refuses an
   * invalid id, a `maxSteps` below 1, a handoff whose id is invalid or differs from its target's,
   * an id-only handoff ([[Handoff.toId]]) to an id no builder of the family defines, one id
   * defined by two builders with different settings - their handoff targets compared too - and whatever
   * [[org.llm4s.agent.graph.toolloop.ToolLoop.build]] refuses: tool name clashes, invalid tool
   * schemas, keys the loop owns, handoffs to self or twice to one target.
   */
  def build(): Result[Agent] =
    for
      family <- AgentBuilder.family(this)
      _      <- AgentBuilder.idTargetsDefined(family)
      agents <- family.foldLeft[Result[Vector[LoopAgent]]](Right(Vector.empty)) { (acc, builder) =>
        acc.flatMap(done => builder.loopAgent.map(done :+ _))
      }
      root <- AgentId.of(id)
      loop <- ToolLoop.build(id, AgentBuilder.fingerprint(family), root, agents)
    yield new Agent(
      root,
      loop,
      runtime.getOrElse(GraphRuntime.inMemory()),
      tracing,
      family.flatMap(b => b.interruptBefore.map(b.nodeOf)).toSet,
      family.flatMap(b => b.interruptAfter.map(b.nodeOf)).toSet
    )

  /** This agent's node for `node`; the id is checked by `build` before it is used. */
  private def nodeOf(node: AgentNode): NodeId =
    val agent = AgentId.unsafe(id)
    node match
      case AgentNode.Model  => ToolLoop.modelNode(agent)
      case AgentNode.Tool   => ToolLoop.callToolNode(agent)
      case AgentNode.Finish => ToolLoop.finishNode(agent)

  private def loopAgent: Result[LoopAgent] =
    for
      agentId <- AgentId.of(id)
      _ <- Either.cond(
        maxSteps >= 1,
        (),
        ValidationError("maxSteps", s"agent '$id': must be at least 1, was $maxSteps")
      )
      toolSet <- ToolSet.of(validator, tools*)
      loopHandoffs <- handoffs.foldLeft[Result[Vector[LoopHandoff]]](Right(Vector.empty)) { (acc, h) =>
        acc.flatMap(done => loopHandoff(h).map(done :+ _))
      }
    yield LoopAgent(agentId, ModelStep.fromClient(client, options, streaming), toolSet)
      .withSystemPrompt(systemPrompt)
      .withMaxSteps(maxSteps)
      .withMiddleware(middleware)
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

  /** The settings the graph version covers, as canonical JSON: two builders of one id must agree on them. */
  private def canonical: ujson.Obj =
    ujson.Obj(
      "id"           -> id,
      "systemPrompt" -> systemPrompt.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
      "maxSteps"     -> maxSteps,
      "tools" -> ujson.Arr.from(
        tools.sortBy(_.spec.name).map(t => ujson.Obj("name" -> t.spec.name, "definition" -> t.spec.toolDefinition))
      ),
      // each middleware's id and contributed tools, so a changed middleware tool schema changes the version
      "middleware" -> ujson.Arr.from(
        middleware.map(m =>
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
      interruptAfter = Vector.empty
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
            case None                                           => walk(rest ++ targets, found :+ next, walked :+ next)
            case Some(seen) if seen.canonical == next.canonical => walk(rest ++ targets, found, walked :+ next)
            case Some(_) =>
              Left(ValidationError("handoffs", s"agent id '${next.id}' is defined by two different builders"))
        case _ => Right(found)
    walk(Vector(root), Vector.empty, Vector.empty)

  /** Every id-only handoff names an agent some builder of the family defines. */
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
   * agent's id, system prompt and max steps, its tools' names and definitions sorted by name, its
   * middleware ids in registration order with each middleware's contributed tools' names and
   * definitions, and its handoff targets with `preserveContext`. Completion
   * options, streaming, breakpoints and the model client are not part of it: they are rebound when a thread is
   * restored.
   */
  private[agent] def fingerprint(family: Vector[AgentBuilder]): String =
    val canonical = ujson.Arr.from(family.sortBy(_.id).map(_.canonical)).render()
    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString
