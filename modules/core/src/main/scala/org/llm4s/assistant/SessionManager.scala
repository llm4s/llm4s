package org.llm4s.assistant

import org.llm4s.agent.{ Agent, AgentState, AgentStatus }
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
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
import org.apache.commons.io.FileUtils

/**
 * Manages session persistence using functional programming principles
 */
class SessionManager(sessionDir: DirectoryPath, agent: Agent) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Converts SessionState to JSON format for persistence (using upickle automatic derivation)
   * Note: We handle ToolRegistry separately since it contains function references
   */
  private def sessionStateToJson(state: SessionState): String = {
    logger.debug("Starting SessionState serialization")

    // Test each component separately
    val sessionIdJson = ujson.Str(state.sessionId.value)
    logger.debug("SessionId serialized successfully")

    val sessionDirJson = ujson.Str(state.sessionDir.value)
    logger.debug("SessionDir serialized successfully")

    val createdJson = ujson.Str(state.created.toString)
    logger.debug("Created timestamp serialized successfully")

    val agentStateJson = state.agentState match {
      case None =>
        logger.debug("No agent state to serialize")
        ujson.Null
      case Some(agentState) =>
        logger.debug("Serializing agent state components")

        // Test conversation serialization
        val conversationJson = Try(write(agentState.conversation)).getOrElse {
          val ex = new RuntimeException("Conversation serialization failed")
          logger.error("Failed to serialize conversation", ex)
          throw ex
        }
        logger.debug("Conversation serialized successfully")

        // Test status serialization
        val statusJson = Try(write(agentState.status)).getOrElse {
          val ex = new RuntimeException("AgentStatus serialization failed")
          logger.error("Failed to serialize agent status", ex)
          throw ex
        }
        logger.debug("AgentStatus serialized successfully")

        ujson.Obj(
          "conversation" -> ujson.read(conversationJson),
          "userQuery"    -> ujson.Str(agentState.userQuery),
          "status"       -> ujson.read(statusJson),
          "logs"         -> ujson.Arr.from(agentState.logs.map(ujson.Str(_))),
          "toolNames"    -> ujson.Arr.from(agentState.tools.tools.map(t => ujson.Str(t.name)))
        )
    }

    val jsonObj = ujson.Obj(
      "sessionId"  -> sessionIdJson,
      "sessionDir" -> sessionDirJson,
      "created"    -> createdJson,
      "agentState" -> agentStateJson
    )

    val result = ujson.write(jsonObj)
    logger.debug("SessionState serialization completed successfully")
    result
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
    state.agentState match {
      case None =>
        Left(AssistantError.SessionError("No agent state to save", SessionId("unknown"), "save"))
      case Some(agentState) =>
        val sessionTitle = title.getOrElse("Session")
        logger.info("Saving session {} with title: {}", state.sessionId, sessionTitle)
        ensureSessionDirectory().flatMap { _ =>
          createFilePaths(title).flatMap { case (jsonPath, markdownPath) =>
            for {
              jsonContent     <- createJsonContent(state)
              markdownContent <- formatSessionContent(agentState, sessionTitle, state.created)
              jsonSize        <- writeSessionFile(jsonPath, jsonContent)
              _               <- writeSessionFile(markdownPath, markdownContent)
              sessionInfo     <- createSessionInfo(state, sessionTitle, jsonPath, agentState, jsonSize)
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
    Try(sessionStateToJson(state)).toEither
      .leftMap(ex => AssistantError.jsonSerializationFailed("SessionState", ex))

  /**
   * Converts JSON back to SessionState for loading
   * Note: We reconstruct ToolRegistry from the provided tools parameter
   */
  private def jsonToSessionState(json: ujson.Value, tools: ToolRegistry): SessionState = {
    val obj = json.obj
    logger.debug("Parsing sessionId...")
    val sessionId = SessionId(obj("sessionId").str)
    logger.debug("Parsing sessionDir...")
    val sessionDir = DirectoryPath(obj("sessionDir").str)
    logger.debug("Parsing created timestamp...")
    val created = LocalDateTime.parse(obj("created").str)

    logger.debug("Parsing agentState...")
    val agentState = obj("agentState") match {
      case ujson.Null =>
        logger.debug("AgentState is null")
        None
      case agentObj =>
        val agentObjMap = agentObj.obj
        logger.debug("Parsing conversation...")
        val conversation = read[Conversation](agentObjMap("conversation"))
        logger.debug("Parsing userQuery...")
        val userQuery = agentObjMap("userQuery").str
        logger.debug("Parsing status...")
        val status = read[AgentStatus](agentObjMap("status"))
        logger.debug("Parsing logs...")
        val logs = agentObjMap("logs").arr.map(_.str).toSeq
        logger.debug("Creating AgentState...")
        Some(AgentState(conversation, tools, userQuery, status, logs))
    }

    logger.debug("Creating SessionState...")
    SessionState(agentState, sessionId, sessionDir, created)
  }

  /**
   * Loads a session from JSON file by title
   */
  def loadSession(sessionTitle: String, tools: ToolRegistry): Either[AssistantError, SessionState] = {
    val jsonPath = Paths.get(sessionDir.value, s"${sanitizeFilename(sessionTitle)}.json")

    for {
      _ <- ensureSessionDirectory()
      _ <- Either.cond(Files.exists(jsonPath), (), AssistantError.sessionTitleNotFound(sessionTitle))
      jsonContent <- Try(Files.readString(jsonPath, StandardCharsets.UTF_8)).toEither
        .leftMap(ex => AssistantError.fileReadFailed(FilePath(jsonPath.toString), ex))
      json <- Try(ujson.read(jsonContent)).toEither
        .leftMap(ex => AssistantError.jsonDeserializationFailed("JSON", ex))
      _ = logger.debug("JSON content keys: {}", json.obj.keySet.mkString(", "))
      state <- Try(jsonToSessionState(json, tools)).toEither
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
   * Finds a unique filename using system temp directory for uniqueness guarantee
   */
  private def findUniqueFilename(baseFilename: String): String = {
    def checkExists(filename: String): Boolean =
      Files.exists(Paths.get(sessionDir.value, s"$filename.json")) ||
        Files.exists(Paths.get(sessionDir.value, s"$filename.md"))

    if (!checkExists(baseFilename)) {
      baseFilename
    } else {
      // Use system temp directory to generate guaranteed unique filename
      val tempFile     = FileUtils.getFile(FileUtils.getTempDirectory, s"session_${System.nanoTime()}")
      val uniqueSuffix = tempFile.getName.substring(8) // Remove "session_" prefix
      s"$baseFilename-$uniqueSuffix"
    }
  }

  /**
   * Formats session content as markdown
   */
  private def formatSessionContent(
    agentState: AgentState,
    title: String,
    created: LocalDateTime
  ): Either[AssistantError, String] =
    Try {
      val header        = createSessionHeader(title, created, agentState)
      val agentMarkdown = agent.formatStateAsMarkdown(agentState)
      header + agentMarkdown
    }.toEither.leftMap(ex =>
      AssistantError.SerializationError(
        s"Failed to format session content: ${ex.getMessage}",
        "SessionContent",
        "format",
        Some(ex)
      )
    )

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
    agentState: AgentState,
    fileSize: Long
  ): Either[AssistantError, SessionInfo] =
    Try {
      SessionInfo(
        id = state.sessionId,
        title = title,
        filePath = FilePath(filePath.toString),
        created = state.created,
        messageCount = agentState.conversation.messages.length,
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
  private def createSessionHeader(title: String, created: LocalDateTime, agentState: AgentState): String =
    s"""# $title
       |
       |**Created:** ${created.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}
       |**Tools Available:** ${agentState.tools.tools.map(_.name).mkString(", ")}
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
