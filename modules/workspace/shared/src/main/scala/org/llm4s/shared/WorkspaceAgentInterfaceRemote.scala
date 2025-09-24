package org.llm4s.shared

import java.util.UUID
import scala.util.Try

/**
 * Remote implementation of WorkspaceAgentInterface that converts method calls to commands,
 * processes them through a handler, and converts responses back to appropriate return types.
 *
 * @param handler A function that processes WorkspaceAgentCommands and returns WorkspaceAgentResponses
 */
class WorkspaceAgentInterfaceRemote(handler: WorkspaceAgentCommand => WorkspaceAgentResponse)
    extends WorkspaceAgentInterface {

  private def generateCommandId(): String = UUID.randomUUID().toString

  private def handleCommand[T <: WorkspaceAgentResponse](
    command: WorkspaceAgentCommand,
    responseClass: Class[T]
  ): T = {
    val response = handler(command)
    response match {
      case error: WorkspaceAgentErrorResponse =>
        throw new WorkspaceAgentException(error.error, error.code, error.details)
      case r if responseClass.isInstance(r) =>
        r.asInstanceOf[T]
      case _ =>
        throw new WorkspaceAgentException(
          s"Unexpected response type: ${response.getClass.getSimpleName}",
          "UNEXPECTED_RESPONSE_TYPE",
          None
        )
    }
  }

  /**
   * List files and directories in a specified path, optionally recursively.
   *
   * @param path Path to explore
   * @param recursive Whether to explore recursively
   * @param excludePatterns Glob patterns to exclude
   * @param maxDepth Maximum recursion depth
   * @param returnMetadata Whether to include file metadata
   * @return Response with list of files and directories
   */
  override def exploreFiles(
    path: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    maxDepth: Option[Int] = None,
    returnMetadata: Option[Boolean] = None
  ): ExploreFilesResponse = {
    val command = ExploreFilesCommand(
      commandId = generateCommandId(),
      path = path,
      recursive = recursive,
      excludePatterns = excludePatterns,
      maxDepth = maxDepth,
      returnMetadata = returnMetadata
    )
    handleCommand(command, classOf[ExploreFilesResponse])
  }

  /**
   * Read the content of a file, with options to read specific line ranges.
   *
   * @param path Path to file
   * @param startLine Optional start line (1-indexed)
   * @param endLine Optional end line (1-indexed)
   * @return Response with file content and metadata
   */
  override def readFile(
    path: String,
    startLine: Option[Int] = None,
    endLine: Option[Int] = None
  ): ReadFileResponse = {
    val command = ReadFileCommand(
      commandId = generateCommandId(),
      path = path,
      startLine = startLine,
      endLine = endLine
    )
    handleCommand(command, classOf[ReadFileResponse])
  }

  /**
   * Write content to a file, creating the file if it doesn't exist.
   *
   * @param path Path to file
   * @param content Content to write
   * @param mode Write mode (default: "overwrite")
   * @param createDirectories Create parent directories if they don't exist
   * @return Response with write operation result
   */
  override def writeFile(
    path: String,
    content: String,
    mode: Option[String] = None,
    createDirectories: Option[Boolean] = None
  ): WriteFileResponse = {
    val command = WriteFileCommand(
      commandId = generateCommandId(),
      path = path,
      content = content,
      mode = mode,
      createDirectories = createDirectories
    )
    handleCommand(command, classOf[WriteFileResponse])
  }

  /**
   * Perform targeted modifications to a file without rewriting the entire content.
   *
   * @param path Path to file
   * @param operations List of operations to perform
   * @return Response with modification result
   */
  override def modifyFile(
    path: String,
    operations: List[FileOperation]
  ): ModifyFileResponse = {
    val command = ModifyFileCommand(
      commandId = generateCommandId(),
      path = path,
      operations = operations
    )
    handleCommand(command, classOf[ModifyFileResponse])
  }

  /**
   * Search for content in files across the workspace.
   *
   * @param paths Paths to search in
   * @param query Search query
   * @param searchType Search type ("regex" or "literal")
   * @param recursive Whether to search recursively
   * @param excludePatterns Glob patterns to exclude
   * @param contextLines Number of context lines to include
   * @return Response with search results
   */
  override def searchFiles(
    paths: List[String],
    query: String,
    searchType: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    contextLines: Option[Int] = None
  ): SearchFilesResponse = {
    val command = SearchFilesCommand(
      commandId = generateCommandId(),
      paths = paths,
      query = query,
      `type` = searchType,
      recursive = recursive,
      excludePatterns = excludePatterns,
      contextLines = contextLines
    )
    handleCommand(command, classOf[SearchFilesResponse])
  }

  /**
   * Execute a shell command in the workspace.
   *
   * @param command Command to execute
   * @param workingDirectory Working directory (default: workspace root)
   * @param timeout Timeout in milliseconds
   * @param environment Environment variables
   * @return Response with command execution result
   */
  override def executeCommand(
    command: String,
    workingDirectory: Option[String] = None,
    timeout: Option[Int] = None,
    environment: Option[Map[String, String]] = None
  ): ExecuteCommandResponse = {
    val cmd = ExecuteCommandCommand(
      commandId = generateCommandId(),
      command = command,
      workingDirectory = workingDirectory,
      timeout = timeout,
      environment = environment
    )
    handleCommand(cmd, classOf[ExecuteCommandResponse])
  }

  /**
   * Retrieve information about the workspace, including default settings and limits.
   *
   * @return Response with workspace information
   */
  override def getWorkspaceInfo(): GetWorkspaceInfoResponse = {
    val command = GetWorkspaceInfoCommand(
      commandId = generateCommandId()
    )
    handleCommand(command, classOf[GetWorkspaceInfoResponse])
  }
}

/**
 * Companion object for WorkspaceAgentInterfaceRemote with factory methods.
 */
object WorkspaceAgentInterfaceRemote {

  /**
   * Create a new WorkspaceAgentInterfaceRemote with the given handler function.
   *
   * @param handler A function that processes WorkspaceAgentCommands and returns WorkspaceAgentResponses
   * @return A new WorkspaceAgentInterfaceRemote instance
   */
  def apply(handler: WorkspaceAgentCommand => WorkspaceAgentResponse): WorkspaceAgentInterfaceRemote =
    new WorkspaceAgentInterfaceRemote(handler)

  /**
   * Create a new WorkspaceAgentInterfaceRemote with a handler that wraps exceptions in ErrorResponses.
   *
   * @param handler A partial function that processes WorkspaceAgentCommands and returns WorkspaceAgentResponses
   * @return A new WorkspaceAgentInterfaceRemote instance with exception handling
   */
  def withErrorHandling(
    handler: PartialFunction[WorkspaceAgentCommand, WorkspaceAgentResponse]
  ): WorkspaceAgentInterfaceRemote = {
    val safeHandler: WorkspaceAgentCommand => WorkspaceAgentResponse = cmd =>
      if (handler.isDefinedAt(cmd)) {
        Try(handler(cmd)).fold(
          e =>
            WorkspaceAgentErrorResponse(
              cmd.commandId,
              s"Error processing command: ${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}",
              "EXECUTION_FAILED",
              Some(e.getStackTrace.mkString("\n"))
            ),
          identity
        )
      } else {
        WorkspaceAgentErrorResponse(
          cmd.commandId,
          s"No handler defined for command type: ${cmd.getClass.getSimpleName}",
          "INVALID_COMMAND",
          None
        )
      }

    new WorkspaceAgentInterfaceRemote(safeHandler)
  }
}
