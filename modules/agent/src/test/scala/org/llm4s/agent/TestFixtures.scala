package org.llm4s.agent

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
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

  private val callIndex = new java.util.concurrent.atomic.AtomicInteger(0)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] = {
    val index = callIndex.getAndIncrement()
    if (responses.isEmpty) Right(CompletionFixture.simple(s"turn-$index"))
    else Right(responses(index % responses.size))
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

  /** A completion of `message`, its tool calls included, with the same usage as [[simple]]. */
  def withMessage(message: AssistantMessage): Completion =
    Completion(
      id = s"fixture-msg-${System.nanoTime()}",
      created = System.currentTimeMillis(),
      content = message.content,
      model = "test-model",
      message = message,
      toolCalls = message.toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 20, totalTokens = 30))
    )

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
 * LLM client that answers call N with `responses(N)`, and records every conversation and options
 * it was called with. Past the end of `responses` it answers `Left(ValidationError)`, so a test
 * that makes more calls than it scripted fails rather than looping. Thread-safe: the agent calls it
 * from its run thread.
 */
final private[agent] class ScriptedLLMClient(responses: Result[Completion]*) extends LLMClient {

  private val recorded = new java.util.concurrent.CopyOnWriteArrayList[(Conversation, CompletionOptions)]()

  /** Every call so far: the conversation sent and the options. */
  def calls: Vector[(Conversation, CompletionOptions)] = {
    import scala.jdk.CollectionConverters._
    recorded.asScala.toVector
  }

  /** The messages each call was sent. */
  def sent: Vector[Vector[Message]] = calls.map(_._1.messages.toVector)

  def callCount: Int = recorded.size

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    val index = recorded.size
    recorded.add(conversation -> options)
    responses
      .lift(index)
      .getOrElse(Left(org.llm4s.error.ValidationError("scripted", s"no response scripted for call $index")))
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = complete(conversation, options)

  override def getContextWindow(): Int = 128000

  override def getReserveCompletion(): Int = 4096
}

private[agent] object ScriptedLLMClient {

  /** Answers each call in turn with one of `completions`. */
  def of(completions: Completion*): ScriptedLLMClient = new ScriptedLLMClient(completions.map(Right(_))*)
}

/** Builders for agents in tests: building must succeed. */
private[agent] object AgentFixture {
  import org.scalatest.Assertions.fail

  /** `builder` built, failing the test if it is refused. */
  def built(builder: AgentBuilder): Agent = builder.build().fold(e => fail(s"build failed: ${e.message}"), identity)

  /** An agent with id `assistant` over `client` and no tools. */
  def plain(client: LLMClient): Agent = built(Agent.builder("assistant", client))

  extension (result: Result[AgentResult]) {

    /** The result, failing the test on `Left`. */
    def value: AgentResult = result.fold(e => fail(s"expected Right, got ${e.message}"), identity)

    /** The error, failing the test on `Right`. */
    def error: org.llm4s.error.LLMError = result.fold(identity, r => fail(s"expected Left, got ${r.status}"))
  }

  /** `error`'s cause when it is a failed node's, else `error` itself. */
  def cause(error: org.llm4s.error.LLMError): org.llm4s.error.LLMError = error match {
    case org.llm4s.agent.graph.GraphError.NodeFailed(_, _, cause) => cause
    case other                                                    => other
  }
}

/** Builds an [[AgentResult]] directly, for tests outside `org.llm4s.agent` that need one of a given status. */
object AgentResultFixture {
  def apply(
    status: AgentStatus,
    messages: Vector[Message] = Vector.empty,
    threadId: org.llm4s.agent.graph.ThreadId = org.llm4s.agent.graph.ThreadId("fixture-thread")
  ): AgentResult =
    AgentResult(
      threadId,
      org.llm4s.agent.graph.RunId("fixture-run"),
      AgentId.unsafe("assistant"),
      status,
      messages,
      UsageSummary()
    )
}

/** Agent tools for specs: a one-argument tool whose body is a function of the call. */
private[agent] object SpecTools {
  import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContext, ToolOutcome }
  import org.llm4s.toolapi.Schema
  import upickle.default.ReadWriter

  final case class Text(text: String) derives ReadWriter

  def spec(name: String): AgentToolSpec[Text] =
    AgentToolSpec[Text](
      name,
      s"The $name tool",
      Schema.`object`[Text](name).withRequiredField("text", Schema.string("Text"))
    )

  def set(tools: AgentTool[?]*): org.llm4s.agent.graph.tool.ToolSet =
    org.llm4s.agent.graph.tool.ToolSet.of(tools*).fold(e => org.scalatest.Assertions.fail(e.message), identity)

  def tool(name: String)(run: (Text, ToolContext) => ToolOutcome): AgentTool[Text] = AgentTool(spec(name))(run)

  def call(id: String, name: String, text: String = "x"): ToolCall = ToolCall(id, name, ujson.Obj("text" -> text))

  def calling(calls: ToolCall*): Completion = CompletionFixture.withMessage(AssistantMessage(None, calls))
}
