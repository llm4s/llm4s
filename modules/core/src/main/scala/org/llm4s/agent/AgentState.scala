package org.llm4s.agent

import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import upickle.default.{ ReadWriter => RW, readwriter }

/**
 * Represents the current state of an agent run.
 *
 * @param conversation The conversation history (without system message - that's in systemMessage field)
 * @param tools The available tool registry
 * @param initialQuery The initial user query that started this conversation (optional for multi-turn)
 * @param status Current agent status (InProgress, WaitingForTools, Complete, or Failed)
 * @param logs Execution logs for this turn
 * @param systemMessage The system message (injected at API call time, not stored in conversation)
 * @param completionOptions LLM completion options (temperature, maxTokens, etc.)
 * @param availableHandoffs Available handoffs for this agent (used for detecting handoff tool calls)
 * @param usageSummary Usage summary for this agent run
 */
case class AgentState(
  conversation: Conversation,
  tools: ToolRegistry,
  initialQuery: Option[String] = None,
  status: AgentStatus = AgentStatus.InProgress,
  logs: Seq[String] = Seq.empty,
  systemMessage: Option[SystemMessage] = None,
  completionOptions: CompletionOptions = CompletionOptions(),
  availableHandoffs: Seq[Handoff] = Seq.empty,
  usageSummary: UsageSummary = UsageSummary()
) {

  /**
   * Adds a message to the conversation and returns a new state
   */
  def addMessage(message: Message): AgentState =
    copy(conversation = conversation.addMessage(message))

  /**
   * Adds multiple messages to the conversation and returns a new state
   */
  def addMessages(messages: Seq[Message]): AgentState =
    copy(conversation = conversation.addMessages(messages))

  /**
   * Adds a log entry and returns a new state
   */
  def log(entry: String): AgentState =
    copy(logs = logs :+ entry)

  /**
   * Updates the status and returns a new state
   */
  def withStatus(newStatus: AgentStatus): AgentState =
    copy(status = newStatus)

  /**
   * Creates a complete conversation by injecting the system message at the beginning.
   * This is used for LLM API calls where system message should be first.
   */
  def toApiConversation: Conversation =
    Conversation(systemMessage.toSeq ++ conversation.messages)

  /**
   * Prints a detailed dump of the agent execution state for debugging
   */
  def dump(): Unit = {
    val separator = "=" * 80

    println(separator)
    println(s"AGENT STATE DUMP - Status: $status")
    println(separator)

    initialQuery.foreach(q => println(s"Initial Query: $q"))
    println(s"Available Tools: ${tools.tools.map(_.name).mkString(", ")}")
    println(separator)

    println("CONVERSATION FLOW:")
    println(separator)

    conversation.messages.zipWithIndex.foreach { case (message, index) =>
      val step = index + 1
      val roleMarker = message.role match {
        case MessageRole.User      => "👤 USER"
        case MessageRole.Assistant => "🤖 ASSISTANT"
        case MessageRole.System    => "⚙️ SYSTEM"
        case MessageRole.Tool      => "🛠️ TOOL"
      }

      println(s"STEP $step: $roleMarker")

      message match {
        case msg: AssistantMessage if msg.toolCalls.nonEmpty =>
          println(s"Content: ${msg.content}")
          println("Tool Calls:")
          msg.toolCalls.foreach { tc =>
            println(s"  - ID: ${tc.id}")
            println(s"    Tool: ${tc.name}")
            println(s"    Args: ${tc.arguments}")
          }

        case msg: ToolMessage =>
          println(s"Tool Call ID: ${msg.toolCallId}")
          println(s"Result: ${msg.content}")

        case msg =>
          println(s"Content: ${msg.content}")
      }

      println(separator)
    }

    if (logs.nonEmpty) {
      println("EXECUTION LOGS:")
      logs.zipWithIndex.foreach { case (log, index) =>
        println(s"${index + 1}. $log")
      }
      println(separator)
    }

    println(s"END OF AGENT STATE DUMP - Status: $status")
    println(separator)
  }
}

object AgentState {
  import org.llm4s.types.{ Result, TryOps }
  import upickle.default._
  import scala.util.Try

  // We can't automatically serialize AgentState because it contains ToolRegistry (with function references)
  // Serialization is handled manually below

  /**
   * Prune conversation history based on configuration.
   * Returns a new AgentState with pruned conversation.
   *
   * This is a pure function - it does not modify the input state.
   *
   * @param state The current agent state
   * @param config Context window configuration
   * @param tokenCounter Function to count tokens in messages (optional, uses simple heuristic by default)
   * @return New state with pruned conversation
   */
  def pruneConversation(
    state: AgentState,
    config: ContextWindowConfig,
    tokenCounter: Message => Int = defaultTokenCounter
  ): AgentState = {
    val messages = state.conversation.messages

    // Check if pruning is needed
    val needsPruning = (config.maxTokens, config.maxMessages) match {
      case (Some(maxTokens), _) =>
        messages.map(tokenCounter).sum > maxTokens
      case (None, Some(maxMessages)) =>
        messages.length > maxMessages
      case (None, None) =>
        false
    }

    if (!needsPruning) {
      state
    } else {
      val prunedMessages = config.pruningStrategy match {
        case PruningStrategy.OldestFirst =>
          pruneOldestFirst(messages, config, tokenCounter)
        case PruningStrategy.MiddleOut =>
          pruneMiddleOut(messages, config, tokenCounter)
        case PruningStrategy.RecentTurnsOnly(turns) =>
          pruneRecentTurnsOnly(messages, turns, config)
        case PruningStrategy.Custom(fn) =>
          fn(messages)
      }

      state.copy(conversation = Conversation(prunedMessages))
    }
  }

  /**
   * Default token counter (rough estimate: words * 1.3).
   * For more accurate counting, integrate with the org.llm4s.context.tokens module.
   */
  private def defaultTokenCounter(message: Message): Int = {
    val words = message.content.split("\\s+").length
    (words * 1.3).toInt
  }

  private def pruneOldestFirst(
    messages: Seq[Message],
    config: ContextWindowConfig,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    // Separate system message if needed
    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    // Calculate how many messages to keep
    val targetCount = config.maxMessages match {
      case Some(max) => math.max(1, max - systemMsgs.length)
      case None      =>
        // Token-based: iteratively count from the end
        val maxTokens    = config.maxTokens.getOrElse(Int.MaxValue)
        val systemTokens = systemMsgs.map(tokenCounter).sum
        var count        = 0
        var tokens       = 0
        otherMsgs.reverse.takeWhile { msg =>
          val msgTokens = tokenCounter(msg)
          if (tokens + msgTokens + systemTokens <= maxTokens) {
            tokens += msgTokens
            count += 1
            true
          } else {
            false
          }
        }
        math.max(1, count)
    }

    // Keep system messages + recent messages up to limit
    val toKeep = if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.takeRight(targetCount)
    } else {
      messages.takeRight(targetCount)
    }

    toKeep
  }

  private def pruneMiddleOut(
    messages: Seq[Message],
    config: ContextWindowConfig,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    // Note: tokenCounter is unused for MiddleOut strategy as it's purely message-count based
    val _           = tokenCounter // Suppress unused warning
    val targetCount = config.maxMessages.getOrElse(messages.length / 2)
    val keepStart   = targetCount / 2
    val keepEnd     = targetCount - keepStart

    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.take(keepStart) ++ otherMsgs.takeRight(keepEnd)
    } else {
      messages.take(keepStart) ++ messages.takeRight(keepEnd)
    }
  }

  private def pruneRecentTurnsOnly(
    messages: Seq[Message],
    turns: Int,
    config: ContextWindowConfig
  ): Seq[Message] = {
    // A turn is a user message + assistant response (+ optional tool messages)
    // Keep the last N complete turns
    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    // Group messages into turns (simplified: every user message starts a turn)
    val turnStarts = otherMsgs.zipWithIndex
      .filter(_._1.role == MessageRole.User)
      .map(_._2)

    val keepFromIndex = if (turnStarts.length > turns) {
      turnStarts(turnStarts.length - turns)
    } else {
      0
    }

    if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.drop(keepFromIndex)
    } else {
      otherMsgs.drop(keepFromIndex)
    }
  }

  /**
   * Serialize agent state to JSON.
   *
   * Note: ToolRegistry is not serialized (contains function references).
   * Tools must be re-registered when loading state.
   *
   * @param state The agent state to serialize
   * @return JSON representation
   */
  def toJson(state: AgentState): ujson.Value =
    ujson.Obj(
      "conversation"      -> writeJs(state.conversation),
      "initialQuery"      -> state.initialQuery.map(ujson.Str.apply).getOrElse(ujson.Null),
      "status"            -> writeJs(state.status),
      "logs"              -> ujson.Arr(state.logs.map(ujson.Str.apply): _*),
      "systemMessage"     -> state.systemMessage.map(msg => ujson.Str(msg.content)).getOrElse(ujson.Null),
      "completionOptions" -> serializeCompletionOptions(state.completionOptions),
      "usageSummary"      -> writeJs(state.usageSummary)
      // Note: tools are NOT serialized
    )

  /**
   * Serialize CompletionOptions to JSON (excluding tools which contain function references).
   */
  private def serializeCompletionOptions(opts: CompletionOptions): ujson.Value =
    ujson.Obj(
      "temperature"      -> ujson.Num(opts.temperature),
      "topP"             -> ujson.Num(opts.topP),
      "maxTokens"        -> opts.maxTokens.map(ujson.Num(_)).getOrElse(ujson.Null),
      "presencePenalty"  -> ujson.Num(opts.presencePenalty),
      "frequencyPenalty" -> ujson.Num(opts.frequencyPenalty),
      "reasoning"        -> opts.reasoning.map(r => writeJs(r)).getOrElse(ujson.Null),
      "budgetTokens"     -> opts.budgetTokens.map(ujson.Num(_)).getOrElse(ujson.Null)
      // Note: tools are NOT serialized (contain function references)
    )

  /**
   * Deserialize CompletionOptions from JSON.
   */
  private def deserializeCompletionOptions(json: ujson.Value): CompletionOptions =
    CompletionOptions(
      temperature = json("temperature").num,
      topP = json("topP").num,
      maxTokens = json("maxTokens") match {
        case ujson.Num(n) => Some(n.toInt)
        case _            => None
      },
      presencePenalty = json("presencePenalty").num,
      frequencyPenalty = json("frequencyPenalty").num,
      reasoning = json.obj.get("reasoning").flatMap {
        case ujson.Null => None
        case v          => Some(read[ReasoningEffort](v))
      },
      budgetTokens = json.obj.get("budgetTokens").flatMap {
        case ujson.Num(n) => Some(n.toInt)
        case _            => None
      }
      // tools will be empty - caller must provide tools separately
    )

  /**
   * Deserialize agent state from JSON.
   *
   * Tools must be provided separately as they cannot be serialized.
   *
   * @param json The JSON representation
   * @param tools The tool registry to use (must be provided by caller)
   * @return Result containing the deserialized state or an error
   */
  def fromJson(
    json: ujson.Value,
    tools: ToolRegistry
  ): Result[AgentState] =
    Try {
      AgentState(
        conversation = read[Conversation](json("conversation")),
        tools = tools, // Provided by caller
        initialQuery = json("initialQuery") match {
          case ujson.Str(q) => Some(q)
          case _            => None
        },
        status = read[AgentStatus](json("status")),
        logs = json("logs").arr.map(_.str).toSeq,
        systemMessage = json("systemMessage") match {
          case ujson.Str(content) => Some(SystemMessage(content))
          case _                  => None
        },
        completionOptions = deserializeCompletionOptions(json("completionOptions")),
        usageSummary = json.obj.get("usageSummary") match {
          case Some(v) => read[UsageSummary](v)
          case None    => UsageSummary()
        }
      )
    }.toResult

  /**
   * Save state to file (convenience method).
   *
   * This performs I/O and may fail with filesystem errors.
   *
   * @param state The state to save
   * @param path File path to write to
   * @return Result indicating success or error
   */
  def saveToFile(state: AgentState, path: String): Result[Unit] = {
    import java.nio.file.{ Files, Paths }
    import java.nio.charset.StandardCharsets

    Try {
      val json    = toJson(state)
      val jsonStr = write(json, indent = 2)
      Files.write(Paths.get(path), jsonStr.getBytes(StandardCharsets.UTF_8))
      ()
    }.toResult
  }

  /**
   * Load state from file (convenience method).
   *
   * This performs I/O and may fail with filesystem errors or parse errors.
   *
   * @param path File path to read from
   * @param tools The tool registry to use (tools are not serialized)
   * @return Result containing the loaded state or an error
   */
  def loadFromFile(path: String, tools: ToolRegistry): Result[AgentState] = {
    import java.nio.file.{ Files, Paths }
    import java.nio.charset.StandardCharsets

    for {
      jsonStr <- Try(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).toResult
      json    <- Try(ujson.read(jsonStr)).toResult
      state   <- fromJson(json, tools)
    } yield state
  }
}

/**
 * Status of the agent run
 */
sealed trait AgentStatus

object AgentStatus {
  case object InProgress      extends AgentStatus
  case object WaitingForTools extends AgentStatus // Waiting for tool execution

  /**
   * Agent has requested a handoff to another agent.
   *
   * This status indicates that the current agent has determined
   * that the query should be handled by a specialist agent.
   *
   * @param handoff The handoff to execute
   * @param handoffReason The reason provided by the LLM for the handoff
   */
  case class HandoffRequested(
    handoff: Handoff,
    handoffReason: Option[String] = None
  ) extends AgentStatus

  case object Complete             extends AgentStatus
  case class Failed(error: String) extends AgentStatus

  // Custom serialization for AgentStatus since sealed trait derivation can be tricky
  // Note: HandoffRequested cannot be fully serialized (contains Agent reference)
  implicit val rw: RW[AgentStatus] = readwriter[ujson.Value].bimap[AgentStatus](
    {
      case InProgress      => ujson.Str("InProgress")
      case WaitingForTools => ujson.Str("WaitingForTools")
      case HandoffRequested(handoff, reason) =>
        ujson.Obj(
          "type"      -> ujson.Str("HandoffRequested"),
          "handoffId" -> ujson.Str(handoff.handoffId),
          "reason"    -> reason.map(ujson.Str.apply).getOrElse(ujson.Null)
        )
      case Complete      => ujson.Str("Complete")
      case Failed(error) => ujson.Obj("type" -> ujson.Str("Failed"), "error" -> ujson.Str(error))
    },
    {
      case ujson.Str("InProgress")      => InProgress
      case ujson.Str("WaitingForTools") => WaitingForTools
      case ujson.Str("Complete")        => Complete
      case obj: ujson.Obj =>
        obj.obj.get("type") match {
          case Some(ujson.Str("HandoffRequested")) =>
            // Note: Cannot fully deserialize handoff (contains Agent reference)
            // This is primarily for trace logging
            Failed("Cannot deserialize HandoffRequested status - contains Agent reference")
          case Some(ujson.Str("Failed")) =>
            Failed(obj.obj.get("error").map(_.str).getOrElse("Unknown error"))
          case _ => Failed("Unknown status format")
        }
      case _ => Failed("Invalid status format")
    }
  )
}
