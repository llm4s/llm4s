package org.llm4s.agent

import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, MessageRole }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import cats.implicits._

import scala.util.Try

/**
 * Pure and nearly-pure helpers for the handoff-as-tool delegation pattern.
 *
 * == Why handoffs are synthesised as tools ==
 *
 * Representing handoffs as synthetic LLM tool calls keeps the LLM in full
 * control of '''when''' to delegate.  Hard-coded routing logic (e.g. "if the
 * query contains 'physics' forward to the science agent") is brittle and
 * requires code changes for every new domain.  A synthesised tool lets the
 * LLM apply its own reasoning about relevance, which is both more flexible
 * and more accurate.
 *
 * == Why `preserveContext=false` transfers only the last user message ==
 *
 * Specialist agents often have their own system prompts and tool sets that
 * conflict with the source agent's context.  Sending the full conversation
 * history also leaks data the target agent has no need for and increases
 * token cost unnecessarily.  The default (`preserveContext=false`) sends only
 * the user's most recent question so the specialist starts with a clean slate.
 *
 * == Why tracing context is propagated unchanged ==
 *
 * End-to-end observability across multi-agent runs requires all spans to share
 * the same trace ID.  Propagating the [[AgentContext]] unchanged ensures that
 * the target agent's spans are nested under the same root trace as the source
 * agent, giving operators a single timeline view.
 */
private[agent] object HandoffExecutor {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Converts each [[Handoff]] into a synthetic [[ToolFunction]] the LLM can
   * invoke to trigger delegation.
   *
   * The generated tool name is [[Handoff.handoffId]] so that
   * [[detectHandoff]] can identify it by the `handoff_to_agent_` prefix.
   * The tool schema exposes a single required `reason` field that the LLM must
   * populate, giving operators visibility into why the delegation occurred.
   *
   * @param handoffs Handoffs to convert; may be empty.
   * @return `Right(tools)` — one tool per handoff — or `Left` if tool creation
   *         fails (e.g. invalid schema definition).
   */
  def createHandoffTools(handoffs: Seq[Handoff]): Result[Seq[ToolFunction[_, _]]] = {
    import HandoffResult._

    handoffs.traverse { handoff =>
      val toolName = handoff.handoffId
      val toolDescription = handoff.transferReason.fold(
        "Hand off this query to a specialist agent."
      )(reason => s"Hand off this query to a specialist agent. $reason")

      val schema = Schema
        .`object`[Map[String, Any]]("Handoff parameters")
        .withRequiredField("reason", Schema.string("Reason for the handoff"))

      ToolBuilder[Map[String, Any], HandoffResult](
        toolName,
        toolDescription,
        schema
      ).withHandler { extractor =>
        extractor.getString("reason").map { reason =>
          HandoffResult(
            handoff_requested = true,
            handoff_id = handoff.handoffId,
            reason = reason
          )
        }
      }.buildSafe()
    }
  }

  /**
   * Scans the most recent assistant message in `state` for a handoff tool call.
   *
   * Returns `None` when there is no assistant message with tool calls, or when
   * none of the tool calls have the `handoff_to_agent_` prefix.  Returns
   * `Some((handoff, reason))` on the '''first''' matching tool call — multiple
   * handoffs in a single turn are not supported (only the first is executed).
   *
   * @param state Current agent state; [[AgentState.availableHandoffs]] is used
   *              to match tool-call names to [[Handoff]] instances.
   * @return `Some((handoff, reason))` if a handoff was requested; `None` otherwise.
   */
  def detectHandoff(state: AgentState): Option[(Handoff, String)] = {
    val latestAssistantMessage = state.conversation.messages.reverse
      .collectFirst { case msg: AssistantMessage if msg.toolCalls.nonEmpty => msg }

    latestAssistantMessage.flatMap { assistantMessage =>
      val handoffToolCalls = assistantMessage.toolCalls.filter(tc => tc.name.startsWith("handoff_to_agent_"))

      handoffToolCalls.headOption.flatMap { toolCall =>
        val reasonOpt = Try {
          val args = ujson.read(toolCall.arguments)
          args.obj.get("reason").map(_.str).getOrElse("No reason provided")
        }.toOption

        val handoffId  = toolCall.name
        val handoffOpt = state.availableHandoffs.find(_.handoffId == handoffId)

        handoffOpt.flatMap(handoff => reasonOpt.map(reason => (handoff, reason)))
      }
    }
  }

  /**
   * Builds the initial [[AgentState]] for the handoff target agent.
   *
   * The conversation messages transferred are controlled by
   * [[Handoff.preserveContext]]:
   *  - `true`  — full conversation history is transferred.
   *  - `false` — only the last user message is transferred (privacy / cost).
   *
   * [[Handoff.transferSystemMessage]] controls whether the source agent's
   * system message is forwarded.  The target agent starts with no available
   * handoffs of its own, preventing unintended chain-delegation.
   *
   * @param sourceState State from the source agent at the point of delegation.
   * @param handoff     Handoff configuration driving the transfer behaviour.
   * @param reason      Optional reason string provided by the LLM; appended to
   *                    the initial log entry for the target agent.
   * @return Initialised state ready to be passed to the target agent's `run`.
   */
  def buildHandoffState(
    sourceState: AgentState,
    handoff: Handoff,
    reason: Option[String]
  ): AgentState = {
    val transferredMessages = if (handoff.preserveContext) {
      sourceState.conversation.messages
    } else {
      sourceState.conversation.messages
        .findLast(_.role == MessageRole.User)
        .toVector
    }

    val conversation = Conversation(transferredMessages)

    val systemMessage = if (handoff.transferSystemMessage) {
      sourceState.systemMessage
    } else {
      None
    }

    val handoffLog = s"[handoff] Received handoff from agent" +
      reason.map(r => s" (Reason: $r)").getOrElse("")

    AgentState(
      conversation = conversation,
      tools = ToolRegistry.empty,
      initialQuery = sourceState.initialQuery,
      status = AgentStatus.InProgress,
      logs = Vector(handoffLog),
      systemMessage = systemMessage,
      availableHandoffs = Seq.empty
    )
  }

  /**
   * Runs the target agent from the prepared handoff state and merges usage.
   *
   * Tracing context is propagated unchanged to the target agent to preserve
   * end-to-end trace continuity across multi-agent flows (see class-level note).
   *
   * @param sourceState The state from the source agent at delegation time.
   * @param handoff     The handoff configuration (carries the target [[Agent]]).
   * @param reason      Optional LLM-provided reason for the delegation.
   * @param maxSteps    Step budget forwarded to the target agent.
   * @param context     Cross-cutting concerns (tracing, debug, trace log path).
   * @return `Right(state)` from the target agent with merged usage; `Left` on
   *         LLM or initialisation error in the target agent.
   */
  def executeHandoff(
    sourceState: AgentState,
    handoff: Handoff,
    reason: Option[String],
    maxSteps: Option[Int],
    context: AgentContext
  ): Result[AgentState] = {
    val logEntry = s"[handoff] Executing handoff: ${handoff.handoffName}" +
      reason.map(r => s" (Reason: $r)").getOrElse("")

    if (context.debug) {
      logger.info("[DEBUG] {}", logEntry)
      logger.info("[DEBUG] preserveContext: {}", handoff.preserveContext)
      logger.info("[DEBUG] transferSystemMessage: {}", handoff.transferSystemMessage)
    }

    val targetState = buildHandoffState(sourceState, handoff, reason)

    if (context.debug) {
      logger.info("[DEBUG] Target state conversation messages: {}", targetState.conversation.messages.length)
      logger.info("[DEBUG] Target state system message: {}", targetState.systemMessage.isDefined)
    }

    handoff.targetAgent.run(targetState, maxSteps, context).map { targetFinalState =>
      targetFinalState.copy(
        usageSummary = sourceState.usageSummary.merge(targetFinalState.usageSummary)
      )
    }
  }
}
