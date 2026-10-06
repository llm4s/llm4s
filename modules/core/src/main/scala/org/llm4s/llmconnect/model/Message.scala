package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.{ macroRW, read, readwriter, write, ReadWriter => RW }

/**
 * A single turn in an LLM conversation.
 *
 * Conversations are sequences of `Message` values passed to [[org.llm4s.llmconnect.LLMClient]].
 * Each concrete subtype corresponds to one participant role:
 * [[UserMessage]], [[SystemMessage]], [[AssistantMessage]], [[ToolMessage]].
 *
 * `content` is always a non-null, non-empty string for well-formed messages —
 * use [[validate]] or the smart constructors on the [[Message]] companion to
 * ensure this invariant. `AssistantMessage.content` returns `""` rather than
 * `null` when the LLM response contains only tool calls and no text.
 */
@Stable
sealed trait Message {
  def role: MessageRole
  def content: String

  override def toString: String = s"$role: $content"

  /**
   * Validates that this message satisfies its role-specific content constraints.
   *
   * Returns `Left(ValidationError)` when `content` is blank; `AssistantMessage`
   * additionally requires at least one of `content` or `toolCalls` to be present.
   */
  def validate: Result[Message] =
    if (content.trim.isEmpty) {
      Left(ValidationError(s"$role message content cannot be empty", "content"))
    } else {
      Right(this)
    }
}

/**
 * Companion object providing smart constructors and conversation-level validation.
 *
 * The smart constructors (`system`, `user`, `assistant`, `tool`) return
 * `Left(ValidationError)` on blank content so callers get typed errors instead
 * of runtime exceptions. Prefer these over the case-class constructors in
 * application code; use case-class constructors directly only in tests or when
 * content is guaranteed non-blank.
 */
object Message {

  implicit val rw: RW[Message] = readwriter[ujson.Value].bimap[Message](
    {
      case um: UserMessage      => ujson.Obj("type" -> ujson.Str("user"), "content" -> ujson.Str(um.content))
      case sm: SystemMessage    => ujson.Obj("type" -> ujson.Str("system"), "content" -> ujson.Str(sm.content))
      case am: AssistantMessage => ujson.Obj("type" -> ujson.Str("assistant"), "data" -> ujson.read(write(am)))
      case tm: ToolMessage =>
        ujson.Obj(
          "type"       -> ujson.Str("tool"),
          "toolCallId" -> ujson.Str(tm.toolCallId),
          "content"    -> ujson.Str(tm.content)
        )
    },
    json => {
      val obj = json.obj
      obj("type").str match {
        case "user"      => UserMessage(obj("content").str)
        case "system"    => SystemMessage(obj("content").str)
        case "assistant" => read[AssistantMessage](obj("data"))
        case "tool"      => ToolMessage(obj("content").str, obj("toolCallId").str)
      }
    }
  )

  /**
   * Validates structural consistency of a full conversation.
   *
   * Three checks are applied in order and all failures are collected before
   * returning, so callers see every problem in a single pass:
   *  1. Each message satisfies its individual [[Message.validate]] constraint.
   *  2. Conversation-flow rules: system messages only at the start; tool messages
   *     must be preceded by an assistant message that requested the matching tool call.
   *  3. Tool-call/response pairing: every tool call in an assistant message must
   *     have exactly one [[ToolMessage]] response with a matching `toolCallId`, and
   *     vice-versa.
   *
   * An empty `messages` list is considered valid.
   *
   * @param messages Messages in conversation order (oldest first).
   * @return `Right(())` when all checks pass; `Left(ValidationError)` with all
   *         violations listed in `error.violations` when any check fails.
   */
  def validateConversation(messages: List[Message]): Result[Unit] = {
    if (messages.isEmpty) {
      return Right(())
    }

    val validationErrors = scala.collection.mutable.ListBuffer[String]()

    // Validate individual messages first
    messages.zipWithIndex.foreach { case (message, index) =>
      message.validate match {
        case Left(error) => validationErrors += s"Message $index: ${error.formatted}"
        case Right(_)    => // OK
      }
    }

    // Validate conversation flow rules
    validationErrors ++= validateConversationFlow(messages)

    // Validate tool call consistency
    validationErrors ++= validateToolCallConsistency(messages)

    if (validationErrors.nonEmpty) {
      Left(
        ValidationError(
          s"Conversation validation failed: ${validationErrors.mkString("; ")}",
          "conversation"
        ).withViolations(validationErrors.toList)
      )
    } else {
      Right(())
    }
  }

  /**
   * Validates conversation flow rules
   */
  private def validateConversationFlow(messages: List[Message]): List[String] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    messages.zipWithIndex.foreach { case (message, index) =>
      message match {
        case _: SystemMessage =>
          // System messages should be at the beginning
          if (index > 0 && messages.take(index).exists(_.isInstanceOf[SystemMessage])) {
            errors += s"Multiple system messages found - system message at index $index should be first"
          }

        case toolMsg: ToolMessage =>
          // Tool messages must follow assistant messages with tool calls
          // Multiple consecutive tool messages are allowed if they belong to the same batch of tool calls
          if (index == 0) {
            errors += "Tool messages cannot be the first message in a conversation"
          } else {
            // Find the most recent AssistantMessage with tool calls before this ToolMessage
            val previousMessages = messages.take(index).reverse
            val recentAssistantWithToolCalls = previousMessages.collectFirst {
              case am: AssistantMessage if am.toolCalls.nonEmpty => am
            }

            recentAssistantWithToolCalls match {
              case Some(assistantMsg) =>
                // Check if this tool message's ID matches one of the tool calls
                if (!assistantMsg.toolCalls.exists(_.id == toolMsg.toolCallId)) {
                  errors += s"Tool message at index $index with call ID '${toolMsg.toolCallId}' does not match any tool calls from recent assistant message"
                }
              case None =>
                errors += s"Tool message at index $index must have an assistant message with tool calls before it"
            }
          }

        case _: UserMessage =>
        // User messages are generally always valid in any position

        case _: AssistantMessage =>
        // Assistant messages are generally always valid in any position
      }
    }

    errors.toList
  }

  /**
   * Validates tool call consistency
   */
  private def validateToolCallConsistency(messages: List[Message]): List[String] = {
    val errors = scala.collection.mutable.ListBuffer[String]()

    // Collect all tool calls and their responses
    val toolCalls     = scala.collection.mutable.Map[String, (AssistantMessage, Int)]()
    val toolResponses = scala.collection.mutable.Map[String, (ToolMessage, Int)]()

    messages.zipWithIndex.foreach { case (message, index) =>
      message match {
        case assistantMsg: AssistantMessage =>
          assistantMsg.toolCalls.foreach(toolCall => toolCalls.put(toolCall.id, (assistantMsg, index)))

        case toolMsg: ToolMessage =>
          toolResponses.put(toolMsg.toolCallId, (toolMsg, index))

        case _ => // Other message types don't affect tool call consistency
      }
    }

    // Check for tool calls without responses
    toolCalls.foreach { case (toolCallId, (assistantMsg, assistantIndex)) =>
      if (!toolResponses.contains(toolCallId)) {
        errors += s"Tool call '$toolCallId' at $assistantIndex has no corresponding tool response; Message: $assistantMsg"
      }
    }

    // Check for tool responses without calls
    toolResponses.foreach { case (toolCallId, (toolMsg, toolIndex)) =>
      if (!toolCalls.contains(toolCallId)) {
        errors += s"Tool response at message $toolIndex references unknown tool call '$toolCallId'; Message: $toolMsg"
      }
    }

    errors.toList
  }

  // Individual message constructors with validation
  def system(content: String): Result[SystemMessage] =
    if (content.trim.isEmpty) {
      Left(ValidationError("System message content cannot be empty", "content"))
    } else {
      Right(SystemMessage(content = content))
    }

  def user(content: String): Result[UserMessage] =
    if (content.trim.isEmpty) {
      Left(ValidationError("User message content cannot be empty", "content"))
    } else {
      Right(UserMessage(content = content))
    }

  def assistant(content: String, toolCalls: List[ToolCall] = List.empty): Result[AssistantMessage] =
    if (content.trim.isEmpty && toolCalls.isEmpty) {
      Left(
        ValidationError(
          "Assistant message must have either content or tool calls",
          "content"
        )
      )
    } else {
      Right(AssistantMessage(content = content, toolCalls = toolCalls))
    }

  def tool(content: String, toolCallId: String): Result[ToolMessage] =
    if (content.trim.isEmpty) {
      Left(ValidationError("Tool message content cannot be empty", "content"))
    } else if (toolCallId.trim.isEmpty) {
      Left(ValidationError("Tool call ID cannot be empty", "toolCallId"))
    } else {
      Right(ToolMessage(content = content, toolCallId = toolCallId))
    }
}

/**
 * Identifies the participant that authored a [[Message]].
 *
 * Maps directly to the `role` field in provider API payloads.
 * The string representation returned by `toString` is the lowercase name
 * forwarded verbatim to the provider (e.g. `"user"`, `"assistant"`).
 */
@Stable
sealed trait MessageRole {
  def name: String
  override def toString: String = name
}

object MessageRole {
  case object System    extends MessageRole { val name = "system"    }
  case object User      extends MessageRole { val name = "user"      }
  case object Assistant extends MessageRole { val name = "assistant" }
  case object Tool      extends MessageRole { val name = "tool"      }
}

/**
 * Represents a user message in the conversation.
 *
 * @param content Content of the user message.
 */
@Stable
final case class UserMessage(content: String) extends Message {
  val role: MessageRole = MessageRole.User
}

object UserMessage {
  implicit val rw: RW[UserMessage] = macroRW
}

/**
 * Represents a system message, which is typically used to set context or instructions for the LLM.
 *
 * A system prompt provides the foundational instructions and behavioral guidelines that shape how the
 * LLM should respond to a user request, including its personality, capabilities, constraints, and communication style.
 * It acts as the model's "operating manual," establishing context about what it should and shouldn't do,
 * how to handle various scenarios, and what information it has access to.
 *
 * @param content Content of the system message.
 */
@Stable
final case class SystemMessage(content: String) extends Message {
  val role: MessageRole = MessageRole.System
}

object SystemMessage {
  implicit val rw: RW[SystemMessage] = macroRW
}

/**
 * A response from the LLM, optionally containing text, tool-call requests, or both, and the
 * reasoning the model produced before them.
 *
 * `content` always returns a non-null `String`; it returns `""` when the LLM
 * response contains only tool calls and no accompanying text (`contentOpt` is
 * `None`). Code that displays assistant output should check for an empty string
 * rather than null-guarding.
 *
 * A well-formed `AssistantMessage` must satisfy at least one of:
 *  - `contentOpt.exists(_.trim.nonEmpty)` — the LLM produced text.
 *  - `toolCalls.nonEmpty` — the LLM requested one or more tool invocations.
 *
 * Thinking alone does not make a message well-formed.
 *
 * Construct one with the companion `apply` and change it with the `with*` setters.
 *
 * '''Sealed thinking.''' A signed [[ThinkingBlock.Text]] or a [[ThinkingBlock.Redacted]] block is
 * valid only in the conversation the provider produced it in. Anthropic signs a thinking block over
 * everything sent before it - the system prompt, the tools and every earlier message - and rejects
 * it once any of those changes; Bedrock documents its reasoning signature as a hash of all the
 * messages in the conversation. Both also reject a block sent beside content or tool calls other
 * than the ones it came with. Two rules keep llm4s from sending a signed turn that no longer holds:
 *
 *  - '''Its own message.''' `withContent` and `withToolCalls`, given a value different from the
 *    current one, '''unseal''' the thinking: they drop redacted blocks, the signatures of text
 *    blocks and the binding, keeping the reasoning text.
 *  - '''Everything before it.''' The client that receives sealed thinking records, in
 *    `thinkingBinding`, a fingerprint of the request it answered: the system messages, the tools,
 *    the response format and every earlier message. When the message is sent again, the client
 *    replays its sealed thinking only if the conversation before it still has that fingerprint, and
 *    sends it unsealed otherwise. So pruning, compression, summarisation, an edit or an insertion
 *    anywhere earlier in the history - by any code, through any API - unseals every later turn,
 *    without the code that made the change having to know about thinking.
 *
 * Unsealed thinking keeps its text, which Anthropic and Bedrock leave out of the request and the
 * other providers still receive. Earlier turns whose history is unchanged keep their sealed thinking,
 * as Anthropic recommends: the blocks it accepts back are an unbroken run of the original sequence,
 * and these rules never leave a gap, because unsealing one turn changes the history of every later
 * one. To keep signed thinking, leave the message and everything before it as they are; to replace
 * it deliberately, use `withThinking`.
 *
 * @param contentOpt Text portion of the response; `None` when the model produced
 *                   only tool calls.
 * @param toolCalls  Tool invocations requested by the model; each carries an `id`
 *                   that must be matched by a subsequent [[ToolMessage]].
 * @param thinking   The model's reasoning, as the provider returned it; empty when there was none
 *                   or the provider does not report it. Clients put it here so that it stays in
 *                   the conversation history, and send it back where their provider accepts it
 *                   (see [[ThinkingBlock]]).
 * @param thinkingBinding A fingerprint of the request the sealed thinking was produced for, set by
 *                   the client that received it; `None` when the thinking is not sealed or was not
 *                   bound. Opaque. Sealed thinking without a binding matching the conversation
 *                   before the message is not replayed (see "Sealed thinking" above).
 */
@Stable
final case class AssistantMessage private (
  contentOpt: Option[String],
  toolCalls: Seq[ToolCall],
  thinking: Seq[ThinkingBlock],
  thinkingBinding: Option[String]
) extends Message {
  val role: MessageRole = MessageRole.Assistant

  def content: String = contentOpt.getOrElse("")

  /** Sets the content; a changed content unseals the thinking (see "Sealed thinking" above). */
  def withContent(content: String): AssistantMessage = withContent(Some(content))

  /** Sets the content; a changed content unseals the thinking (see "Sealed thinking" above). */
  def withContent(content: Option[String]): AssistantMessage =
    if (content == contentOpt) this else unsealed.copy(contentOpt = content)

  /** Sets the tool calls; changed tool calls unseal the thinking (see "Sealed thinking" above). */
  def withToolCalls(toolCalls: Seq[ToolCall]): AssistantMessage =
    if (toolCalls == this.toolCalls) this else unsealed.copy(toolCalls = toolCalls)

  /**
   * Replaces the thinking, sealed or not, as given, and clears the binding: sealed thinking set here
   * is not replayed until a client binds it (see "Sealed thinking" above).
   */
  def withThinking(thinking: Seq[ThinkingBlock]): AssistantMessage = copy(thinking = thinking, thinkingBinding = None)

  /** Sets the thinking to `text` as one unsigned [[ThinkingBlock.Text]], or to none when it is empty. */
  def withThinking(text: String): AssistantMessage =
    copy(thinking = if (text.isEmpty) Seq.empty else Seq(ThinkingBlock.Text(text)), thinkingBinding = None)

  /** Sets the binding of the sealed thinking; used by the clients that receive and replay it. */
  private[llm4s] def withThinkingBinding(binding: Option[String]): AssistantMessage = copy(thinkingBinding = binding)

  /**
   * This message with its thinking unsealed: redacted blocks, signatures and the binding dropped,
   * the reasoning text kept. Itself when the thinking is not sealed.
   */
  private[llm4s] def unsealed: AssistantMessage =
    if (!hasSealedThinking && thinkingBinding.isEmpty) this
    else copy(thinking = unsealedThinking, thinkingBinding = None)

  /** The text of the thinking blocks, concatenated; `None` when there is none. */
  def thinkingText: Option[String] = ThinkingBlock.text(thinking)

  /** Whether the message carries any thinking, text or redacted. */
  def hasThinking: Boolean = thinking.nonEmpty

  /**
   * Whether the thinking is sealed: it holds a signed text block or a redacted block, valid only with
   * this exact content and these tool calls, after the history it was produced after (see "Sealed
   * thinking" above).
   */
  def hasSealedThinking: Boolean = thinking.exists(ThinkingBlock.isSealed)

  // the thinking with redacted blocks and signatures dropped, keeping the reasoning text
  private def unsealedThinking: Seq[ThinkingBlock] =
    if (!hasSealedThinking) thinking
    else thinking.collect { case ThinkingBlock.Text(text, _) if text.nonEmpty => ThinkingBlock.Text(text) }

  override def toString: String = {
    val toolCallsStr = if (toolCalls.nonEmpty) {
      s"\nTool Calls: ${toolCalls.map(tc => s"[${tc.id}: ${tc.name}(${tc.arguments})]").mkString(", ")}"
    } else " - no tool calls"

    s"$role: $content$toolCallsStr"
  }

  def hasToolCalls: Boolean = toolCalls.nonEmpty

  override def validate: Result[Message] =
    if (content.trim.isEmpty && toolCalls.isEmpty) {
      Left(ValidationError("Assistant message must have either content or tool calls", "content"))
    } else if (
      thinking.exists {
        case ThinkingBlock.Text(text, signature) => text.isEmpty && !signature.exists(_.nonEmpty)
        case ThinkingBlock.Redacted(data)        => data.isEmpty
      }
    ) {
      Left(ValidationError("thinking", "an assistant message's thinking block must not be empty"))
    } else {
      Right(this)
    }
}

object AssistantMessage {

  /** Creates an [[AssistantMessage]]. Named arguments are the supported way to construct one. */
  def apply(
    contentOpt: Option[String] = None,
    toolCalls: Seq[ToolCall] = Seq.empty,
    thinking: Seq[ThinkingBlock] = Seq.empty
  ): AssistantMessage =
    new AssistantMessage(contentOpt, toolCalls, thinking, None)

  def apply(content: String): AssistantMessage =
    new AssistantMessage(Some(content), Seq.empty, Seq.empty, None)
  def apply(content: String, toolCalls: Seq[ToolCall]): AssistantMessage =
    new AssistantMessage(Some(content), toolCalls, Seq.empty, None)

  // Manual ReadWriter: `thinking` and `thinkingBinding` are written only when present and read as
  // empty when absent, so JSON written before they existed still reads
  implicit val rw: RW[AssistantMessage] = readwriter[ujson.Value].bimap[AssistantMessage](
    msg => {
      val obj = ujson.Obj(
        "contentOpt" -> (msg.contentOpt match {
          case None          => ujson.Null
          case Some(content) => ujson.Str(content)
        }),
        "toolCalls" -> ujson.read(write(msg.toolCalls))
      )
      if (msg.thinking.nonEmpty) obj("thinking") = ujson.read(write(msg.thinking))
      msg.thinkingBinding.foreach(b => obj("thinkingBinding") = b)
      obj
    },
    json => {
      val obj = json.obj
      val contentOpt = obj.get("contentOpt") match {
        case Some(ujson.Null)         => None
        case Some(ujson.Str(content)) => Some(content)
        case _                        => None
      }
      val toolCalls = obj.get("toolCalls") match {
        case Some(toolCallsJson) => read[Seq[ToolCall]](toolCallsJson)
        case _                   => Seq.empty
      }
      val thinking = obj.get("thinking") match {
        case Some(ujson.Null) | None => Seq.empty
        case Some(thinkingJson)      => read[Seq[ThinkingBlock]](thinkingJson)
      }
      val binding = obj.get("thinkingBinding").flatMap(_.strOpt)
      new AssistantMessage(contentOpt, toolCalls, thinking, binding)
    }
  )
}

/**
 * Represents a message from a tool, typically containing the result of a tool call.
 *
 * @param content    Content of the tool message, usually the result of the tool execution, e.g. a json response.
 * @param toolCallId Unique identifier for the tool call (as provided by the ToolCall).
 */
@Stable
final case class ToolMessage(
  content: String,
  toolCallId: String
) extends Message {
  val role: MessageRole = MessageRole.Tool

  /**
   * Resolves the tool name for this response by scanning `contextMessages` for
   * the [[ToolCall]] whose `id` matches [[toolCallId]].
   *
   * Returns `"unknown-tool"` when no matching tool call is found, which can
   * happen if `contextMessages` does not include the assistant message that
   * issued the request (e.g. after context pruning).
   *
   * @param contextMessages The conversation history to search; all
   *                        [[AssistantMessage]] entries are scanned.
   */
  def findToolCallName(contextMessages: Seq[Message]): String =
    contextMessages
      .collect { case am: AssistantMessage => am.toolCalls }
      .flatten
      .find(_.id == toolCallId)
      .map(_.name)
      .getOrElse("unknown-tool")

  override def validate: Result[Message] = {
    val validations = List(
      if (content.trim.isEmpty) Some("Tool message content cannot be empty") else None,
      if (toolCallId.trim.isEmpty) Some("Tool call ID cannot be empty") else None
    ).flatten

    if (validations.nonEmpty) {
      Left(
        ValidationError(
          validations.mkString("; "),
          "toolMessage"
        ).withViolations(validations)
      )
    } else {
      Right(this)
    }
  }
}

object ToolMessage {
  implicit val rw: RW[ToolMessage] = macroRW
}

/**
 * A single tool invocation requested by the LLM.
 *
 * The LLM generates `id` to correlate this request with its [[ToolMessage]] response;
 * the agent framework forwards `id` unchanged when constructing [[ToolMessage]] values,
 * so do not modify it.
 *
 * `arguments` is a parsed `ujson.Value` (typically a JSON object), not a raw string.
 * Use `arguments.obj` to access fields or pass it directly to
 * [[org.llm4s.toolapi.ToolRegistry.execute]] via a [[org.llm4s.toolapi.ToolCallRequest]].
 *
 * @param id        Provider-generated identifier; matched by the corresponding [[ToolMessage.toolCallId]].
 * @param name      Name of the tool to invoke; must match a registered [[org.llm4s.toolapi.ToolFunction]].
 * @param arguments Parsed JSON arguments; the schema is defined by the tool's [[org.llm4s.toolapi.Schema]].
 */
@Stable
case class ToolCall(
  id: String,
  name: String,
  arguments: ujson.Value
) {
  override def toString: String = s"ToolCall($id, $name, $arguments)"
}

object ToolCall {
  implicit val rw: RW[ToolCall] = macroRW
}
