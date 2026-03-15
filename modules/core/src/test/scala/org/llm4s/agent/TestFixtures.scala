package org.llm4s.agent

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result

/**
 * Deterministic fake LLM client that always returns the same completion.
 *
 * Use when:
 *  - The test only needs one LLM response (e.g. a single `run` with no tool calls).
 *  - The response content is irrelevant and only the state transition matters.
 *
 * Prefer over `MockLLMClient` when the single-response constraint makes the
 * test intent clearer and eliminates index-management boilerplate.
 */
final private[agent] class DeterministicFakeLLMClient(
  response: Completion
) extends LLMClient {

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] =
    Right(response)

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options)

  override def getContextWindow(): Int = 128000

  override def getReserveCompletion(): Int = 4096
}

/**
 * Deterministic fake LLM client that returns `first` until it sees a tool
 * result or assistant message in the conversation, then returns `second`.
 *
 * Use when:
 *  - The test covers a one-tool-call round-trip (LLM → tool → LLM).
 *  - The exact number of turns is two and both responses are meaningful.
 *
 * The turn-detection heuristic (presence of Tool or Assistant messages) is
 * intentionally simple — use `NTurnFakeLLMClient` when more precision is
 * needed.
 */
final private[agent] class TwoTurnDeterministicFakeLLMClient(
  first: Completion,
  second: Completion
) extends LLMClient {

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = {
    val hasToolResult =
      conversation.messages.exists(_.role == org.llm4s.llmconnect.model.MessageRole.Tool)
    val hasAssistantMessage =
      conversation.messages.exists(_.role == org.llm4s.llmconnect.model.MessageRole.Assistant)
    if (hasToolResult || hasAssistantMessage) Right(second) else Right(first)
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options)

  override def getContextWindow(): Int = 128000

  override def getReserveCompletion(): Int = 4096
}

/**
 * LLM client that always returns `Left(error)`.
 *
 * Use when:
 *  - The test verifies error-propagation paths (e.g. `run` returns `Left` when
 *    the LLM call fails).
 *  - You need to simulate a hard LLM failure on the first (or any) call.
 *
 * Unlike `MockLLMClient` with an error response, this client makes the failure
 * intent immediately obvious from the class name alone.
 */
final private[agent] class FailingLLMClient(error: LLMError) extends LLMClient {

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] =
    Left(error)

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    Left(error)

  override def getContextWindow(): Int = 128000

  override def getReserveCompletion(): Int = 4096
}

/**
 * LLM client that rotates through N pre-configured responses.
 *
 * Call N is answered by `responses(N % responses.size)`.  If `responses` is
 * empty, every call returns a default text-only completion.
 *
 * Use when:
 *  - The test exercises exactly N LLM turns with distinct responses.
 *  - You want predictable, index-based turn control without a mutable counter.
 */
final private[agent] class NTurnFakeLLMClient(responses: Completion*) extends LLMClient {

  private var callIndex = 0

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = {
    val result =
      if (responses.isEmpty)
        Right(CompletionFixture.simple(s"turn-$callIndex"))
      else
        Right(responses(callIndex % responses.size))
    callIndex += 1
    result
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options)

  override def getContextWindow(): Int = 128000

  override def getReserveCompletion(): Int = 4096
}

/**
 * Factory methods for building [[Completion]] instances in tests.
 *
 * Reduces boilerplate in tests that only care about one or two fields of the
 * completion and want sensible defaults for everything else.
 */
private[agent] object CompletionFixture {

  /**
   * A plain text completion with no tool calls and synthetic token usage.
   *
   * @param text The assistant's response text.
   */
  def simple(text: String): Completion = {
    val message = AssistantMessage(text, Seq.empty)
    Completion(
      id = s"fixture-${System.nanoTime()}",
      created = System.currentTimeMillis(),
      content = text,
      model = "test-model",
      message = message,
      toolCalls = Nil,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )
  }

  /**
   * A completion whose only content is a single tool call.
   *
   * @param name   Tool name.
   * @param args   Tool arguments as a ujson value (typically `ujson.Obj(...)`).
   * @param callId Tool call identifier; defaults to `"call-1"`.
   */
  def withToolCall(name: String, args: ujson.Value, callId: String = "call-1"): Completion = {
    val tc      = ToolCall(id = callId, name = name, arguments = args)
    val message = AssistantMessage("", Seq(tc))
    Completion(
      id = s"fixture-tc-${System.nanoTime()}",
      created = System.currentTimeMillis(),
      content = "",
      model = "test-model",
      message = message,
      toolCalls = List(tc),
      usage = Some(TokenUsage(promptTokens = 15, completionTokens = 5, totalTokens = 20))
    )
  }

  /**
   * A plain text completion with explicit token usage.
   *
   * Use when the test verifies usage accumulation or cost tracking.
   *
   * @param text       The assistant's response text.
   * @param prompt     Prompt token count.
   * @param completion Completion token count.
   */
  def withUsage(text: String, prompt: Int, completion: Int): Completion = {
    val message = AssistantMessage(text, Seq.empty)
    Completion(
      id = s"fixture-usage-${System.nanoTime()}",
      created = System.currentTimeMillis(),
      content = text,
      model = "test-model",
      message = message,
      toolCalls = Nil,
      usage = Some(TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = prompt + completion))
    )
  }
}

/**
 * Factory methods for building [[AgentState]] instances in tests.
 *
 * These builders create the minimum viable state for each scenario and leave
 * all other fields at their defaults, keeping test setup concise.
 */
private[agent] object AgentStateFixture {

  /**
   * An [[AgentState]] ready for its first [[Agent.runStep]] call.
   *
   * @param query The user's initial question.
   * @param tools Tool registry; defaults to an empty registry.
   */
  def initial(query: String, tools: ToolRegistry = ToolRegistry.empty): AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage(query))),
      tools = tools,
      initialQuery = Some(query),
      status = AgentStatus.InProgress
    )

  /**
   * An [[AgentState]] in terminal `Complete` status with both a user message
   * and a final assistant response.
   *
   * Use to construct the `previousState` argument for
   * [[Agent.continueConversation]] tests.
   *
   * @param query    The user's question.
   * @param response The assistant's final response.
   */
  def complete(query: String, response: String): AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage(query), AssistantMessage(response, Seq.empty))),
      tools = ToolRegistry.empty,
      initialQuery = Some(query),
      status = AgentStatus.Complete
    )

  /**
   * An [[AgentState]] with exactly the supplied messages in its conversation.
   *
   * The state is `InProgress`; override with `.withStatus(...)` if needed.
   *
   * @param msgs Ordered sequence of messages forming the conversation.
   */
  def withMessages(msgs: Message*): AgentState =
    AgentState(
      conversation = Conversation(msgs.toSeq),
      tools = ToolRegistry.empty,
      status = AgentStatus.InProgress
    )
}
