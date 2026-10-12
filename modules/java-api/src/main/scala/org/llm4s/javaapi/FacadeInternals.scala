package org.llm4s.javaapi

import org.llm4s.agent.{ AgentResult, AgentStatus }
import org.llm4s.agent.graph.{ BreakpointPhase => GraphPhase, InterruptId }
import org.llm4s.agent.graph.toolloop.{
  ApprovalRequest,
  BreakpointRequest,
  MiddlewareQuestionRequest,
  ToolQuestionRequest
}
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  Message,
  ModelUsage,
  SystemMessage,
  ThinkingBlock,
  TokenUsage,
  ToolCall,
  ToolMessage,
  UsageSummary,
  UserMessage
}
import org.llm4s.metrics.MetricsCollector
import org.llm4s.types.Result

import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * Not part of the API: what the facade's classes share - building a client, reading core's Scala
 * values into the facade's Java value types, and wrapping a `Result` in an [[LlmResult]].
 *
 * These lived on the facade types' own companions as `private[javaapi]` factories, which Scala 3 emits as `public
 * static` methods of the types themselves (#1666). Here they are out of a Java caller's way: every method takes a
 * Scala or core type, and no facade signature names this object.
 */
private[llm4s] object FacadeInternals {

  /** `result` as Java callers read it. */
  def result[A](result: Result[A]): LlmResult[A] = new LlmResult(FacadeToken.INSTANCE, result)

  /**
   * What both public `Llm4s.createClient` overloads do: the client for `config`, reporting every call to `metrics` (a
   * null `metrics` records nothing; a null `config` is a failed result).
   */
  def createClient(config: ProviderConfig, metrics: MetricsCollector): LlmResult[JLlmClient] =
    if (config == null) LlmResult.failure(ValidationError.required("config"))
    else
      result(for {
        registry <- Llm4sConfig.modelRegistryService()
        client   <- LLMConnect.getClient(config, Option(metrics).getOrElse(MetricsCollector.noop))(using registry)
      } yield Llm4s.wrapClient(client))

  /** `agentResult` as Java and Kotlin callers read it. */
  def agentResult(agentResult: AgentResult): JAgentResult =
    new JAgentResult(
      agentResult.threadId.value,
      agentResult.runId.value,
      agentResult.activeAgent.value,
      agentStatus(agentResult.status),
      java.util.List.copyOf(agentResult.messages.map(message).asJava),
      usageSummary(agentResult.usage)
    )

  /** `status` as Java and Kotlin callers read it; a `Suspended` one's interrupts via [[pending]]. */
  def agentStatus(status: AgentStatus): JAgentStatus = status match {
    case AgentStatus.Completed(answer) =>
      new JAgentStatus(AgentStatusKind.COMPLETED, Optional.ofNullable(answer), Optional.empty, Optional.empty, none)
    case AgentStatus.Blocked(guardrail, reason) =>
      new JAgentStatus(
        AgentStatusKind.BLOCKED,
        Optional.empty,
        Optional.ofNullable(guardrail),
        Optional.ofNullable(reason),
        none
      )
    case AgentStatus.StepLimitReached =>
      new JAgentStatus(AgentStatusKind.STEP_LIMIT_REACHED, Optional.empty, Optional.empty, Optional.empty, none)
    case suspended: AgentStatus.Suspended =>
      new JAgentStatus(AgentStatusKind.SUSPENDED, Optional.empty, Optional.empty, Optional.empty, pending(suspended))
  }

  private def none: java.util.List[PendingInterrupt] = java.util.List.of()

  /**
   * The interrupts `status` waits for: a `Suspended` turn's approvals, then its tool questions, its middleware
   * questions and its breakpoints, each in the order the turn lists them; none for any other status. An
   * unmodifiable `java.util.List`.
   */
  def pending(status: AgentStatus): java.util.List[PendingInterrupt] = status match {
    case AgentStatus.Suspended(approvals, questions, asked, breakpoints) =>
      val all = approvals.map((id, request) => approval(id, request)) ++
        questions.map((id, request) => question(id, request)) ++
        asked.map((id, request) => middlewareQuestion(id, request)) ++
        breakpoints.map((id, request) => breakpoint(id, request))
      java.util.List.copyOf(all.asJava)
    case _ => none
  }

  private def approval(id: InterruptId, request: ApprovalRequest): PendingInterrupt =
    interrupt(id, InterruptKind.APPROVAL, Some(request.call), Option(request.reason), None, None)

  private def question(id: InterruptId, request: ToolQuestionRequest): PendingInterrupt =
    interrupt(id, InterruptKind.QUESTION, Some(request.call), Option(request.question).map(_.render()), None, None)

  private def middlewareQuestion(id: InterruptId, request: MiddlewareQuestionRequest): PendingInterrupt =
    interrupt(
      id,
      InterruptKind.MIDDLEWARE_QUESTION,
      request.call.map(_.call),
      Option(request.question).map(_.render()),
      Some(request.middleware.value),
      None
    )

  private def breakpoint(id: InterruptId, request: BreakpointRequest): PendingInterrupt = {
    val phase = request.phase match {
      case GraphPhase.Before => BreakpointPhase.BEFORE
      case GraphPhase.After  => BreakpointPhase.AFTER
    }
    interrupt(id, InterruptKind.BREAKPOINT, request.call, None, None, Some(request.node.value -> phase))
  }

  private def interrupt(
    id: InterruptId,
    kind: InterruptKind,
    call: Option[ToolCall],
    detail: Option[String],
    middleware: Option[String],
    held: Option[(String, BreakpointPhase)]
  ): PendingInterrupt =
    new PendingInterrupt(
      id.value,
      kind,
      call.map(_.name).orNull,
      call.map(_.arguments.render()).orNull,
      detail.orNull,
      middleware.orNull,
      held.map(_._1).orNull,
      held.map(_._2).orNull
    )

  /** `message` as Java and Kotlin callers read it. */
  def message(message: Message): JMessage = message match {
    case UserMessage(content)   => plain(JMessageRole.USER, content)
    case SystemMessage(content) => plain(JMessageRole.SYSTEM, content)
    case assistant: AssistantMessage =>
      new JMessage(
        JMessageRole.ASSISTANT,
        text(assistant.content),
        java.util.List.copyOf(assistant.toolCalls.map(toolCall).asJava),
        Optional.empty,
        ThinkingBlock.text(assistant.thinking).toJava
      )
    case ToolMessage(content, toolCallId) =>
      new JMessage(
        JMessageRole.TOOL,
        text(content),
        java.util.List.of(),
        Optional.ofNullable(toolCallId),
        Optional.empty
      )
  }

  private def plain(role: JMessageRole, content: String): JMessage =
    new JMessage(role, text(content), java.util.List.of(), Optional.empty, Optional.empty)

  /** `content`, with a `null` - which a Scala message can hold - read as empty, as `JMessage.content` promises. */
  private def text(content: String): String = Option(content).getOrElse("")

  /** `call` as Java and Kotlin callers read it. */
  def toolCall(call: ToolCall): JToolCall = new JToolCall(call.id, call.name, call.arguments.render())

  /** `usage` as Java and Kotlin callers read it. */
  def usageSummary(usage: UsageSummary): JUsageSummary = {
    val byModel =
      new java.util.TreeMap[String, JModelUsage](usage.byModel.map((m, u) => m -> modelUsage(u)).asJava)
    new JUsageSummary(
      usage.requestCount,
      usage.inputTokens,
      usage.outputTokens,
      usage.thinkingTokens,
      usage.totalCost.bigDecimal,
      java.util.Collections.unmodifiableMap(byModel)
    )
  }

  /** `usage` as Java and Kotlin callers read it. */
  def modelUsage(usage: ModelUsage): JModelUsage =
    new JModelUsage(
      usage.requestCount,
      usage.inputTokens,
      usage.outputTokens,
      usage.thinkingTokens,
      usage.totalCost.bigDecimal
    )

  /** `completion` as Java and Kotlin callers read it. */
  def completion(completion: Completion): JCompletion =
    new JCompletion(
      completion.id,
      Option(completion.content).getOrElse(""),
      completion.model,
      java.util.List.copyOf(completion.toolCalls.map(toolCall).asJava),
      completion.usage.map(tokenUsage).toJava,
      // as core's `ModelUsage.add` converts it, so a reply's cost and an agent turn's total agree; a cost that is not a
      // number has no decimal value and reads as unknown
      completion.estimatedCost.filter(_.isFinite).map(BigDecimal.decimal(_).bigDecimal).toJava,
      completion.thinking.toJava
    )

  /** `usage` as Java and Kotlin callers read it: a count the provider did not report is zero. */
  def tokenUsage(usage: TokenUsage): JTokenUsage =
    new JTokenUsage(
      usage.promptTokens,
      usage.completionTokens,
      usage.totalTokens,
      usage.thinkingTokens.getOrElse(0),
      usage.cachedTokens.getOrElse(0),
      usage.cacheCreationTokens.getOrElse(0)
    )
}
