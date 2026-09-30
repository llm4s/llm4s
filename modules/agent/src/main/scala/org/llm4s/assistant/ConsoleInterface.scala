package org.llm4s.assistant

import org.llm4s.toolapi.ToolRegistry
import org.llm4s.error.{ AssistantError, LLMError }
import cats.implicits._
import cats.Show
import fansi._
import scala.io.StdIn
import scala.util.Try

/**
 * Immutable configuration for console interface
 */
case class ConsoleConfig(
  promptSymbol: String = "User> ",
  assistantSymbol: String = "Assistant> ",
  colorScheme: Map[MessageType, Attrs] = Map(
    MessageType.Info              -> Color.Blue,
    MessageType.Success           -> Color.Green,
    MessageType.Warning           -> Color.Yellow,
    MessageType.Error             -> Color.Red,
    MessageType.AssistantResponse -> Color.Magenta
  ),
  styles: ConsoleConfig.StyleConfig = ConsoleConfig.StyleConfig()
)

object ConsoleConfig {
  case class StyleConfig(
    prompt: Attrs = Color.Cyan,
    highlight: Attrs = Bold.On ++ Color.Green,
    title: Attrs = Bold.On ++ Color.Cyan,
    command: Attrs = Color.Yellow,
    bold: Attrs = Bold.On
  )
}

/**
 * Handles console-based user interface using functional programming principles
 */
class ConsoleInterface(
  tools: ToolRegistry,
  sessionManager: SessionManager,
  config: ConsoleConfig = ConsoleConfig()
) {

  /**
   * Shows welcome message with recent sessions
   */
  def showWelcome(): Either[AssistantError, Unit] = {
    val recentSessions = sessionManager.listRecentSessions(5).getOrElse(Seq.empty)

    val recentSessionsDisplay = if (recentSessions.nonEmpty) {
      s"""
${config.styles.title("Recent Sessions (load with /load \"name\"):")}
${recentSessions.zipWithIndex
          .map { case (title, index) =>
            s"  ${index + 1}. ${config.styles.highlight(title)}"
          }
          .mkString("\n")}
"""
    } else {
      ""
    }

    val welcome = s"""
${config.styles.title("╭─────────────────────────────────────────────╮")}
${config.styles.title("│           🤖 LLM4S Assistant Agent          │")}
${config.styles.title("╰─────────────────────────────────────────────╯")}

${config.styles.bold("Available Tools:")}
${tools.tools.map(tool => s"  • ${tool.name}").mkString("\n")}$recentSessionsDisplay

${config.styles.bold("Commands:")}
  • ${config.styles.command("/load \"Session Name\"")} - Continue a previous session
  • ${config.styles.command("/help")} - Show this help message
  • ${config.styles.command("/new")} - Start a new conversation
  • ${config.styles.command("/save [title]")} - Save current session
  • ${config.styles.command("/sessions")} - List recent sessions
  • ${config.styles.command("/quit")} - Save and exit

${config.colorScheme(MessageType.Success)("Just type your message to start chatting or load a session!")}
"""
    Try(println(welcome)).toEither.leftMap(ex =>
      AssistantError.DisplayError(s"Failed to show welcome message: ${ex.getMessage}", "welcome", Some(ex))
    )
  }

  /**
   * Prompts user for input
   */
  def promptUser(): Either[AssistantError, String] =
    Try {
      print(config.styles.prompt(s"\n${config.promptSymbol}"))
      Option(StdIn.readLine())
    }.toEither
      .leftMap(ex => AssistantError.IOError(s"Failed to read input: ${ex.getMessage}", "read", Some(ex)))
      .flatMap {
        case Some(input) => Right(input)
        case None        => Left(AssistantError.EOFError("EOF reached", "read"))
      }

  /**
   * Prompts user for input with custom prompt text
   */
  def promptForInput(promptText: String): Either[AssistantError, String] =
    Try {
      print(config.styles.command(promptText))
      Option(StdIn.readLine())
    }.toEither
      .leftMap(ex => AssistantError.IOError(s"Failed to read input: ${ex.getMessage}", "read", Some(ex)))
      .flatMap(_.toRight(AssistantError.EOFError("EOF reached", "read")))

  /**
   * Displays a message to the user
   */
  def displayMessage(message: String, messageType: MessageType = MessageType.Info): Either[AssistantError, Unit] = {
    val styledMessage = messageType match {
      case MessageType.Info    => message
      case MessageType.Success => config.colorScheme(MessageType.Success)(message).toString
      case MessageType.Warning => config.colorScheme(MessageType.Warning)(message).toString
      case MessageType.Error   => config.colorScheme(MessageType.Error)(message).toString
      case MessageType.AssistantResponse =>
        s"\n${config.colorScheme(MessageType.AssistantResponse)(config.assistantSymbol)} $message"
    }

    Try(println(styledMessage)).toEither.leftMap(ex =>
      AssistantError.DisplayError(s"Failed to display message: ${ex.getMessage}", "message", Some(ex))
    )
  }

  /**
   * Shows help message
   */
  def showHelp(): String =
    s"""${config.styles.bold("Available Commands:")}
  • ${config.styles.command("/load \"Session Name\"")} - Continue a previous session
  • ${config.styles.command("/help")} - Show this help message
  • ${config.styles.command("/new")} - Start a new conversation (saves current)
  • ${config.styles.command("/save [title]")} - Save current session with optional title
  • ${config.styles.command("/sessions")} - List recent saved sessions
  • ${config.styles.command("/quit")} - Save current session and exit

${config.styles.bold("Available Tools:")}
${tools.tools.map(tool => s"  • ${config.styles.highlight(tool.name)} - ${tool.description}").mkString("\n")}

${config.styles.bold("Tips:")}
  • Conversations continue across multiple messages
  • Sessions are automatically saved as markdown files
  • Use natural language - the assistant will use tools as needed
"""

  /**
   * Formats session summaries for display
   */
  def formatSessionList(sessions: Seq[String]): String =
    if (sessions.isEmpty) {
      "No saved sessions found."
    } else {
      val formatted = sessions
        .map(title => s"  • ${config.styles.highlight(title)}")
        .mkString("\n")

      s"${config.styles.bold("Recent sessions:")}\n$formatted"
    }

  /**
   * Displays an error message
   */
  def displayError(error: String): Either[AssistantError, Unit] =
    displayMessage(s"Error: $error", MessageType.Error)

  /**
   * Displays a success message
   */
  def displaySuccess(message: String): Either[AssistantError, Unit] =
    displayMessage(message, MessageType.Success)

  /**
   * Displays an LLM error with proper formatting
   */
  def displayLLMError(error: LLMError): Either[AssistantError, Unit] =
    displayMessage(s"LLM Error: ${error.formatted}", MessageType.Error)
}

/**
 * Enumeration for different message types
 */
sealed trait MessageType
object MessageType {
  case object Info              extends MessageType
  case object Success           extends MessageType
  case object Warning           extends MessageType
  case object Error             extends MessageType
  case object AssistantResponse extends MessageType

  // Show instance for better formatting
  implicit val showMessageType: Show[MessageType] = Show.show {
    case Info              => "Info"
    case Success           => "Success"
    case Warning           => "Warning"
    case Error             => "Error"
    case AssistantResponse => "Assistant"
  }
}

// Show instances for error types
object ShowInstances {
  implicit val showAssistantError: Show[AssistantError] = Show.show {
    case AssistantError.IOError(message, _, _)      => s"IO Error: $message"
    case AssistantError.EOFError(message, _)        => s"EOF Error: $message"
    case AssistantError.DisplayError(message, _, _) => s"Display Error: $message"
    case error                                      => error.message
  }

  implicit val showLLMError: Show[LLMError] = Show.show(_.formatted)
}
