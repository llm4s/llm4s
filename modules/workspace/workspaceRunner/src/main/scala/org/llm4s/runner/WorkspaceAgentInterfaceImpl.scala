package org.llm4s.runner

import org.llm4s.shared._

import java.io.{ BufferedWriter, PrintWriter }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths, StandardOpenOption }
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.sys.process._
import scala.util.{ Failure, Success, Try, Using }

/**
 * Implementation of WorkspaceAgentInterface that operates on a local filesystem workspace.
 *
 * @param workspaceRoot The root directory of the workspace
 * @param isWindows     True if the host OS is Windows (used for shell command selection)
 * @param sandboxConfig Optional sandbox config; if None, uses [[WorkspaceSandboxConfig.Permissive]]
 */
class WorkspaceAgentInterfaceImpl(
  workspaceRoot: String,
  isWindows: Boolean,
  sandboxConfig: Option[WorkspaceSandboxConfig] = None
) extends WorkspaceAgentInterface {

  private val rootPath = Paths.get(workspaceRoot).toAbsolutePath.normalize()

  private val config        = sandboxConfig.getOrElse(WorkspaceSandboxConfig.Permissive)
  private val defaultLimits = config.limits
  private val defaultExclusions =
    if (config.excludePatterns.nonEmpty) config.excludePatterns
    else WorkspaceSandboxConfig.DefaultExclusions

  /**
   * Resolves a relative path against the workspace root, ensuring it doesn't escape the workspace.
   *
   * @param relativePath The path relative to workspace root
   * @return The absolute path
   * @throws IllegalArgumentException if the path attempts to escape the workspace
   */
  private def resolvePath(relativePath: String): Path = {
    val normalized = rootPath.resolve(relativePath).normalize()

    if (!normalized.startsWith(rootPath)) {
      throw new IllegalArgumentException(s"Path '$relativePath' attempts to escape the workspace")
    }

    normalized
  }

  /**
   * Creates file metadata for a given path.
   *
   * @param path The file path
   * @return FileMetadata object
   */
  private def createFileMetadata(path: Path): FileMetadata = {
    val file         = path.toFile
    val relativePath = rootPath.relativize(path).toString

    FileMetadata(
      path = relativePath,
      size = file.length(),
      isDirectory = file.isDirectory,
      lastModified = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(file.lastModified()))
    )
  }

  /**
   * Checks if a path matches any of the exclusion patterns.
   *
   * @param path The path to check
   * @param excludePatterns Patterns to exclude
   * @return true if the path should be excluded
   */
  private def isExcluded(path: String, excludePatterns: List[String]): Boolean = {
    // Simple glob matching implementation
    // In a real implementation, use a proper glob library
    val normalizedPath = path.replace("\\", "/")
    excludePatterns.exists { pattern =>
      val normalizedPattern = pattern.replace("\\", "/")
      // Use a placeholder to avoid corrupting ** when replacing *
      val placeholder = "\u0000DOUBLESTAR\u0000"
      val regex = normalizedPattern
        .replace("**", placeholder) // protect ** first
        .replace(".", "\\.")        // escape dots
        .replace("*", "[^/]+")      // single * matches path segment chars
        .replace(placeholder, ".*") // restore ** as .* to match any path

      normalizedPath.matches(regex) || (normalizedPath + "/").matches(regex)
    }
  }

  /**
   * List files and directories in a specified path, optionally recursively.
   */
  override def exploreFiles(
    path: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    maxDepth: Option[Int] = None,
    returnMetadata: Option[Boolean] = None
  ): ExploreFilesResponse = {
    val resolvedPath    = resolvePath(path)
    val isRecursive     = recursive.getOrElse(false)
    val depth           = maxDepth.getOrElse(if (isRecursive) 3 else 1)
    val includeMetadata = returnMetadata.getOrElse(false)
    val patterns        = excludePatterns.getOrElse(defaultExclusions)

    if (!Files.exists(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"Path '$path' does not exist",
        "PATH_NOT_FOUND",
        None
      )
    }

    if (!Files.isDirectory(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"Path '$path' is not a directory",
        "NOT_A_DIRECTORY",
        None
      )
    }

    val stream = if (isRecursive) Files.walk(resolvedPath, depth) else Files.list(resolvedPath)
    Using(stream) { s =>
      // Use lazy evaluation: filter and limit on the iterator before materializing to a list
      // This prevents loading the entire directory tree into memory for large repos
      val filteredFiles = s
        .iterator()
        .asScala
        .filterNot { p =>
          val relativePath = rootPath.relativize(p).toString
          isExcluded(relativePath, patterns)
        }
        .take(defaultLimits.maxDirectoryEntries + 1)
        .toList

      val isTruncated = filteredFiles.size > defaultLimits.maxDirectoryEntries
      val files = filteredFiles.take(defaultLimits.maxDirectoryEntries).map { p =>
        val relativePath = rootPath.relativize(p).toString
        val isDir        = Files.isDirectory(p)

        FileEntry(
          path = relativePath,
          isDirectory = isDir,
          metadata = if (includeMetadata) Some(createFileMetadata(p)) else None
        )
      }

      ExploreFilesResponse(
        commandId = "local",
        files = files,
        isTruncated = isTruncated,
        totalFound = filteredFiles.size
      )
    }.get
  }

  /**
   * Read the content of a file, with options to read specific line ranges.
   */
  override def readFile(
    path: String,
    startLine: Option[Int] = None,
    endLine: Option[Int] = None
  ): ReadFileResponse = {
    val resolvedPath = resolvePath(path)

    if (!Files.exists(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"File '$path' does not exist",
        "FILE_NOT_FOUND",
        None
      )
    }

    if (Files.isDirectory(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"Path '$path' is a directory, not a file",
        "NOT_A_FILE",
        None
      )
    }

    val fileSize = Files.size(resolvedPath)
    if (fileSize > defaultLimits.maxFileSize) {
      throw new WorkspaceAgentException(
        s"File '$path' exceeds maximum size limit (${defaultLimits.maxFileSize} bytes)",
        "SIZE_LIMIT_EXCEEDED",
        Some(s"File size: $fileSize bytes")
      )
    }

    val metadata = createFileMetadata(resolvedPath)

    Using(Source.fromFile(resolvedPath.toFile, StandardCharsets.UTF_8.name())) { source =>
      val lines      = source.getLines().toList
      val totalLines = lines.size

      val start = startLine.map(l => math.max(1, math.min(l, totalLines)) - 1).getOrElse(0)
      val end   = endLine.map(l => math.max(start + 1, math.min(l, totalLines))).getOrElse(totalLines)

      val selectedLines = lines.slice(start, end)
      val content       = selectedLines.mkString("\n")

      ReadFileResponse(
        commandId = "local",
        content = content,
        metadata = metadata,
        isTruncated = false,
        totalLines = totalLines,
        returnedLines = selectedLines.size
      )
    } match {
      case Success(response) => response
      case Failure(e) =>
        throw new WorkspaceAgentException(
          s"Failed to read file '$path': ${e.getMessage}",
          "READ_ERROR",
          None
        )
    }
  }

  /**
   * Write content to a file, creating the file if it doesn't exist.
   */
  override def writeFile(
    path: String,
    content: String,
    mode: Option[String] = None,
    createDirectories: Option[Boolean] = None
  ): WriteFileResponse = {
    val resolvedPath = resolvePath(path)
    val writeMode    = mode.getOrElse("overwrite")
    val createDirs   = createDirectories.getOrElse(false)

    if (createDirs) {
      Files.createDirectories(resolvedPath.getParent)
    } else if (!Files.exists(resolvedPath.getParent)) {
      throw new WorkspaceAgentException(
        s"Parent directory for '$path' does not exist",
        "DIRECTORY_NOT_FOUND",
        None
      )
    }

    val options = writeMode match {
      case "create" =>
        if (Files.exists(resolvedPath)) {
          throw new WorkspaceAgentException(
            s"File '$path' already exists and mode is 'create'",
            "FILE_EXISTS",
            None
          )
        }
        Array(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)

      case "append" =>
        Array(StandardOpenOption.CREATE, StandardOpenOption.APPEND)

      case "overwrite" =>
        Array(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)

      case _ =>
        throw new WorkspaceAgentException(
          s"Invalid write mode: $writeMode. Must be 'create', 'overwrite', or 'append'",
          "INVALID_ARGUMENT",
          None
        )
    }

    Try {
      val bytes = content.getBytes(StandardCharsets.UTF_8)
      Files.write(resolvedPath, bytes, options: _*)

      WriteFileResponse(
        commandId = "local",
        success = true,
        path = path,
        bytesWritten = bytes.length
      )
    }.recover { case e: Exception =>
      throw new WorkspaceAgentException(
        s"Failed to write to file '$path': ${e.getMessage}",
        "WRITE_ERROR",
        None
      )
    }.get
  }

  /**
   * Perform targeted modifications to a file without rewriting the entire content.
   */
  override def modifyFile(
    path: String,
    operations: List[FileOperation]
  ): ModifyFileResponse = {
    val resolvedPath = resolvePath(path)

    if (!Files.exists(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"File '$path' does not exist",
        "FILE_NOT_FOUND",
        None
      )
    }

    if (Files.isDirectory(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"Path '$path' is a directory, not a file",
        "NOT_A_FILE",
        None
      )
    }

    // Read the file content
    val lines = Using(Source.fromFile(resolvedPath.toFile, StandardCharsets.UTF_8.name()))(_.getLines().toList) match {
      case Success(fileLines) => fileLines
      case Failure(e) =>
        throw new WorkspaceAgentException(
          s"Failed to read file '$path': ${e.getMessage}",
          "READ_ERROR",
          None
        )
    }

    // Apply operations
    val modifiedLines = applyOperations(lines, operations)

    // Write back to file using UTF-8 encoding
    Using(
      new PrintWriter(
        new BufferedWriter(
          new java.io.OutputStreamWriter(
            new java.io.FileOutputStream(resolvedPath.toFile),
            StandardCharsets.UTF_8
          )
        )
      )
    )(writer => modifiedLines.foreach(writer.println)) match {
      case Success(_) =>
        ModifyFileResponse(
          commandId = "local",
          success = true,
          path = path
        )
      case Failure(e) =>
        throw new WorkspaceAgentException(
          s"Failed to write modified content to file '$path': ${e.getMessage}",
          "WRITE_ERROR",
          None
        )
    }
  }

  /**
   * Apply file operations to a list of lines.
   *
   * @param lines Original file lines
   * @param operations Operations to apply
   * @return Modified lines
   */
  private def applyOperations(lines: List[String], operations: List[FileOperation]): List[String] =
    operations.foldLeft(lines) { (currentLines, operation) =>
      operation match {
        case ReplaceOperation(_, startLine, endLine, newContent) =>
          val start = math.max(1, startLine) - 1
          val end   = math.min(endLine, currentLines.size)

          if (start >= currentLines.size || end < start) {
            throw new WorkspaceAgentException(
              s"Invalid line range: $startLine-$endLine for file with ${currentLines.size} lines",
              "INVALID_ARGUMENT",
              None
            )
          }

          val newLines = newContent.split("\n").toList
          currentLines.take(start) ++ newLines ++ currentLines.drop(end)

        case InsertOperation(_, afterLine, newContent) =>
          val pos = math.min(afterLine, currentLines.size)

          if (pos < 0) {
            throw new WorkspaceAgentException(
              s"Invalid line position: $afterLine",
              "INVALID_ARGUMENT",
              None
            )
          }

          val newLines = newContent.split("\n").toList
          currentLines.take(pos) ++ newLines ++ currentLines.drop(pos)

        case DeleteOperation(_, startLine, endLine) =>
          val start = math.max(1, startLine) - 1
          val end   = math.min(endLine, currentLines.size)

          if (start >= currentLines.size || end < start) {
            throw new WorkspaceAgentException(
              s"Invalid line range: $startLine-$endLine for file with ${currentLines.size} lines",
              "INVALID_ARGUMENT",
              None
            )
          }

          currentLines.take(start) ++ currentLines.drop(end)

        case RegexReplaceOperation(_, pattern, replacement, flags) =>
          val patternFlags = flags.getOrElse("")
          val regexFlags = {
            var result = 0
            if (patternFlags.contains("i")) result |= Pattern.CASE_INSENSITIVE
            if (patternFlags.contains("m")) result |= Pattern.MULTILINE
            if (patternFlags.contains("s")) result |= Pattern.DOTALL
            result
          }

          val regex = Pattern.compile(pattern, regexFlags)

          currentLines.map { line =>
            val matcher = regex.matcher(line)
            if (patternFlags.contains("g")) {
              matcher.replaceAll(replacement)
            } else {
              matcher.replaceFirst(replacement)
            }
          }
      }
    }

  /**
   * Search for content in files across the workspace.
   */
  override def searchFiles(
    paths: List[String],
    query: String,
    searchType: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    contextLines: Option[Int] = None
  ): SearchFilesResponse = {
    val isRecursive = recursive.getOrElse(true)
    val context     = contextLines.getOrElse(2)
    val patterns    = excludePatterns.getOrElse(defaultExclusions)

    if (!List("regex", "literal").contains(searchType)) {
      throw new WorkspaceAgentException(
        s"Invalid search type: $searchType. Must be 'regex' or 'literal'",
        "INVALID_ARGUMENT",
        None
      )
    }

    // Prepare regex pattern
    val pattern = if (searchType == "literal") {
      Pattern.compile(Pattern.quote(query))
    } else {
      Try(Pattern.compile(query)) match {
        case Success(p) => p
        case Failure(e) =>
          throw new WorkspaceAgentException(
            s"Invalid regex pattern: ${e.getMessage}",
            "INVALID_ARGUMENT",
            None
          )
      }
    }

    // Collect all files to search
    val filesToSearch = paths.flatMap { path =>
      val resolvedPath = resolvePath(path)

      if (!Files.exists(resolvedPath)) {
        throw new WorkspaceAgentException(
          s"Path '$path' does not exist",
          "PATH_NOT_FOUND",
          None
        )
      }

      if (Files.isDirectory(resolvedPath)) {
        val stream = if (isRecursive) Files.walk(resolvedPath) else Files.list(resolvedPath)
        Using.resource(stream) { s =>
          s.iterator()
            .asScala
            .filter(p => Files.isRegularFile(p))
            .filterNot { p =>
              val relativePath = rootPath.relativize(p).toString
              isExcluded(relativePath, patterns)
            }
            .toList
        }
      } else {
        List(resolvedPath)
      }
    }

    // Search in files
    var matches      = List.empty[SearchMatch]
    var totalMatches = 0
    var done         = false   // used to break out once we've observed one match past the cap

    // stop scanning as soon as we've counted one result beyond the configured
    // max; that allows us to report `isTruncated` correctly while avoiding a
    // full workspace sweep. the `totalMatches` value is therefore only guaranteed
    // to be accurate up to maxSearchResults+1.
    for (file <- filesToSearch if !done) {
      val relativePath = rootPath.relativize(file).toString

      Try(Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toList).toOption.foreach { lines =>
        for ((line, lineIndex) <- lines.zipWithIndex if !done) {
          val matcher = pattern.matcher(line)

          if (matcher.find()) {
            totalMatches += 1

            if (matches.size < defaultLimits.maxSearchResults) {
              val lineNumber    = lineIndex + 1
              val beforeContext = lines.slice(math.max(0, lineIndex - context), lineIndex)
              val afterContext  = lines.slice(lineIndex + 1, math.min(lines.size, lineIndex + context + 1))

              matches = matches :+ SearchMatch(
                path = relativePath,
                line = lineNumber,
                matchText = line,
                contextBefore = beforeContext,
                contextAfter = afterContext
              )
            }

            // once we've seen one hit past the cap we can stop scanning entirely
            if (totalMatches > defaultLimits.maxSearchResults) {
              done = true
            }
          }
        }
      }
    }

    SearchFilesResponse(
      commandId = "local",
      matches = matches,
      isTruncated = totalMatches > matches.size,
      totalMatches = totalMatches
    )
  }

  /**
   * Execute a shell command in the workspace.
   * When sandbox config has shellAllowed=false, throws WorkspaceAgentException.
   */
  override def executeCommand(
    command: String,
    workingDirectory: Option[String] = None,
    timeoutSeconds: Option[Int] = None,
    environment: Option[Map[String, String]] = None
  ): ExecuteCommandResponse = {
    if (!config.shellAllowed) {
      throw new WorkspaceAgentException(
        "Shell execution is disabled by sandbox config (shellAllowed=false)",
        "SHELL_DISABLED",
        None
      )
    }

    val workDir = workingDirectory
      .map(dir => resolvePath(dir).toFile)
      .getOrElse(rootPath.toFile)

    if (!workDir.exists() || !workDir.isDirectory) {
      throw new WorkspaceAgentException(
        s"Working directory does not exist or is not a directory",
        "INVALID_DIRECTORY",
        None
      )
    }

    val timeoutMs = (timeoutSeconds.getOrElse(config.defaultCommandTimeoutSeconds) * 1000).toLong
    val env       = environment.getOrElse(Map.empty)

    val cmd = if (isWindows) {
      Seq("cmd.exe", "/c", command)
    } else {
      Seq("sh", "-c", command)
    }
    val processBuilder = Process(
      command = cmd,
      cwd = workDir,
      extraEnv = env.toSeq: _*
    )

    val stdout    = new StringBuilder
    val stderr    = new StringBuilder
    val startTime = System.currentTimeMillis()

    val exitCode = Try {
      val process = processBuilder.run(
        ProcessLogger(
          line =>
            if (stdout.length < defaultLimits.maxOutputSize) {
              stdout.append(line).append("\n")
            },
          line =>
            if (stderr.length < defaultLimits.maxOutputSize) {
              stderr.append(line).append("\n")
            }
        )
      )

      while (process.isAlive() && System.currentTimeMillis() - startTime < timeoutMs)
        Thread.sleep(100)

      val completed = !process.isAlive()

      if (!completed) {
        process.destroy()
        throw new WorkspaceAgentException(
          s"Command execution timed out after ${timeoutMs}ms",
          "TIMEOUT",
          None
        )
      }

      process.exitValue()
    }.recover {
      case e: Exception if !e.isInstanceOf[WorkspaceAgentException] =>
        throw new WorkspaceAgentException(
          s"Failed to execute command: ${e.getMessage}",
          "EXECUTION_FAILED",
          None
        )
    }.get

    val duration          = System.currentTimeMillis() - startTime
    val isStdoutTruncated = stdout.length >= defaultLimits.maxOutputSize
    val isStderrTruncated = stderr.length >= defaultLimits.maxOutputSize

    ExecuteCommandResponse(
      commandId = "local",
      stdout = stdout.toString(),
      stderr = stderr.toString(),
      exitCode = exitCode,
      isOutputTruncated = isStdoutTruncated || isStderrTruncated,
      durationMs = duration
    )
  }

  /**
   * Retrieve information about the workspace, including default settings and limits.
   */
  override def getWorkspaceInfo(): GetWorkspaceInfoResponse =
    GetWorkspaceInfoResponse(
      commandId = "local",
      root = rootPath.toString,
      defaultExclusions = defaultExclusions,
      limits = defaultLimits
    )
}
