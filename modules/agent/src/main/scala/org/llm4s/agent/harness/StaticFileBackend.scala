package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ StateKey, StateUpdate }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.time.Instant
import java.util.regex.Pattern

/**
 * Files the application ships, fixed when the backend is built and read-only: skills and `AGENTS.md` for an agent
 * whose files are otherwise thread state (design §4.38). On a [[StateFileBackend]] only a tool call writes a file,
 * so mount this in a [[CompositeBackend]] - at `/app`, say - and point `SkillsConfig.withSources` and
 * `InstructionsConfig.withPaths` there; every thread reads the same files, and no tool can change them.
 *
 * Paths are the backend's own, as for every backend in a composite: a file the agent reads at `/app/skills/a/SKILL.md`
 * through a mount at `/app` is given here as `/skills/a/SKILL.md`. `ls`, `read`, `glob` and `grep` see the files; `write`, `edit` and `delete` are refused, so a file tool reports a
 * tool error. It writes no state key, so it adds nothing to a checkpoint.
 */
@Experimental
final class StaticFileBackend private (files: Map[VPath, FileEntry]) extends FileBackend:

  val writes: Set[StateKey[?, ?]] = Set.empty

  private val lock: LockKey = FileBackend.LockRoot / "static"

  def locks(path: VPath): Set[LockKey] = Set(lock)

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    FileTree.ls(files, path, limit).map(l => l.copy(items = l.items.map(_.copy(modified = None))))

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    FileTree.read(files, path, offset, limit)

  private def readOnly[A](operation: String, path: VPath): Result[A] =
    Left(BackendRefused.at(operation, path, "is read-only: the application's files cannot be changed"))

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    readOnly("write", path)

  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] = readOnly("edit", path)

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] = readOnly("delete", path)

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    FileTree.find(files, under, accept, limit)

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    FileTree.search(files, under, regex, accept, limit)

@Experimental
object StaticFileBackend:

  /** The default bound on the number of files. */
  val DefaultMaxFiles: Int = 1000

  /** 1 MiB: the default bound on the files' paths and contents together, as JSON strings. */
  val DefaultMaxBytes: Long = 1024L * 1024

  /**
   * A backend holding `files`, by path. They are checked as every backend checks the files it reads: UTF-8 text with
   * no NUL, at most `maxFiles` files and `maxBytes` bytes of paths and contents together, and no path both a file and
   * a directory (none at the root). A bundle that breaks one is a `ValidationError`.
   */
  def apply(
    files: Map[VPath, String],
    maxFiles: Int = DefaultMaxFiles,
    maxBytes: Long = DefaultMaxBytes
  ): Result[StaticFileBackend] =
    if maxFiles < 1 then Left(ValidationError("maxFiles", s"must be at least 1, was $maxFiles"))
    else if maxBytes < 1 then Left(ValidationError("maxBytes", s"must be at least 1, was $maxBytes"))
    else
      val entries = files.map((path, content) => path -> FileEntry(content, Instant.EPOCH))
      FileTree
        .validated("load", entries, FileTree.Bounds(maxFiles, maxBytes, maxBytes))
        .left
        .map(refused => ValidationError("files", refused.message))
        .map(new StaticFileBackend(_))
