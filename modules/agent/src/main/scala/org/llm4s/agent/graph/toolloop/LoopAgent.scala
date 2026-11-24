package org.llm4s.agent.graph.toolloop

import org.llm4s.annotation.Experimental
import org.llm4s.agent.AgentId
import org.llm4s.agent.events.ModelCallCompleted
import org.llm4s.agent.graph.VersionedJson
import org.llm4s.agent.graph.middleware.AgentMiddleware
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.llmconnect.model.{ Message, UsageSummary }
import upickle.default.ReadWriter

/**
 * One agent of a [[ToolLoop]] family: its model, tools, middleware, system prompt, step limit and
 * handoffs. Its nodes are prefixed with its `id`.
 *
 * @param systemPrompt sent first on every model call of this agent, never stored in the history
 * @param maxSteps the limit this agent's model node applies to the turn's step count, which every
 *   agent of the turn shares: at it the turn ends with [[TurnOutcome.StepLimitReached]] instead of a
 *   further call, so an agent handed a turn that has already used its limit makes no call
 * @param maxPromptSectionTokens the most every middleware's prompt sections may add to one request together,
 *   estimated at four characters a token: a request over it fails the run (design §4.22)
 * @param output the response format this agent's answers must match (design 4.26), or none
 */
@Experimental
final case class LoopAgent private (
  id: AgentId,
  model: ModelStep,
  tools: ToolSet,
  middleware: Seq[AgentMiddleware],
  systemPrompt: Option[String],
  maxSteps: Int,
  handoffs: Vector[LoopHandoff],
  maxPromptSectionTokens: Int,
  output: Option[OutputFormat]
):
  def withId(id: AgentId): LoopAgent                              = copy(id = id)
  def withModel(model: ModelStep): LoopAgent                      = copy(model = model)
  def withTools(tools: ToolSet): LoopAgent                        = copy(tools = tools)
  def withSystemPrompt(prompt: String): LoopAgent                 = withSystemPrompt(Some(prompt))
  def withSystemPrompt(prompt: Option[String]): LoopAgent         = copy(systemPrompt = prompt)
  def withMaxSteps(steps: Int): LoopAgent                         = copy(maxSteps = steps)
  def withMiddleware(middleware: Seq[AgentMiddleware]): LoopAgent = copy(middleware = middleware)
  def withHandoffs(handoffs: Vector[LoopHandoff]): LoopAgent      = copy(handoffs = handoffs)
  def withMaxPromptSectionTokens(n: Int): LoopAgent               = copy(maxPromptSectionTokens = n)
  def withOutput(format: OutputFormat): LoopAgent                 = withOutput(Some(format))
  def withOutput(format: Option[OutputFormat]): LoopAgent         = copy(output = format)

@Experimental
object LoopAgent:

  /** The prompt section budget of an agent that sets none. */
  val DefaultMaxPromptSectionTokens: Int = 8192

  /** The most prompt sections one request may carry, whatever their size: more fail the run. */
  val MaxPromptSections: Int = 64

  /**
   * An agent with no system prompt, no middleware, no handoffs, no response format, a limit of 50 steps a turn and
   * the default prompt section budget.
   */
  def apply(id: AgentId, model: ModelStep, tools: ToolSet = ToolSet.empty): LoopAgent =
    new LoopAgent(id, model, tools, Nil, None, 50, Vector.empty, DefaultMaxPromptSectionTokens, None)

/** A transfer the agent may make to `target`; with `preserveContext = false` the target sees only the transfer onwards. */
@Experimental
final case class LoopHandoff private (target: AgentId, reason: Option[String], preserveContext: Boolean):
  def withTarget(value: AgentId): LoopHandoff          = copy(target = value)
  def withReason(value: String): LoopHandoff           = copy(reason = Some(value))
  def withReason(value: Option[String]): LoopHandoff   = copy(reason = value)
  def withPreserveContext(value: Boolean): LoopHandoff = copy(preserveContext = value)

@Experimental
object LoopHandoff:
  def apply(target: AgentId, reason: Option[String], preserveContext: Boolean): LoopHandoff =
    new LoopHandoff(target, reason, preserveContext)

/**
 * The last handoff the thread made: from `source` to `target`, by the stored assistant message
 * `messageId`. Recorded by the model node when it routes a transfer; the target's model reads it to
 * build the view a transfer without preserved context sends.
 */
@Experimental
final case class Transfer private (source: AgentId, target: AgentId, messageId: String) derives ReadWriter:
  def withSource(value: AgentId): Transfer   = copy(source = value)
  def withTarget(value: AgentId): Transfer   = copy(target = value)
  def withMessageId(value: String): Transfer = copy(messageId = value)

@Experimental
object Transfer:
  def apply(source: AgentId, target: AgentId, messageId: String): Transfer =
    new Transfer(source, target, messageId)

/**
 * What the admission stage did with a turn's query before the turn was stored (#2854), kept with the turn's input so
 * that its input step neither runs the stage again nor loses what it counted. It holds no text of the query.
 *
 * @param agents the agents whose `admit` stacks ran: the input step runs any other owner's itself
 * @param usage the usage of the model calls the stages made, which the input step adds to the thread's
 * @param calls the event of each of those calls, which the input step announces
 * @param refusedBy the guardrail or middleware that refused the query, when one did: the input then holds no
 *   query, and its input step blocks the turn in that name
 */
@Experimental
final case class Admission private (
  agents: Vector[AgentId],
  usage: UsageSummary = UsageSummary(),
  calls: Vector[ModelCallCompleted] = Vector.empty,
  refusedBy: Option[String] = None
) derives ReadWriter:
  def withAgents(value: Vector[AgentId]): Admission           = copy(agents = value)
  def withUsage(value: UsageSummary): Admission               = copy(usage = value)
  def withCalls(value: Vector[ModelCallCompleted]): Admission = copy(calls = value)
  def withRefusedBy(value: String): Admission                 = copy(refusedBy = Some(value))
  def withRefusedBy(value: Option[String]): Admission         = copy(refusedBy = value)

@Experimental
object Admission:
  def apply(
    agents: Vector[AgentId],
    usage: UsageSummary = UsageSummary(),
    calls: Vector[ModelCallCompleted] = Vector.empty,
    refusedBy: Option[String] = None
  ): Admission = new Admission(agents, usage, calls, refusedBy)

/**
 * One turn's input: the user's query and, on a new thread only, the conversation to import before it.
 *
 * `admission` is what the admission stage made of the query (#2854), when it ran before the turn was stored: the
 * `query` is then the stage's - rewritten, or empty for one it refused - and never the query as submitted. `None` for
 * an input nothing has admitted yet, a stored one written before admission existed included: its input step runs the
 * stage.
 */
@Experimental
final case class AgentInput private (
  query: String,
  history: Vector[Message] = Vector.empty,
  admission: Option[Admission] = None
) derives ReadWriter:
  def withQuery(value: String): AgentInput                = copy(query = value)
  def withHistory(value: Vector[Message]): AgentInput     = copy(history = value)
  def withAdmission(value: Admission): AgentInput         = copy(admission = Some(value))
  def withAdmission(value: Option[Admission]): AgentInput = copy(admission = value)

  /** Whether `agent`'s admission stack has run on this input. */
  private[agent] def admittedBy(agent: AgentId): Boolean = admission.exists(_.agents.contains(agent))

  /** The guardrail or middleware that refused the query at admission, if one did. */
  private[agent] def refusedBy: Option[String] = admission.flatMap(_.refusedBy)

@Experimental
object AgentInput:
  def apply(
    query: String,
    history: Vector[Message] = Vector.empty,
    admission: Option[Admission] = None
  ): AgentInput = new AgentInput(query, history, admission)

/** How a turn ended. */
@Experimental
enum TurnOutcome derives ReadWriter:
  /** The active agent gave a final answer. */
  case Completed

  /** The active agent used its `maxSteps` model calls without a final answer. */
  case StepLimitReached

  /** The run used its `RunBudgets.maxTokens` without a final answer; no model call was made at the budget. */
  case TokenLimitReached

  /** The active agent's final answer was cut off at the model's output limit (`FinishReason.Length`). */
  case Truncated

/**
 * What an output Block needs to undo a turn's writes to one key: its `value` before the turn first wrote it,
 * encoded with the key's codec (`None`: no stored entry), and the fingerprint of what the turn's own writes last
 * left there (`written`; `None`: no stored entry). A Block restores the key only while it still holds `written`,
 * so a later write by anyone else - `updateState` while the turn waits for review - is kept (design §4.22).
 */
@Experimental
final case class PriorValue(value: Option[VersionedJson], written: Option[String]) derives ReadWriter

/**
 * The current turn: the model calls it has made, its outcome once it has one, and what an output Block restores
 * when it retracts the turn - the agent and transfer it started with, and `priorValues`: by key id, each key the
 * turn may write, as it was before the turn and as the turn left it ([[PriorValue]]), one entry per key. Reset by
 * each turn's input; a turn that ends clears `priorValues` (design §4.22).
 */
@Experimental
final case class TurnState private (
  steps: Int,
  outcome: Option[TurnOutcome],
  startAgent: Option[AgentId] = None,
  startTransfer: Option[Transfer] = None,
  priorValues: Map[String, PriorValue] = Map.empty,
  /** Whether the reply stored last ended at the model's output limit (`FinishReason.Length`); `false` when read from a turn stored before it. */
  cut: Boolean = false
) derives ReadWriter:
  def withSteps(value: Int): TurnState                           = copy(steps = value)
  def withOutcome(value: TurnOutcome): TurnState                 = copy(outcome = Some(value))
  def withOutcome(value: Option[TurnOutcome]): TurnState         = copy(outcome = value)
  def withStartAgent(value: AgentId): TurnState                  = copy(startAgent = Some(value))
  def withStartAgent(value: Option[AgentId]): TurnState          = copy(startAgent = value)
  def withStartTransfer(value: Transfer): TurnState              = copy(startTransfer = Some(value))
  def withStartTransfer(value: Option[Transfer]): TurnState      = copy(startTransfer = value)
  def withPriorValues(value: Map[String, PriorValue]): TurnState = copy(priorValues = value)
  def withCut(value: Boolean): TurnState                         = copy(cut = value)

@Experimental
object TurnState:
  def apply(
    steps: Int,
    outcome: Option[TurnOutcome],
    startAgent: Option[AgentId] = None,
    startTransfer: Option[Transfer] = None,
    priorValues: Map[String, PriorValue] = Map.empty,
    cut: Boolean = false
  ): TurnState =
    new TurnState(steps, outcome, startAgent, startTransfer, priorValues, cut)

/** A turn's result: how it ended, and the agent active at its end - the one the next turn starts with. */
@Experimental
final case class TurnOutput private (outcome: TurnOutcome, activeAgent: AgentId) derives ReadWriter:
  def withOutcome(value: TurnOutcome): TurnOutput = copy(outcome = value)
  def withActiveAgent(value: AgentId): TurnOutput = copy(activeAgent = value)

@Experimental
object TurnOutput:
  def apply(outcome: TurnOutcome, activeAgent: AgentId): TurnOutput =
    new TurnOutput(outcome, activeAgent)
