package org.llm4s.toolapi

import org.llm4s.shared._
import org.llm4s.workspace.ContainerisedWorkspace
import upickle.default._

import org.llm4s.types.TryOps
import scala.util.{ Failure, Success, Try }

/**
 * Factory object for creating workspace tools with consistent schemas and handlers.
 * This eliminates duplication across different use cases while allowing customization
 * of handler behavior and return types.
 */
object WorkspaceTools {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /**
   * Create an explore files tool that lists files and directories.
   *
   * @param workspace The workspace to operate on
   * @param includeRecursive Whether to include recursive parameter in schema
   * @param handler Custom handler function for parameter extraction and result transformation
   * @tparam T Return type of the handler
   * @return A configured ToolFunction
   */
  def createExploreTool[T: ReadWriter](
    workspace: ContainerisedWorkspace,
    includeRecursive: Boolean = true
  )(
    handler: (SafeParameterExtractor, ContainerisedWorkspace) => Either[String, T]
  ): ToolFunction[Map[String, Any], T] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Explore files parameters")
      .withProperty(Schema.property("path", Schema.string("Path to explore")))

    val finalSchema = if (includeRecursive) {
      schema.withProperty(
        Schema.property(
          "recursive",
          Schema.boolean("Whether to explore recursively"),
          required = false
        )
      )
    } else schema

    def wrappedHandler(params: SafeParameterExtractor): Either[String, T] =
      handler(params, workspace)

    ToolBuilder[Map[String, Any], T](
      "explore_files",
      "List files and directories at a given path",
      finalSchema
    ).withHandler(wrappedHandler).build()
  }

  /**
   * Create a read file tool that reads file contents.
   *
   * @param workspace The workspace to operate on
   * @param includeLineParams Whether to include start_line and end_line parameters
   * @param handler Custom handler function for parameter extraction and result transformation
   * @tparam T Return type of the handler
   * @return A configured ToolFunction
   */
  def createReadTool[T: ReadWriter](
    workspace: ContainerisedWorkspace,
    includeLineParams: Boolean = true
  )(
    handler: (SafeParameterExtractor, ContainerisedWorkspace) => Either[String, T]
  ): ToolFunction[Map[String, Any], T] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Read file parameters")
      .withProperty(Schema.property("path", Schema.string("Path to the file to read")))

    val finalSchema = if (includeLineParams) {
      schema
        .withProperty(
          Schema.property(
            "start_line",
            Schema.integer("Start line for reading (0-indexed)"),
            required = false
          )
        )
        .withProperty(
          Schema.property(
            "end_line",
            Schema.integer("End line for reading (0-indexed)"),
            required = false
          )
        )
    } else schema

    def wrappedHandler(params: SafeParameterExtractor): Either[String, T] =
      handler(params, workspace)

    ToolBuilder[Map[String, Any], T](
      "read_file",
      "Read the contents of a file",
      finalSchema
    ).withHandler(wrappedHandler).build()
  }

  /**
   * Create a write file tool that writes content to files.
   *
   * @param workspace The workspace to operate on
   * @param includeCreateDirs Whether to include create_directories parameter
   * @param handler Custom handler function for parameter extraction and result transformation
   * @tparam T Return type of the handler
   * @return A configured ToolFunction
   */
  def createWriteTool[T: ReadWriter](
    workspace: ContainerisedWorkspace,
    includeCreateDirs: Boolean = true
  )(
    handler: (SafeParameterExtractor, ContainerisedWorkspace) => Either[String, T]
  ): ToolFunction[Map[String, Any], T] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Write file parameters")
      .withProperty(Schema.property("path", Schema.string("Path to write the file to")))
      .withProperty(Schema.property("content", Schema.string("Content to write to the file")))

    val finalSchema = if (includeCreateDirs) {
      schema.withProperty(
        Schema.property(
          "create_directories",
          Schema.boolean("Whether to create parent directories if they don't exist"),
          required = false
        )
      )
    } else schema

    def wrappedHandler(params: SafeParameterExtractor): Either[String, T] =
      handler(params, workspace)

    ToolBuilder[Map[String, Any], T](
      "write_file",
      "Write content to a file",
      finalSchema
    ).withHandler(wrappedHandler).build()
  }

  /**
   * Create a search files tool that searches for content in files.
   *
   * @param workspace The workspace to operate on
   * @param includeSearchType Whether to include search_type parameter
   * @param includeRecursive Whether to include recursive parameter
   * @param handler Custom handler function for parameter extraction and result transformation
   * @tparam T Return type of the handler
   * @return A configured ToolFunction
   */
  def createSearchTool[T: ReadWriter](
    workspace: ContainerisedWorkspace,
    includeSearchType: Boolean = true,
    includeRecursive: Boolean = true
  )(
    handler: (SafeParameterExtractor, ContainerisedWorkspace) => Either[String, T]
  ): ToolFunction[Map[String, Any], T] = {
    val baseSchema = Schema
      .`object`[Map[String, Any]]("Search files parameters")
      .withProperty(
        Schema.property(
          "paths",
          Schema.array("Paths to search in", Schema.string("Path to search in"))
        )
      )
      .withProperty(Schema.property("query", Schema.string("Text or pattern to search for")))

    val withSearchType = if (includeSearchType) {
      baseSchema.withProperty(
        Schema.property(
          "search_type",
          Schema.string("Type of search: 'literal' or 'regex'"),
          required = false
        )
      )
    } else baseSchema

    val finalSchema = if (includeRecursive) {
      withSearchType.withProperty(
        Schema.property(
          "recursive",
          Schema.boolean("Whether to search recursively"),
          required = false
        )
      )
    } else withSearchType

    def wrappedHandler(params: SafeParameterExtractor): Either[String, T] =
      handler(params, workspace)

    ToolBuilder[Map[String, Any], T](
      "search_files",
      "Search for content in files",
      finalSchema
    ).withHandler(wrappedHandler).build()
  }

  /**
   * Create an execute command tool that runs shell commands.
   *
   * @param workspace The workspace to operate on
   * @param includeWorkingDir Whether to include working_directory parameter
   * @param includeTimeout Whether to include timeout parameter
   * @param handler Custom handler function for parameter extraction and result transformation
   * @tparam T Return type of the handler
   * @return A configured ToolFunction
   */
  def createExecuteTool[T: ReadWriter](
    workspace: ContainerisedWorkspace,
    includeWorkingDir: Boolean = true,
    includeTimeout: Boolean = true
  )(
    handler: (SafeParameterExtractor, ContainerisedWorkspace) => Either[String, T]
  ): ToolFunction[Map[String, Any], T] = {
    val baseSchema = Schema
      .`object`[Map[String, Any]]("Execute a command on the shell")
      .withProperty(Schema.property("command", Schema.string("The shell command to execute")))

    val withWorkingDir = if (includeWorkingDir) {
      baseSchema.withProperty(
        Schema.property(
          "working_directory",
          Schema.string("Working directory for command execution"),
          required = false
        )
      )
    } else baseSchema

    val finalSchema = if (includeTimeout) {
      withWorkingDir.withProperty(
        Schema.property(
          "timeout",
          Schema.integer("Timeout in seconds for command execution"),
          required = false
        )
      )
    } else withWorkingDir

    def wrappedHandler(params: SafeParameterExtractor): Either[String, T] =
      handler(params, workspace)

    ToolBuilder[Map[String, Any], T](
      "execute_command",
      "Execute a command in the workspace",
      finalSchema
    ).withHandler(wrappedHandler).build()
  }

  /**
   * Create a modify file tool that performs operations like replace, insert, or delete.
   *
   * @param workspace The workspace to operate on
   * @param handler Custom handler function for parameter extraction and result transformation
   * @tparam T Return type of the handler
   * @return A configured ToolFunction
   */
  def createModifyTool[T: ReadWriter](
    workspace: ContainerisedWorkspace
  )(
    handler: (SafeParameterExtractor, ContainerisedWorkspace) => Either[String, T]
  ): ToolFunction[Map[String, Any], T] = {
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

    val schema = Schema
      .`object`[Map[String, Any]]("Modify file parameters")
      .withProperty(Schema.property("path", Schema.string("Path to the file to modify")))
      .withProperty(
        Schema.property(
          "operations",
          Schema.array("List of operations to perform", operationSchema)
        )
      )

    def wrappedHandler(params: SafeParameterExtractor): Either[String, T] =
      handler(params, workspace)

    ToolBuilder[Map[String, Any], T](
      "modify_file",
      "Modify a file with operations like replace, insert, or delete",
      schema
    ).withHandler(wrappedHandler).build()
  }

  // Predefined handler functions for common use cases

  /**
   * Default handler for explore tool that returns ujson.Value
   */
  def defaultExploreHandler(
    params: SafeParameterExtractor,
    workspace: ContainerisedWorkspace
  ): Either[String, ujson.Value] = {
    val path      = params.getString("path").getOrElse("/workspace")
    val recursive = params.getBoolean("recursive").getOrElse(false)

    Try(workspace.exploreFiles(path, recursive = Some(recursive))) match {
      case Success(response) =>
        val filesObj = ujson.Arr.from(
          response.files.map(f => ujson.Obj("path" -> f.path, "type" -> (if (f.isDirectory) "directory" else "file")))
        )
        Right(ujson.Obj("files" -> filesObj))
      case Failure(ex) =>
        Left(s"Exception exploring files: ${ex.getMessage}")
    }
  }

  /**
   * Default handler for read tool that returns ujson.Value
   */
  def defaultReadHandler(
    params: SafeParameterExtractor,
    workspace: ContainerisedWorkspace
  ): Either[String, ujson.Value] = {
    val path      = params.getString("path").getOrElse("")
    val startLine = params.getInt("start_line").toOption.flatMap(Some(_))
    val endLine   = params.getInt("end_line").toOption.flatMap(Some(_))

    Try(workspace.readFile(path, startLine, endLine)) match {
      case Success(response) =>
        Right(ujson.Obj("content" -> response.content))
      case Failure(ex) =>
        Left(s"Exception reading file: ${ex.getMessage}")
    }
  }

  /**
   * Default handler for write tool that returns ujson.Value
   */
  def defaultWriteHandler(
    params: SafeParameterExtractor,
    workspace: ContainerisedWorkspace
  ): Either[String, ujson.Value] = {
    val path       = params.getString("path").getOrElse("")
    val content    = params.getString("content").getOrElse("")
    val createDirs = params.getBoolean("create_directories").getOrElse(true)

    {
      val r = Try(workspace.writeFile(path, content, createDirectories = Some(createDirs))).toResult
      for {
        resp <- r.left.map(_.formatted)
      } yield ujson.Obj("success" -> resp.success)
    }
  }

  /**
   * Default handler for search tool that returns ujson.Value
   */
  def defaultSearchHandler(
    params: SafeParameterExtractor,
    workspace: ContainerisedWorkspace
  ): Either[String, ujson.Value] = {
    val paths      = params.getArray("paths").toOption.map(_.arr.map(p => p.str).toList).getOrElse(List("/workspace"))
    val query      = params.getString("query").getOrElse("")
    val searchType = params.getString("search_type").getOrElse("literal")
    val recursive  = params.getBoolean("recursive").getOrElse(true)

    (for {
      response <- Try(workspace.searchFiles(paths, query, searchType, recursive = Some(recursive))).toResult.left
        .map(_.formatted)
    } yield response) match {
      case Right(response) =>
        val matches = response.matches.map(m =>
          ujson.Obj(
            "path"       -> m.path,
            "line"       -> m.line,
            "match_text" -> m.matchText,
            "context" -> ujson.Str(
              (m.contextBefore.mkString("\n") + "\n" + m.matchText + "\n" + m.contextAfter.mkString("\n")).trim
            )
          )
        )
        Right(ujson.Obj("matches" -> ujson.Arr.from(matches)))
      case Left(err) =>
        Left(err)
    }
  }

  /**
   * Default handler for execute tool that returns ujson.Value
   */
  def defaultExecuteHandler(
    params: SafeParameterExtractor,
    workspace: ContainerisedWorkspace
  ): Either[String, ujson.Value] = {
    val command    = params.getString("command").getOrElse("")
    val workingDir = params.getString("working_directory").getOrElse("/workspace")
    val timeout    = params.getInt("timeout").toOption.flatMap(Some(_))

    {
      logger.info(s"Executing command: $command in directory: $workingDir with timeout: $timeout")
      val r = Try(workspace.executeCommand(command, workingDirectory = Some(workingDir), timeout = timeout)).toResult
      for {
        execResult <- r.left.map(_.formatted)
      } yield {
        logger.info(
          "Command execution complete - exitCode: " + execResult.exitCode +
            " stdout: " + execResult.stdout.length + " stderr: " + execResult.stderr.length + "b"
        )
        println(execResult.stdout)
        println(execResult.stderr)
        ujson.Obj(
          "exit_code" -> execResult.exitCode,
          "stdout"    -> execResult.stdout,
          "stderr"    -> execResult.stderr
        )
      }
    }
  }

  /**
   * Default handler for modify tool that returns ujson.Value
   */
  def defaultModifyHandler(
    params: SafeParameterExtractor,
    workspace: ContainerisedWorkspace
  ): Either[String, ujson.Value] = {
    val path     = params.getString("path").getOrElse("")
    val opsParam = params.getArray("operations")

    opsParam match {
      case Right(arr) =>
        val operations = arr.arr.map { opObj =>
          val extractor  = SafeParameterExtractor(opObj)
          val op         = extractor.getString("operation").getOrElse("replace")
          val startLine  = extractor.getInt("start_line").getOrElse(0)
          val endLine    = extractor.getInt("end_line").getOrElse(0)
          val newContent = extractor.getString("new_content").getOrElse("")

          op match {
            case "replace" => ReplaceOperation(startLine = startLine, endLine = endLine, newContent = newContent)
            case "insert"  => InsertOperation(afterLine = startLine, newContent = newContent)
            case "delete"  => DeleteOperation(startLine = startLine, endLine = endLine)
            case _         => throw new IllegalArgumentException(s"Unknown operation type: $op")
          }
        }.toList

        val r = Try(workspace.modifyFile(path, operations)).toResult
        for {
          _ <- r.left.map(_.formatted)
        } yield ujson.Obj("success" -> true)
      case Left(error) =>
        Left(s"Invalid operations parameter: $error")
    }
  }

  /**
   * Creates a complete set of workspace tools using default handlers.
   * This is equivalent to the original createWorkspaceTools method but using the factory methods.
   */
  def createDefaultWorkspaceTools(workspace: ContainerisedWorkspace): Seq[ToolFunction[_, _]] =
    Seq(
      createExploreTool(workspace)(defaultExploreHandler),
      createReadTool(workspace)(defaultReadHandler),
      createWriteTool(workspace)(defaultWriteHandler),
      createModifyTool(workspace)(defaultModifyHandler),
      createSearchTool(workspace)(defaultSearchHandler),
      createExecuteTool(workspace)(defaultExecuteHandler)
    )
}
