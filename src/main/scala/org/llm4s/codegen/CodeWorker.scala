package org.llm4s.codegen

import org.llm4s.agent.{ Agent, AgentState, AgentStatus }
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._
import org.llm4s.shared._
import org.llm4s.toolapi._
import org.llm4s.workspace.ContainerisedWorkspace
import org.slf4j.LoggerFactory
import upickle.default._

import scala.util.{ Failure, Success, Try }

/**
 * A worker for code generation and manipulation tasks.
 * CodeWorker combines a workspace environment with an LLM agent to handle
 * tasks involving code base understanding, modification, and generation.
 *
 * @param sourceDirectory The directory containing the codebase to work with
 */
class CodeWorker(sourceDirectory: String) {
  private val logger    = LoggerFactory.getLogger(getClass)
  private val workspace = new ContainerisedWorkspace(sourceDirectory)
  private val client    = LLM.client()
  private val agent     = new Agent(client)

  // Custom tool definitions for working with code
  private val workspaceTools = createWorkspaceTools()
  private val toolRegistry   = new ToolRegistry(workspaceTools)

  /**
   * Initialize the workspace and prepare for code tasks
   * @return true if the workspace was initialized successfully
   */
  def initialize(): Boolean = {
    logger.info(s"Initializing CodeWorker for directory: $sourceDirectory")
    workspace.startContainer()
  }

  /**
   * Execute a code task and return the result
   * @param task The description of the code task to perform
   * @param maxSteps Maximum number of agent steps to run (None for unlimited)
   * @param traceLogPath Optional path to write a markdown trace file
   * @return Either an error or the agent's final state
   */
  def executeTask(
      task: String, 
      maxSteps: Option[Int] = None,
      traceLogPath: Option[String] = None
  ): Either[LLMError, AgentState] = {
    val infoResponse = workspace.getWorkspaceInfo()
    if (infoResponse.root.isEmpty) {
      return Left(ValidationError("Workspace is not initialized"))
    }

    logger.info(s"Executing code task: $task")
    if (traceLogPath.isDefined) {
      logger.info(s"Trace log will be written to: ${traceLogPath.get}")
    }

    // Run the agent to completion or until step limit is reached
    val result = agent.run(task, toolRegistry, maxSteps, traceLogPath)

    result match {
      case Right(finalState) =>
        logger.info(s"Task completed with status: ${finalState.status}")
        if (finalState.status == AgentStatus.Complete) {
          logger.info("Task completed successfully")
        } else {
          logger.warn(s"Task did not complete successfully: ${finalState.status}")
        }
      case Left(error) =>
        logger.error(s"Task execution failed: ${error.message}")
    }

    result
  }

  /**
   * Clean up resources when done
   */
  def shutdown(): Boolean = {
    logger.info("Shutting down CodeWorker")
    workspace.stopContainer()
  }

  /**
   * Create tool definitions for the workspace operations
   */
  private def createWorkspaceTools(): Seq[ToolFunction[_, _]] = {
    // File exploration tool
    val exploreToolSchema = Schema
      .`object`[Map[String, Any]]("Explore files parameters")
      .withProperty(Schema.property("path", Schema.string("Path to explore")))

    def exploreHandler(params: SafeParameterExtractor): Either[String, ujson.Value] = {
      val path      = params.getString("path").getOrElse("/workspace")
      val recursive = params.getBoolean("recursive").getOrElse(false)

      Try(workspace.exploreFiles(path, recursive = Some(recursive))) match {
        case Success(response) =>
          val filesObj = ujson.Arr.from(response.files.map(f =>
            ujson.Obj("path" -> f.path, "type" -> (if (f.isDirectory) "directory" else "file"))
          ))
          Right(ujson.Obj("files" -> filesObj))
        case Failure(ex) =>
          Left(s"Exception exploring files: ${ex.getMessage}")
      }
    }

    val exploreTool = ToolBuilder[Map[String, Any], ujson.Value](
      "explore_files",
      "List files and directories at a given path",
      exploreToolSchema
    ).withHandler(exploreHandler).build()

    // Read file tool
    val readToolSchema = Schema
      .`object`[Map[String, Any]]("Read file parameters")
      .withProperty(Schema.property("path", Schema.string("Path to the file to read")))

    def readHandler(params: SafeParameterExtractor): Either[String, ujson.Value] = {
      val path      = params.getString("path").getOrElse("")
      val startLine = params.getInt("start_line").toOption.map(Some(_)).getOrElse(None)
      val endLine   = params.getInt("end_line").toOption.map(Some(_)).getOrElse(None)

      Try(workspace.readFile(path, startLine, endLine)) match {
        case Success(response) =>
          Right(ujson.Obj("content" -> response.content))
        case Failure(ex) =>
          Left(s"Exception reading file: ${ex.getMessage}")
      }
    }

    val readTool = ToolBuilder[Map[String, Any], ujson.Value](
      "read_file",
      "Read the contents of a file",
      readToolSchema
    ).withHandler(readHandler).build()

    // Write file tool
    val writeToolSchema = Schema
      .`object`[Map[String, Any]]("Write file parameters")
      .withProperty(Schema.property("path", Schema.string("Path to write the file to")))
      .withProperty(Schema.property("content", Schema.string("Content to write to the file")))

    def writeHandler(params: SafeParameterExtractor): Either[String, ujson.Value] = {
      val path       = params.getString("path").getOrElse("")
      val content    = params.getString("content").getOrElse("")
      val createDirs = params.getBoolean("create_directories").getOrElse(true)

      Try(workspace.writeFile(path, content, createDirectories = Some(createDirs))) match {
        case Success(response) =>
          Right(ujson.Obj("success" -> response.success))
        case Failure(ex) =>
          Left(s"Exception writing file: ${ex.getMessage}")
      }
    }

    val writeTool = ToolBuilder[Map[String, Any], ujson.Value](
      "write_file",
      "Write content to a file",
      writeToolSchema
    ).withHandler(writeHandler).build()

    // Modify file tool
    val operationSchema = Schema
      .`object`[Map[String, Any]]("File operation")
      .withProperty(
        Schema.property(
          "operation",
          Schema.string("Type of operation: 'replace', 'insert', or 'delete'")
        )
      )
      .withProperty(
        Schema.property("start_line", Schema.integer("Start line for the operation (0-indexed)"))
      )
      .withProperty(
        Schema.property("end_line", Schema.integer("End line for the operation (0-indexed)"))
      )
      .withProperty(
        Schema.property(
          "new_content",
          Schema.string("New content for replace or insert operations"),
          required = false
        )
      )

    val modifyToolSchema = Schema
      .`object`[Map[String, Any]]("Modify file parameters")
      .withProperty(Schema.property("path", Schema.string("Path to the file to modify")))
      .withProperty(
        Schema.property(
          "operations",
          Schema.array("List of operations to perform", operationSchema)
        )
      )

    def modifyHandler(params: SafeParameterExtractor): Either[String, ujson.Value] = {
      val path     = params.getString("path").getOrElse("")
      val opsParam = params.getArray("operations")

      opsParam match {
        case Right(arr) =>
          try {
            val operations = arr.arr.map { opObj =>
              val extractor = SafeParameterExtractor(opObj)
              val op        = extractor.getString("operation").getOrElse("replace")
              val startLine = extractor.getInt("start_line").getOrElse(0)
              val endLine   = extractor.getInt("end_line").getOrElse(0)
              val newContent = extractor.getString("new_content").getOrElse("")

              op match {
                case "replace" => ReplaceOperation(startLine = startLine, endLine = endLine, newContent = newContent)
                case "insert"  => InsertOperation(afterLine = startLine, newContent = newContent)
                case "delete"  => DeleteOperation(startLine = startLine, endLine = endLine)
                case _         => throw new IllegalArgumentException(s"Unknown operation type: $op")
              }
            }.toList

            workspace.modifyFile(path, operations) match {
              case response if response.success =>
                Right(ujson.Obj("success" -> true))
              case response =>
                Left(s"Failed to modify file")
            }
          } catch {
            case ex: Exception =>
              Left(s"Exception modifying file: ${ex.getMessage}")
          }
        case Left(error) =>
          Left(s"Invalid operations parameter: $error")
      }
    }

    val modifyTool = ToolBuilder[Map[String, Any], ujson.Value](
      "modify_file",
      "Modify a file with operations like replace, insert, or delete",
      modifyToolSchema
    ).withHandler(modifyHandler).build()

    // Search files tool
    val searchToolSchema = Schema
      .`object`[Map[String, Any]]("Search files parameters")
      .withProperty(
        Schema.property(
          "paths",
          Schema.array("Paths to search in", Schema.string("Path to search in"))
        )
      )
      .withProperty(Schema.property("query", Schema.string("Text or pattern to search for")))

    def searchHandler(params: SafeParameterExtractor): Either[String, ujson.Value] = {
      val paths      = params.getArray("paths").toOption.map(_.arr.map(p => p.str).toList).getOrElse(List("/workspace"))
      val query      = params.getString("query").getOrElse("")
      val searchType = params.getString("search_type").getOrElse("literal")
      val recursive  = params.getBoolean("recursive").getOrElse(true)

      Try(workspace.searchFiles(paths, query, searchType, recursive = Some(recursive))) match {
        case Success(response) =>
          val matches = response.matches.map(m =>
            ujson.Obj(
              "path"       -> m.path,
              "line"       -> m.line,
              "match_text" -> m.matchText,
              "context"    -> ujson.Str(
                (m.contextBefore.mkString("\n") + "\n" + m.matchText + "\n" + m.contextAfter.mkString("\n")).trim
              )
            )
          )
          Right(ujson.Obj("matches" -> ujson.Arr.from(matches)))
        case Failure(ex) =>
          Left(s"Exception searching files: ${ex.getMessage}")
      }
    }

    val searchTool = ToolBuilder[Map[String, Any], ujson.Value](
      "search_files",
      "Search for content in files",
      searchToolSchema
    ).withHandler(searchHandler).build()

    // Execute command tool
    val executeToolSchema = Schema
      .`object`[Map[String, Any]]("Execute a command on the shell")
      .withProperty(Schema.property("command", Schema.string("The shell command to execute")))

    def executeHandler(params: SafeParameterExtractor): Either[String, ujson.Value] = {
      val command    = params.getString("command").getOrElse("")
      val workingDir = params.getString("working_directory").getOrElse("/workspace")
      val timeout    = params.getInt("timeout").toOption.map(Some(_)).getOrElse(None)

      try {
        val execResult = workspace.executeCommand(command, workingDirectory = Some(workingDir), timeout = timeout)
        Right(
          ujson.Obj(
            "exit_code" -> execResult.exitCode,
            "stdout"    -> execResult.stdout,
            "stderr"    -> execResult.stderr
          )
        )
      } catch {
        case ex: Exception =>
          Left(s"Exception executing command: ${ex.getMessage}")
      }
    }

    val executeTool = ToolBuilder[Map[String, Any], ujson.Value](
      "execute_command",
      "Execute a command in the workspace",
      executeToolSchema
    ).withHandler(executeHandler).build()

    Seq(exploreTool, readTool, writeTool, modifyTool, searchTool, executeTool)
  }
}