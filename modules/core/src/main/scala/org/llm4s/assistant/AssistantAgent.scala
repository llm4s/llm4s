package org.llm4s.assistant

import org.llm4s.agent.{ Agent, AgentState, AgentStatus }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.error.{ AssistantError, LLMError, ConfigurationError }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ SessionId, DirectoryPath }
import cats.implicits._

import java.util.UUID
import org.slf4j.LoggerFactory

sealed trait Command
object Command {
  case object Help               extends Command
  case object New                extends Command
  case class Save(title: String) extends Command
  case class Load(title: String) extends Command
  case object Sessions           extends Command
  case object Quit               extends Command

  def parse(input: String): Either[AssistantError, Command] =
    input.toLowerCase.split("\\s+").toList match {
      case "/help" :: _ => Right(Help)
      case "/new" :: _  => Right(New)
      case "/save" :: titleParts =>
        val cleanTitle = titleParts.mkString(" ").trim
        val finalTitle = if (cleanTitle.nonEmpty) cleanTitle else "Saved Session"
        Right(Save(finalTitle))
      case "/load" :: titleParts =>
        val cleanTitle = titleParts.mkString(" ").trim.stripPrefix("\"").stripSuffix("\"")
        if (cleanTitle.nonEmpty) {
          Right(Load(cleanTitle))
        } else {
          Left(AssistantError.emptyCommandTitle("/load"))
        }
      case "/sessions" :: _ => Right(Sessions)
      case "/quit" :: _     => Right(Quit)
      case _                => Left(AssistantError.unknownCommand(input))
    }
}

/**
 * Interactive assistant agent that wraps the existing Agent functionality
 * in a user-friendly conversational loop with session management using functional programming principles.
 */
class AssistantAgent(
  client: LLMClient,
  tools: ToolRegistry,
  sessionDir: String = "./sessions",
  consoleConfig: ConsoleConfig = ConsoleConfig()
) {
  private val logger         = LoggerFactory.getLogger(getClass)
  private val agent          = new Agent(client)
  private val sessionManager = new SessionManager(DirectoryPath(sessionDir), agent)
  private val console        = new ConsoleInterface(tools, sessionManager, consoleConfig)

  /**
   * Starts the interactive session loop
   */
  def startInteractiveSession(): Either[AssistantError, Unit] = {
    logger.info("Starting interactive assistant session")
    for {
      _ <- console.showWelcome()
      initialState = SessionState(None, SessionId(UUID.randomUUID().toString), DirectoryPath(sessionDir))
      _            = logger.info("Created new session: {}", initialState.sessionId)
      _ <- runInteractiveLoop(initialState)
    } yield logger.info("Interactive assistant session ended")
  }

  /**
   * Main interactive loop that processes user input until quit
   */
  private def runInteractiveLoop(initialState: SessionState): Either[AssistantError, Unit] = {
    def loop(state: SessionState): Either[AssistantError, Unit] =
      console.promptUser().flatMap { input =>
        processInput(input.trim, state).fold(
          error =>
            // Continue on error
            console.displayError(error.message).flatMap(_ => loop(state)),
          { case (newState, response) =>
            // Display response if not empty
            val displayResult = if (response.nonEmpty) {
              console.displayMessage(response, MessageType.AssistantResponse)
            } else {
              Right(())
            }

            displayResult.flatMap { _ =>
              // Check if user wants to quit
              if (input.trim.toLowerCase == "/quit") {
                Right(())
              } else {
                loop(newState)
              }
            }
          }
        )
      }

    loop(initialState)
  }

  /**
   * Processes user input - either a command or a query for the agent
   */
  private def processInput(input: String, state: SessionState): Either[AssistantError, (SessionState, String)] =
    if (input.startsWith("/")) {
      handleCommand(input, state)
    } else if (input.nonEmpty) {
      processQuery(input, state)
    } else {
      Right((state, ""))
    }

  /**
   * Processes a user query through the agent
   */
  private def processQuery(query: String, state: SessionState): Either[AssistantError, (SessionState, String)] = {
    logger.debug("Processing user query: {}", query.take(100))
    for {
      updatedState <- addUserMessage(query, state)
      finalState <- runAgentToCompletion(updatedState).leftMap(llmError =>
        AssistantError.SessionError(s"Agent execution failed: ${llmError.message}", state.sessionId, "agent-execution")
      )
      response <- extractFinalResponse(finalState)
    } yield {
      logger.debug("Successfully processed query, response length: {}", response.length)
      (finalState, formatAssistantResponse(response))
    }
  }

  /**
   * Handles slash commands
   */
  private def handleCommand(command: String, state: SessionState): Either[AssistantError, (SessionState, String)] =
    Command.parse(command) match {
      case Right(cmd)       => handleValidCommand(cmd, state)
      case Left(parseError) => Right((state, parseError.message))
    }

  /**
   * Handles valid parsed commands - pure business logic with no string munging
   */
  private def handleValidCommand(
    command: Command,
    state: SessionState
  ): Either[AssistantError, (SessionState, String)] =
    command match {
      case Command.Help =>
        Right((state, console.showHelp()))

      case Command.New =>
        handleNewSessionCommand(state)

      case Command.Save(title) =>
        sessionManager.saveSession(state, Some(title)).map(_ => (state, s"Session saved as: $title"))

      case Command.Load(title) =>
        handleLoadSessionCommand(title, state)

      case Command.Sessions =>
        sessionManager.listRecentSessions().map(sessions => (state, console.formatSessionList(sessions)))

      case Command.Quit =>
        handleQuitCommand(state)
    }

  /**
   * Checks if current session has content worth saving
   */
  private def hasContentToSave(state: SessionState): Boolean =
    state.agentState.exists(_.conversation.messages.nonEmpty)

  /**
   * Prompts user and gets clean session name with default
   */
  private def getSessionNameWithDefault(): Either[AssistantError, String] =
    promptForSessionName("Enter a name for the current session (or press Enter for 'Untitled Session'): ")
      .map(name => if (name.trim.nonEmpty) name.trim else "Untitled Session")

  /**
   * Saves current session with given title
   */
  private def saveCurrentSession(state: SessionState, title: String): Either[AssistantError, Unit] =
    sessionManager.saveSession(state, Some(title)).map(_ => ())

  /**
   * Creates new session state
   */
  private def createNewSessionState(state: SessionState): SessionState =
    state.withNewSession()

  /**
   * Formats success message for saved session
   */
  private def formatSavedSessionMessage(title: String): String =
    s"Previous session saved as '$title'. Started new session."

  /**
   * Formats message for fresh session
   */
  private def formatFreshSessionMessage(): String =
    "Started new session."

  /**
   * Loads session with given title
   */
  private def loadSession(title: String): Either[AssistantError, SessionState] =
    sessionManager.loadSession(title, tools)

  /**
   * Counts messages in session state
   */
  private def countMessagesInSession(state: SessionState): Int =
    state.agentState.map(_.conversation.messages.length).getOrElse(0)

  /**
   * Formats load success message
   */
  private def formatLoadSuccessMessage(title: String, messageCount: Int): String =
    s"✅ Session '$title' restored - $messageCount messages loaded"

  // Additional atomic methods for Command.Quit

  /**
   * Formats goodbye message for saved session
   */
  private def formatSavedGoodbyeMessage(title: String): String =
    s"Session saved as '$title'. Goodbye!"

  /**
   * Formats simple goodbye message
   */
  private def formatSimpleGoodbyeMessage(): String =
    "Goodbye!"

  /**
   * Handles new session command by composing atomic operations
   */
  private def handleNewSessionCommand(state: SessionState): Either[AssistantError, (SessionState, String)] =
    if (hasContentToSave(state)) {
      for {
        title <- getSessionNameWithDefault()
        _     <- saveCurrentSession(state, title)
        newState = createNewSessionState(state)
        message  = formatSavedSessionMessage(title)
      } yield (newState, message)
    } else {
      // Just start fresh session
      val newState = createNewSessionState(state)
      val message  = formatFreshSessionMessage()
      Right((newState, message))
    }

  /**
   * Handles load session command by composing atomic operations
   */
  private def handleLoadSessionCommand(
    title: String,
    state: SessionState
  ): Either[AssistantError, (SessionState, String)] =
    for {
      // Auto-save current session if it has content (reusing existing logic)
      _ <-
        if (hasContentToSave(state)) {
          saveCurrentSession(state, "Auto-saved Session")
        } else {
          Right(())
        }
      // Load the requested session
      loadedState <- loadSession(title)
      messageCount = countMessagesInSession(loadedState)
      message      = formatLoadSuccessMessage(title, messageCount)
    } yield (loadedState, message)

  /**
   * Handles quit command by composing atomic operations (reusing existing methods)
   */
  private def handleQuitCommand(state: SessionState): Either[AssistantError, (SessionState, String)] =
    if (hasContentToSave(state)) {
      // Reuse existing session saving logic
      for {
        title <- getSessionNameWithDefault()
        _     <- saveCurrentSession(state, title)
        message = formatSavedGoodbyeMessage(title)
      } yield (state, message)
    } else {
      // Simple goodbye
      val message = formatSimpleGoodbyeMessage()
      Right((state, message))
    }

  /**
   * Adds user message to the conversation - initializes if first message
   */
  private def addUserMessage(query: String, state: SessionState): Either[AssistantError, SessionState] =
    Right(
      state.agentState
        .map { agentState =>
          // Some case - add to existing conversation
          val updatedAgentState = agentState
            .addMessage(UserMessage(query))
            .withStatus(AgentStatus.InProgress)
          state.withAgentState(updatedAgentState)
        }
        .getOrElse {
          // None case - initialize agent
          val initialState = agent.initialize(query, tools)
          state.withAgentState(initialState)
        }
    )

  /**
   * Runs the agent until completion or failure
   */
  private def runAgentToCompletion(state: SessionState): Either[LLMError, SessionState] =
    state.agentState match {
      case None => Left(ConfigurationError("No agent state to run"))
      case Some(agentState) =>
        def runSteps(currentState: AgentState): Either[org.llm4s.error.LLMError, AgentState] =
          currentState.status match {
            case AgentStatus.InProgress | AgentStatus.WaitingForTools =>
              agent.runStep(currentState) match {
                case Right(newState) => runSteps(newState)
                case Left(error)     => Left(error)
              }
            case _ => Right(currentState)
          }

        runSteps(agentState)
          .map(finalAgentState => state.withAgentState(finalAgentState))
    }

  /**
   * Extracts the final response from the agent state
   */
  private def extractFinalResponse(state: SessionState): Either[AssistantError, String] =
    state.agentState match {
      case None => Left(AssistantError.SessionError("No agent state available", state.sessionId, "extract-response"))
      case Some(agentState) =>
        agentState.conversation.messages.reverse.collectFirst {
          case msg: AssistantMessage if msg.toolCalls.isEmpty => msg.content
        } match {
          case Some(content) => Right(content)
          case None =>
            Left(
              AssistantError.SessionError("No final response found from assistant", state.sessionId, "extract-response")
            )
        }
    }

  /**
   * Prompts user for a session name
   */
  private def promptForSessionName(prompt: String): Either[AssistantError, String] =
    console.promptForInput(prompt)

  /**
   * Formats the assistant's response for display
   */
  private def formatAssistantResponse(response: String): String =
    response // The ConsoleInterface handles assistant formatting via MessageType.AssistantResponse
}
