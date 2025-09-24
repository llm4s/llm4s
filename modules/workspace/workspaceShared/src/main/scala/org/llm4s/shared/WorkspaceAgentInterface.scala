package org.llm4s.shared

/**
 * Exception thrown when a workspace agent command fails
 */
class WorkspaceAgentException(
  val error: String,
  val code: String,
  val details: Option[String] = None
) extends RuntimeException(s"$code: $error${details.map(d => s" - $d").getOrElse("")}")

/**
 * Interface that provides methods corresponding to WorkspaceAgentCommands.
 */
trait WorkspaceAgentInterface {

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
  def exploreFiles(
    path: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    maxDepth: Option[Int] = None,
    returnMetadata: Option[Boolean] = None
  ): ExploreFilesResponse

  /**
   * Read the content of a file, with options to read specific line ranges.
   *
   * @param path Path to file
   * @param startLine Optional start line (1-indexed)
   * @param endLine Optional end line (1-indexed)
   * @return Response with file content and metadata
   */
  def readFile(
    path: String,
    startLine: Option[Int] = None,
    endLine: Option[Int] = None
  ): ReadFileResponse

  /**
   * Write content to a file, creating the file if it doesn't exist.
   *
   * @param path Path to file
   * @param content Content to write
   * @param mode Write mode (default: "overwrite")
   * @param createDirectories Create parent directories if they don't exist
   * @return Response with write operation result
   */
  def writeFile(
    path: String,
    content: String,
    mode: Option[String] = None,
    createDirectories: Option[Boolean] = None
  ): WriteFileResponse

  /**
   * Perform targeted modifications to a file without rewriting the entire content.
   *
   * @param path Path to file
   * @param operations List of operations to perform
   * @return Response with modification result
   */
  def modifyFile(
    path: String,
    operations: List[FileOperation]
  ): ModifyFileResponse

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
  def searchFiles(
    paths: List[String],
    query: String,
    searchType: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    contextLines: Option[Int] = None
  ): SearchFilesResponse

  /**
   * Execute a shell command in the workspace.
   *
   * @param command Command to execute
   * @param workingDirectory Working directory (default: workspace root)
   * @param timeout Timeout in milliseconds
   * @param environment Environment variables
   * @return Response with command execution result
   */
  def executeCommand(
    command: String,
    workingDirectory: Option[String] = None,
    timeout: Option[Int] = None,
    environment: Option[Map[String, String]] = None
  ): ExecuteCommandResponse

  /**
   * Retrieve information about the workspace, including default settings and limits.
   *
   * @return Response with workspace information
   */
  def getWorkspaceInfo(): GetWorkspaceInfoResponse
}
