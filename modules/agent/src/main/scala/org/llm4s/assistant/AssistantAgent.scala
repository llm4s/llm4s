package org.llm4s.assistant

import org.llm4s.annotation.Experimental
import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.agent.graph.{ GraphError, RunConfig, ThreadId }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.error.AssistantError
import org.llm4s.types.Result
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ SessionId, DirectoryPath }
import cats.implicits._

import java.util.UUID
import org.slf4j.LoggerFactory

sealed private[assistant] trait Command
private[assistant] object Command {
  case object Help               extends Command
  case object New                extends Command
  case class Save(title: String) extends Command
  case class Load(title: String) extends Command
  case object Sessions           extends Command
  case object Quit               extends Command

  /** The title `/save` uses when it is given none. */
  val DefaultSaveTitle: String = "Saved Session"

  /**
   * The command `input` names. Only the command word is matched without regard to case (`/LOAD`); a title keeps
   * its case, so `/load MyChat` finds the session `/save MyChat` wrote on a case-sensitive file system (#3277).
   */
  def parse(input: String): Either[AssistantError, Command] =
    input.trim.split("\\s+").toList match {
      case Nil => Left(AssistantError.unknownCommand(input))
      case word :: titleParts =>
        word.toLowerCase(java.util.Locale.ROOT) :: titleParts match {
          case "/help" :: _ => Right(Help)
          case "/new" :: _  => Right(New)
          case "/save" :: titleParts =>
            val cleanTitle = titleParts.mkString(" ").trim
            val finalTitle = if (cleanTitle.nonEmpty) cleanTitle else DefaultSaveTitle
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
}

/**
 * Interactive assistant agent that wraps an [[org.llm4s.agent.Agent]] in a user-friendly
 * conversational loop with session management. The agent is built once, from `client` and
 * `tools`; each query is one turn on the session's thread.
 */
@Experimental
class AssistantAgent(
  client: LLMClient,
  tools: ToolRegistry,
  sessionDir: String = "./sessions"
) {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Built once; a build failure is reported by every query. */
  private[assistant] val agent: Result[Agent] = Agent.builder("assistant", client).withTools(tools).build()
  private val sessionManager                  = new SessionManager(DirectoryPath(sessionDir))
  private val console                         = new ConsoleInterface(tools, sessionManager)

  /**
   * Starts the interactive session loop
   */
  def startInteractiveSession(): Either[AssistantError, Unit] = {
    logger.info("Starting interactive assistant session")
    for {
      _ <- console.showWelcome()
      initialState = SessionState(None, None, SessionId(UUID.randomUUID().toString), DirectoryPath(sessionDir))
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
              // the parsed command decides, so `/QUIT` and `/quit now` end the loop as `/quit` does (#3277)
              if (isQuit(input)) {
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

  /** Whether `input` is the `/quit` command, in any case and with anything after it. */
  private[assistant] def isQuit(input: String): Boolean =
    input.trim.startsWith("/") && Command.parse(input).contains(Command.Quit)

  /**
   * Processes user input - either a command or a query for the agent
   */
  private[assistant] def processInput(
    input: String,
    state: SessionState
  ): Either[AssistantError, (SessionState, String)] =
    if (input.startsWith("/")) {
      handleCommand(input, state)
    } else if (input.nonEmpty) {
      processQuery(input, state)
    } else {
      Right((state, ""))
    }

  /**
   * Processes a user query through the agent: one turn. A turn that ends without an answer -
   * step limit, pending approval - is still recorded in the session, its reason as the response.
   */
  private def processQuery(query: String, state: SessionState): Either[AssistantError, (SessionState, String)] = {
    logger.debug("Processing user query: {}", query.take(100))
    for {
      result <- runTurn(query, state).leftMap(llmError =>
        AssistantError.SessionError(
          s"Agent execution failed: ${llmError.message}",
          state.sessionId,
          "agent-execution",
          llmCause = Some(llmError)
        )
      )
      // every turn's result is kept, whatever its status, so a later /save includes it
      finalState = state.withResult(result)
      response   = extractFinalResponse(finalState).fold(_.message, identity)
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
        // a title the user typed replaces its earlier save, so `/load title` restores this one (#3277)
        sessionManager
          .saveSession(state, Some(title), overwrite = title != Command.DefaultSaveTitle)
          .map(info => (state, s"Session saved as: ${info.title}"))

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
    state.messages.nonEmpty

  /**
   * Prompts user and gets clean session name with default
   */
  private def getSessionNameWithDefault(): Either[AssistantError, String] =
    promptForSessionName(s"Enter a name for the current session (or press Enter for '$UntitledTitle'): ")
      .map(name => if (name.trim.nonEmpty) name.trim else UntitledTitle)

  private val UntitledTitle  = "Untitled Session"
  private val AutoSavedTitle = "Auto-saved Session"

  /**
   * Saves the current session under `title` and returns the name it was saved as: a name the user chose replaces
   * its earlier save, a default one (`Untitled Session`, `Auto-saved Session`) never does, so it may gain a suffix.
   */
  private def saveCurrentSession(state: SessionState, title: String): Either[AssistantError, String] =
    sessionManager
      .saveSession(state, Some(title), overwrite = title != UntitledTitle && title != AutoSavedTitle)
      .map(_.title)

  /**
   * Saves the session before `action` (`Quit`, `Start a new session`), or, when the save fails, asks whether to go on
   * without it: `Some(name)` when saved, `None` when the user chose to discard it, the save's error otherwise. So a
   * session dir that cannot be written does not trap the user in the loop (#3277).
   */
  private def savedOrDiscarded(
    state: SessionState,
    title: String,
    action: String
  ): Either[AssistantError, Option[String]] =
    saveCurrentSession(state, title) match {
      case Right(name) => Right(Some(name))
      case Left(error) =>
        promptForSessionName(s"The session could not be saved: ${error.message}\n$action without saving it? (y/N): ")
          .flatMap(answer =>
            if (Set("y", "yes").contains(answer.trim.toLowerCase(java.util.Locale.ROOT))) Right(None) else Left(error)
          )
    }

  /**
   * Creates new session state
   */
  private def createNewSessionState(state: SessionState): SessionState =
    replacing(state, state.withNewSession())

  /**
   * `next`, having forgotten `previous`'s thread when `next` does not continue it, so an abandoned
   * conversation does not stay in the agent's runtime.
   */
  private def replacing(previous: SessionState, next: SessionState): SessionState = {
    previous.threadId.filterNot(next.threadId.contains).foreach(forgetThread)
    next
  }

  /** Removes `threadId` from the agent's runtime; a failure is logged, since the session moves on either way. */
  private def forgetThread(threadId: ThreadId): Unit =
    agent.flatMap(_.forget(threadId)).left.foreach { error =>
      logger.warn("Could not forget thread {}: {}", threadId.value, error.message)
    }

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
    sessionManager.loadSession(title)

  /**
   * Counts messages in session state
   */
  private def countMessagesInSession(state: SessionState): Int =
    state.messages.length

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
        saved <- savedOrDiscarded(state, title, "Start a new session")
        newState = createNewSessionState(state)
        message  = saved.fold("Previous session discarded. Started new session.")(formatSavedSessionMessage)
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
          saveCurrentSession(state, AutoSavedTitle).map(_ => ())
        } else {
          Right(())
        }
      // Load the requested session
      loadedState <- loadSession(title)
      messageCount = countMessagesInSession(loadedState)
      message      = formatLoadSuccessMessage(title, messageCount)
    } yield (replacing(state, loadedState), message)

  /**
   * Handles quit command by composing atomic operations (reusing existing methods)
   */
  private def handleQuitCommand(state: SessionState): Either[AssistantError, (SessionState, String)] =
    if (hasContentToSave(state)) {
      // Reuse existing session saving logic
      for {
        title <- getSessionNameWithDefault()
        saved <- savedOrDiscarded(state, title, "Quit")
        message = saved.fold("Session not saved. Goodbye!")(formatSavedGoodbyeMessage)
      } yield (state, message)
    } else {
      // Simple goodbye
      val message = formatSimpleGoodbyeMessage()
      Right((state, message))
    }

  /**
   * Runs `query` as one turn: on the session's thread, or on a new thread seeded with the session's
   * messages when it has none yet - a loaded session - or when its thread cannot take a new turn
   * (an earlier turn failed, or is waiting for an approval the assistant cannot give), which is
   * then forgotten.
   */
  private[assistant] def runTurn(query: String, state: SessionState): Result[AgentResult] =
    agent.flatMap { agent =>
      def fresh() = agent.run(ThreadId(UUID.randomUUID().toString), query, RunConfig(), state.messages)
      state.threadId match {
        case Some(threadId) =>
          agent.run(threadId, query) match {
            case Left(_: GraphError.IncompleteRun | _: GraphError.PendingInterrupts) =>
              fresh().map { result =>
                forgetThread(threadId)
                result
              }
            case other => other
          }
        case None => fresh()
      }
    }

  /**
   * The response to show for the latest turn: its answer, or why there is none.
   */
  private[assistant] def extractFinalResponse(state: SessionState): Either[AssistantError, String] =
    state.last.map(_.status) match {
      case None => Left(AssistantError.SessionError("No agent result available", state.sessionId, "extract-response"))
      case Some(AgentStatus.Completed(answer)) => Right(answer)
      case Some(AgentStatus.Truncated(partial)) =>
        Right(s"$partial\n[The answer was cut off at the model's output limit]")
      case Some(AgentStatus.Blocked(guardrail, why)) =>
        Right(s"Blocked by guardrail '$guardrail'${why.fold("")(reason => s": $reason")}")
      case Some(AgentStatus.StepLimitReached) =>
        Left(
          AssistantError.SessionError(
            "The agent reached its step limit without a final answer",
            state.sessionId,
            "extract-response"
          )
        )
      case Some(AgentStatus.TokenLimitReached) =>
        Left(
          AssistantError.SessionError(
            "The agent reached its token budget without a final answer",
            state.sessionId,
            "extract-response"
          )
        )
      case Some(_: AgentStatus.Suspended) =>
        Left(
          AssistantError.SessionError(
            "The agent is waiting for a tool approval or an answer, which the assistant cannot give",
            state.sessionId,
            "extract-response"
          )
        )
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
