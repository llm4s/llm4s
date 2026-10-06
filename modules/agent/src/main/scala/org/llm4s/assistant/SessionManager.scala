package org.llm4s.assistant

import org.llm4s.llmconnect.model._
import org.llm4s.error.AssistantError
import org.llm4s.types.{ SessionId, DirectoryPath, FilePath }
import cats.implicits._
import java.nio.file.{ Files, Path, Paths, StandardOpenOption }
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.Try
import org.slf4j.LoggerFactory
import upickle.default._

/**
 * Manages session persistence for the interactive assistant.
 *
 * SessionManager handles saving and loading conversation sessions to disk,
 * enabling users to resume previous conversations and maintain context
 * across multiple interactions.
 *
 * == Key Features ==
 *  - '''Session Save''': Persists the conversation's messages as JSON with markdown companion
 *  - '''Session Load''': Restores the messages, which the next turn imports into a new agent thread
 *  - '''Session Listing''': Shows recent sessions sorted by modification time
 *  - '''Filename Sanitization''': Safely handles special characters in titles
 *
 * == Storage Format ==
 * Sessions are stored as two files:
 *  - `{title}.json` - Machine-readable session state for loading
 *  - `{title}.md` - Human-readable markdown for viewing/sharing
 *
 * == Usage Example ==
 * {{{
 * val manager = new SessionManager(DirectoryPath("/path/to/sessions"))
 *
 * // Save current session
 * val info = manager.saveSession(sessionState, Some("My Conversation"))
 *
 * // List recent sessions
 * val sessions = manager.listRecentSessions(limit = 5)
 *
 * // Load a previous session
 * val loaded = manager.loadSession("My Conversation")
 * }}}
 *
 * @param sessionDir Directory path where sessions are stored
 * @param uniqueSuffix Generator for the suffix used to disambiguate filename collisions; injectable for testing
 * @see [[SessionState]] for the state being persisted
 * @see [[SessionInfo]] for session metadata returned after save
 */
class SessionManager(
  sessionDir: DirectoryPath,
  uniqueSuffix: () => String = () => System.nanoTime().toString
) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Converts SessionState to JSON for persistence: its identity and the conversation's messages.
   * The agent thread is not saved; loading imports the messages into a new one.
   */
  private def sessionStateToJson(state: SessionState): Either[AssistantError, String] =
    Try(writeJs(state.messages)).toEither
      .leftMap { ex =>
        logger.error("Failed to serialize messages", ex)
        AssistantError.jsonSerializationFailed("Messages", ex)
      }
      .map { messagesJson =>
        ujson.write(
          ujson.Obj(
            "sessionId"  -> ujson.Str(state.sessionId.value),
            "sessionDir" -> ujson.Str(state.sessionDir.value),
            "created"    -> ujson.Str(state.created.toString),
            "messages"   -> messagesJson
          )
        )
      }

  /**
   * Ensures the session directory exists
   */
  private def ensureSessionDirectory(): Either[AssistantError, Path] = {
    val path = Paths.get(sessionDir.value)
    Try(Files.createDirectories(path)).toEither
      .leftMap(ex => AssistantError.fileWriteFailed(FilePath(sessionDir.value), ex))
      .map(_ => path)
  }

  /**
   * Saves a session in both JSON and markdown formats
   */
  def saveSession(state: SessionState, title: Option[String] = None): Either[AssistantError, SessionInfo] =
    state.messages match {
      case messages if messages.isEmpty =>
        Left(AssistantError.SessionError("No conversation to save", SessionId("unknown"), "save"))
      case messages =>
        val sessionTitle = title.getOrElse("Session")
        logger.info("Saving session {} with title: {}", state.sessionId, sessionTitle)
        ensureSessionDirectory().flatMap { _ =>
          createFilePaths(title).flatMap { case (jsonPath, markdownPath) =>
            for {
              jsonContent     <- createJsonContent(state)
              markdownContent <- formatSessionContent(messages, sessionTitle, state.created)
              jsonSize        <- writeSessionFile(jsonPath, jsonContent)
              _               <- writeSessionFile(markdownPath, markdownContent)
              sessionInfo     <- createSessionInfo(state, sessionTitle, jsonPath, messages, jsonSize)
            } yield {
              logger.info("Successfully saved session JSON: {} and markdown: {}", jsonPath, markdownPath)
              sessionInfo
            }
          }
        }
    }

  /**
   * Creates JSON content for session storage
   */
  private def createJsonContent(state: SessionState): Either[AssistantError, String] =
    sessionStateToJson(state)

  /**
   * Converts JSON back to SessionState for loading: the saved messages become the history the
   * next turn imports.
   */
  private def jsonToSessionState(json: ujson.Value): SessionState = {
    val obj = json.obj
    SessionState(
      threadId = None,
      last = None,
      sessionId = SessionId(obj("sessionId").str),
      sessionDir = DirectoryPath(obj("sessionDir").str),
      created = LocalDateTime.parse(obj("created").str),
      history = read[Vector[Message]](obj("messages"))
    )
  }

  /**
   * Loads a session from JSON file by title
   */
  def loadSession(sessionTitle: String): Either[AssistantError, SessionState] = {
    val jsonPath = Paths.get(sessionDir.value, s"${sanitizeFilename(sessionTitle)}.json")

    for {
      _ <- ensureSessionDirectory()
      _ <- Either.cond(Files.exists(jsonPath), (), AssistantError.sessionTitleNotFound(sessionTitle))
      jsonContent <- Try(Files.readString(jsonPath, StandardCharsets.UTF_8)).toEither
        .leftMap(ex => AssistantError.fileReadFailed(FilePath(jsonPath.toString), ex))
      json <- Try(ujson.read(jsonContent)).toEither
        .leftMap(ex => AssistantError.jsonDeserializationFailed("JSON", ex))
      _ = logger.debug("JSON content keys: {}", json.obj.keySet.mkString(", "))
      state <- Try(jsonToSessionState(json)).toEither
        .leftMap { ex =>
          logger.error("Failed to deserialize SessionState. JSON content preview: {}", jsonContent.take(500))
          logger.error("Deserialization error details:", ex)
          AssistantError.jsonDeserializationFailed("SessionState", ex)
        }
    } yield {
      logger.info("Successfully loaded session: {} from {}", sessionTitle, jsonPath)
      state
    }
  }

  /**
   * Lists recent sessions for welcome screen display
   */
  def listRecentSessions(limit: Int = 5): Either[AssistantError, Seq[String]] =
    Try {
      Files
        .list(Paths.get(sessionDir.value))
        .filter(_.toString.endsWith(".json"))
        .toArray
        .map(_.asInstanceOf[Path])
        .sortBy(path => Try(Files.getLastModifiedTime(path)).getOrElse(java.nio.file.attribute.FileTime.fromMillis(0)))
        .reverse
        .take(limit)
        .map(_.getFileName.toString.stripSuffix(".json"))
        .toSeq
    }.toEither
      .leftMap(ex => AssistantError.fileReadFailed(FilePath(sessionDir.value), ex))

  /**
   * Creates file paths for the session (both JSON and markdown)
   */
  private def createFilePaths(title: Option[String]): Either[AssistantError, (Path, Path)] =
    Try {
      val sessionTitle = title.getOrElse("Untitled Session")
      val baseFilename = sanitizeFilename(sessionTitle)

      // Handle naming collisions by appending numbers
      val uniqueBasename = findUniqueFilename(baseFilename)

      val jsonPath     = Paths.get(sessionDir.value, s"$uniqueBasename.json")
      val markdownPath = Paths.get(sessionDir.value, s"$uniqueBasename.md")
      (jsonPath, markdownPath)
    }.toEither.leftMap(ex =>
      AssistantError.FileError(
        s"Failed to create file paths: ${ex.getMessage}",
        FilePath(sessionDir.value),
        "create",
        Some(ex)
      )
    )

  /**
   * Finds a unique filename, appending a generated suffix on collision
   */
  private def findUniqueFilename(baseFilename: String): String = {
    def checkExists(filename: String): Boolean =
      Files.exists(Paths.get(sessionDir.value, s"$filename.json")) ||
        Files.exists(Paths.get(sessionDir.value, s"$filename.md"))

    if (!checkExists(baseFilename)) {
      baseFilename
    } else {
      s"$baseFilename-${uniqueSuffix()}"
    }
  }

  /**
   * Formats session content as markdown
   */
  private def formatSessionContent(
    messages: Vector[Message],
    title: String,
    created: LocalDateTime
  ): Either[AssistantError, String] =
    Try(createSessionHeader(title, created, messages) + formatMessages(messages)).toEither.leftMap(ex =>
      AssistantError.SerializationError(
        s"Failed to format session content: ${ex.getMessage}",
        "SessionContent",
        "format",
        Some(ex)
      )
    )

  /** One markdown section per message, with each assistant tool call's name and arguments. */
  private def formatMessages(messages: Vector[Message]): String =
    messages.zipWithIndex
      .map { case (message, i) =>
        val calls = message match {
          case a: AssistantMessage =>
            a.toolCalls.map(c => s"\n- Tool call `${c.name}` (${c.id}): `${c.arguments.render()}`").mkString
          case t: ToolMessage => s"\n\n_Result of tool call ${t.toolCallId}_"
          case _              => ""
        }
        s"### ${i + 1}. ${message.role}\n\n${message.content}$calls\n"
      }
      .mkString("\n")

  /**
   * Writes session content to file and returns file size
   */
  private def writeSessionFile(filePath: Path, content: String): Either[AssistantError, Long] =
    Try {
      Files.write(
        filePath,
        content.getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
      Files.size(filePath)
    }.toEither.leftMap(ex => AssistantError.fileWriteFailed(FilePath(filePath.toString), ex))

  /**
   * Creates session info from the saved session
   */
  private def createSessionInfo(
    state: SessionState,
    title: String,
    filePath: Path,
    messages: Vector[Message],
    fileSize: Long
  ): Either[AssistantError, SessionInfo] =
    Try {
      SessionInfo(
        id = state.sessionId,
        title = title,
        filePath = FilePath(filePath.toString),
        created = state.created,
        messageCount = messages.length,
        fileSize = fileSize
      )
    }.toEither.leftMap(ex =>
      AssistantError.SessionError(
        s"Failed to create session info: ${ex.getMessage}",
        state.sessionId,
        "create",
        Some(ex)
      )
    )

  /**
   * Creates session header markdown
   */
  private def createSessionHeader(title: String, created: LocalDateTime, messages: Vector[Message]): String =
    s"""# $title
       |
       |**Created:** ${created.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}
       |**Messages:** ${messages.length}
       |
       |---
       |
       |""".stripMargin

  /**
   * Sanitizes filename by removing invalid characters
   */
  private def sanitizeFilename(filename: String): String =
    filename.replaceAll("[^a-zA-Z0-9.-]", "_").take(50)

}
