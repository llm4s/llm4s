package org.llm4s.agent

import org.llm4s.core.safety.Safety
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }

/**
 * Basic Agent implementation.
 */
class Agent(client: LLMClient) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Initializes a new agent state with the given query
   *
   * @param query The user query to process
   * @param tools The registry of available tools
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @return A new AgentState initialized with the query and tools
   */
  def initialize(query: String, tools: ToolRegistry, systemPromptAddition: Option[String] = None): AgentState = {
    val baseSystemPrompt = """You are a helpful assistant with access to tools. 
        |Follow these steps:
        |1. Analyze the user's question and determine which tools you need to use
        |2. Use tools ONE AT A TIME - make one tool call, wait for the result, then decide if you need more tools
        |3. Use the results from previous tool calls in subsequent tool calls when needed
        |4. When you have enough information, provide a helpful final answer
        |5. Think step by step and be thorough""".stripMargin

    val fullSystemPrompt = systemPromptAddition match {
      case Some(addition) => s"$baseSystemPrompt\n\n$addition"
      case None           => baseSystemPrompt
    }

    val systemMsg = SystemMessage(fullSystemPrompt)
    // Only store user message in conversation - system message is now config, not history
    val initialMessages = Seq(
      UserMessage(query)
    )

    AgentState(
      conversation = Conversation(initialMessages),
      tools = tools,
      userQuery = query,
      systemMessage = Some(systemMsg)
    )
  }

  /**
   * Runs a single step of the agent's reasoning process
   */
  def runStep(state: AgentState): Result[AgentState] =
    state.status match {
      case AgentStatus.InProgress =>
        // Get tools from registry and create completion options
        val options = CompletionOptions(tools = state.tools.tools)

        logger.debug("Running completion step with tools: {}", state.tools.tools.map(_.name).mkString(", "))
        // Request next step from LLM using system message injection
        client.complete(state.toApiConversation, options) match {
          case Right(completion) =>
            val logMessage = completion.message.toolCalls match {
              case Seq() => s"[assistant] text: ${completion.message.content}"
              case toolCalls =>
                val toolNames = toolCalls.map(_.name).mkString(", ")
                s"[assistant] tools: ${toolCalls.size} tool calls requested ($toolNames)"
            }

            val updatedState = state
              .log(logMessage)
              .addMessage(completion.message)

            completion.message.toolCalls match {
              case Seq() =>
                // No tool calls - agent is ready to answer
                Right(updatedState.withStatus(AgentStatus.Complete))

              case _ =>
                // Don't process tools yet, just mark as waiting
                logger.debug("Tool calls identified, setting state to waiting for tools")
                Right(updatedState.withStatus(AgentStatus.WaitingForTools))
            }

          case Left(error) =>
            Left(error)
        }

      case AgentStatus.WaitingForTools =>
        // Get the latest assistant message with tool calls
        val assistantMessageOpt = state.conversation.messages.reverse
          .collectFirst { case msg: AssistantMessage if msg.toolCalls.nonEmpty => msg }

        assistantMessageOpt match {
          case Some(assistantMessage) =>
            // Log summary of tools to be processed
            val toolNames    = assistantMessage.toolCalls.map(_.name).mkString(", ")
            val logMessage   = s"[tools] executing ${assistantMessage.toolCalls.size} tools ($toolNames)"
            val stateWithLog = state.log(logMessage)

            // Process the tool calls
            Try {
              logger.debug("Processing {} tool calls", assistantMessage.toolCalls.size)
              processToolCalls(stateWithLog, assistantMessage.toolCalls)
            } match {
              case Success(newState) =>
                logger.debug("Tool processing successful - continuing")
                Right(newState.withStatus(AgentStatus.InProgress))
              case Failure(error) =>
                logger.error("Tool processing failed: {}", error.getMessage)
                Right(stateWithLog.withStatus(AgentStatus.Failed(error.getMessage)))
            }

          case None =>
            // Shouldn't happen, but handle gracefully
            Right(state.withStatus(AgentStatus.Failed("No tool calls found in conversation")))
        }

      case _ =>
        // If the agent is already complete or failed, don't do anything
        Right(state)
    }

  /**
   * Process tool calls and add the results to the conversation
   */
  private def processToolCalls(state: AgentState, toolCalls: Seq[ToolCall]): AgentState = {
    val toolRegistry = state.tools

    // Process each tool call and create tool messages
    val toolMessages = toolCalls.map { toolCall =>
      val startTime = System.currentTimeMillis()

      logger.info("Executing tool: {} with arguments: {}", toolCall.name, toolCall.arguments)

      val request = ToolCallRequest(toolCall.name, toolCall.arguments)
      val result  = toolRegistry.execute(request)

      val endTime  = System.currentTimeMillis()
      val duration = endTime - startTime

      val resultContent = result match {
        case Right(json) =>
          val jsonStr = json.render()
          logger.info("Tool {} completed successfully in {}ms. Result: {}", toolCall.name, duration, jsonStr)
          jsonStr
        case Left(error) =>
          val errorMessage = error.getFormattedMessage
          // Escape the error message for JSON
          val escapedMessage = errorMessage
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
          val errorJson = s"""{ "isError": true, "error": "$escapedMessage" }"""
          logger.warn("Tool {} failed in {}ms with error: {}", toolCall.name, duration, errorMessage)
          errorJson
      }

      state.log(s"[tool] ${toolCall.name} (${duration}ms): $resultContent")
      ToolMessage(resultContent, toolCall.id)
    }

    // Add the tool messages to the conversation
    state.addMessages(toolMessages)
  }

  /**
   * Formats the agent state as a markdown document for tracing
   *
   * @param state The agent state to format as markdown
   * @return A markdown string representation of the agent state
   */
  def formatStateAsMarkdown(state: AgentState): String = {
    val sb = new StringBuilder()

    // Add header
    sb.append("# Agent Execution Trace\n\n")
    sb.append(s"**Query:** ${state.userQuery}\n")
    sb.append(s"**Status:** ${state.status}\n")
    sb.append(s"**Tools Available:** ${state.tools.tools.map(_.name).mkString(", ")}\n\n")

    // Add conversation
    sb.append("## Conversation Flow\n\n")

    state.conversation.messages.zipWithIndex.foreach { case (message, index) =>
      val step = index + 1

      message.role match {
        case MessageRole.System =>
          sb.append(s"### Step $step: System Message\n\n")
          sb.append("```\n")
          sb.append(message.content)
          sb.append("\n```\n\n")

        case MessageRole.User =>
          sb.append(s"### Step $step: User Message\n\n")
          sb.append(message.content)
          sb.append("\n\n")

        case MessageRole.Assistant =>
          sb.append(s"### Step $step: Assistant Message\n\n")

          message match {
            case msg: AssistantMessage if msg.toolCalls.nonEmpty =>
              // Show content if it exists
              if (msg.content != null)
                if (msg.content.trim.nonEmpty) {
                  sb.append(msg.content)
                  sb.append("\n\n")
                } else
                  sb.append("--NO CONTENT--\n\n")

              sb.append("**Tool Calls:**\n\n")

              msg.toolCalls.foreach { tc =>
                sb.append(s"Tool: **${tc.name}**\n\n")
                sb.append("Arguments:\n")
                sb.append("```json\n")
                sb.append(tc.arguments)
                sb.append("\n```\n\n")
              }

            case _ =>
              sb.append(message.content)
              sb.append("\n\n")
          }

        case MessageRole.Tool =>
          message match {
            case msg: ToolMessage =>
              sb.append(s"### Step $step: Tool Response\n\n")
              sb.append(s"Tool Call ID: `${msg.toolCallId}`\n\n")
              sb.append("Result:\n")
              sb.append("```json\n")
              sb.append(msg.content)
              sb.append("\n```\n\n")

            case _ =>
              sb.append(s"### Step $step: Tool Response\n\n")
              sb.append("```\n")
              sb.append(message.content)
              sb.append("\n```\n\n")
          }

      }
    }

    // Add logs
    if (state.logs.nonEmpty) {
      sb.append("## Execution Logs\n\n")

      state.logs.zipWithIndex.foreach { case (log, index) =>
        sb.append(s"${index + 1}. ")

        // Format logs with code blocks for tool outputs
        log match {
          case l if l.startsWith("[assistant]") =>
            sb.append(s"**Assistant:** ${l.stripPrefix("[assistant] ")}\n")

          case l if l.startsWith("[tool]") =>
            val content = l.stripPrefix("[tool] ")
            sb.append(s"**Tool Output:** ${content}\n")

          case l if l.startsWith("[tools]") =>
            sb.append(s"**Tools:** ${l.stripPrefix("[tools] ")}\n")

          case l if l.startsWith("[system]") =>
            sb.append(s"**System:** ${l.stripPrefix("[system] ")}\n")

          case _ =>
            sb.append(s"$log\n")
        }
      }
    }

    sb.toString
  }

  /**
   * Writes the current state to a markdown trace file
   *
   * @param state The agent state to write to the trace log
   * @param traceLogPath The path to write the trace log to
   */
  def writeTraceLog(state: AgentState, traceLogPath: String): Unit = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{ Files, Paths }

    Safety
      .fromTry(Try {
        val content = formatStateAsMarkdown(state)
        Files.write(Paths.get(traceLogPath), content.getBytes(StandardCharsets.UTF_8))
      })
      .left
      .foreach(err => logger.error("Failed to write trace log: {}", err.message))
  }

  /**
   * Runs the agent from an existing state until completion, failure, or step limit is reached
   *
   * @param initialState The initial agent state to run from
   * @param maxSteps Optional limit on the number of steps to execute
   * @param traceLogPath Optional path to write a markdown trace file
   * @return Either an error or the final agent state
   */
  def run(
    initialState: AgentState,
    maxSteps: Option[Int] = None,
    traceLogPath: Option[String] = None
  ): Result[AgentState] = {
    // Write initial state if tracing is enabled
    traceLogPath.foreach(path => writeTraceLog(initialState, path))

    @tailrec
    def runUntilCompletion(state: AgentState, stepsRemaining: Option[Int] = maxSteps): Result[AgentState] =
      (state.status, stepsRemaining) match {
        // Check for step limit before executing either type of step
        case (s, Some(0)) if s == AgentStatus.InProgress || s == AgentStatus.WaitingForTools =>
          // Step limit reached
          val updatedState =
            state.log("[system] Step limit reached").withStatus(AgentStatus.Failed("Maximum step limit reached"))

          // Write final state if tracing is enabled
          traceLogPath.foreach(path => writeTraceLog(updatedState, path))
          Right(updatedState)

        // Continue if we're in progress or waiting for tools
        case (s, _) if s == AgentStatus.InProgress || s == AgentStatus.WaitingForTools =>
          runStep(state) match {
            case Right(newState) =>
              // Only decrement steps when going from InProgress to WaitingForTools or back to InProgress
              // This means one "logical step" includes both the LLM call and tool execution
              val shouldDecrementStep =
                (state.status == AgentStatus.InProgress && newState.status == AgentStatus.WaitingForTools) ||
                  (state.status == AgentStatus.WaitingForTools && newState.status == AgentStatus.InProgress)

              val nextSteps = if (shouldDecrementStep) stepsRemaining.map(_ - 1) else stepsRemaining

              // Write updated state if tracing is enabled
              traceLogPath.foreach(path => writeTraceLog(newState, path))

              runUntilCompletion(newState, nextSteps)

            case Left(error) =>
              Left(error)
          }

        case (_, _) =>
          // Write final state if tracing is enabled
          traceLogPath.foreach(path => writeTraceLog(state, path))
          Right(state) // Complete or Failed
      }

    runUntilCompletion(initialState)
  }

  /**
   * Runs the agent with a new query until completion, failure, or step limit is reached
   *
   * @param query The user query to process
   * @param tools The registry of available tools
   * @param maxSteps Optional limit on the number of steps to execute
   * @param traceLogPath Optional path to write a markdown trace file
   * @param systemPromptAddition Optional additional text to append to the default system prompt
   * @return Either an error or the final agent state
   */
  def run(
    query: String,
    tools: ToolRegistry,
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    systemPromptAddition: Option[String]
  ): Result[AgentState] = {
    val initialState = initialize(query, tools, systemPromptAddition)
    run(initialState, maxSteps, traceLogPath)
  }
}
