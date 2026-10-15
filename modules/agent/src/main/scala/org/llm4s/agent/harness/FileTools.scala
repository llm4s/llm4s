package org.llm4s.agent.harness

import org.llm4s.agent.graph.StateUpdate
import org.llm4s.agent.graph.tool.{
  AgentTool,
  AgentToolSpec,
  LockKey,
  Operation,
  ToolContent,
  ToolOutcome,
  ToolResource
}
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.toolapi.{ Schema, ToolHints }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * The bounds of [[FileTools]]' results (design §4.32, rule 12).
 *
 * @param readLimit the lines `read_file` returns when the model gives no `limit`, and the most it returns
 * @param maxLineChars the most characters of one line `read_file` and `grep` show; a longer line is cut and marked
 * @param maxResults the most entries `ls`, `glob` and `grep` return; a longer listing says it stopped
 * @param maxResultChars the most characters of any one result, an error's included; at least 256
 */
@Experimental
final case class FileToolLimits private (readLimit: Int, maxLineChars: Int, maxResults: Int, maxResultChars: Int):
  def withReadLimit(n: Int): FileToolLimits      = FileToolLimits(n, maxLineChars, maxResults, maxResultChars)
  def withMaxLineChars(n: Int): FileToolLimits   = FileToolLimits(readLimit, n, maxResults, maxResultChars)
  def withMaxResults(n: Int): FileToolLimits     = FileToolLimits(readLimit, maxLineChars, n, maxResultChars)
  def withMaxResultChars(n: Int): FileToolLimits = FileToolLimits(readLimit, maxLineChars, maxResults, n)

object FileToolLimits:

  /** The least `maxResultChars`: room for a result's notes, whatever else it holds. */
  val MinResultChars: Int = 256

  /** 2000 lines per read, 2000 characters per line, 500 results per listing, 40 000 characters per result. */
  val default: FileToolLimits = apply()

  def apply(
    readLimit: Int = 2000,
    maxLineChars: Int = 2000,
    maxResults: Int = 500,
    maxResultChars: Int = 40000
  ): FileToolLimits =
    require(readLimit >= 1, s"readLimit must be at least 1, was $readLimit")
    require(maxLineChars >= 1, s"maxLineChars must be at least 1, was $maxLineChars")
    require(maxResults >= 1, s"maxResults must be at least 1, was $maxResults")
    require(
      maxResultChars >= MinResultChars,
      s"maxResultChars must be at least $MinResultChars, was $maxResultChars"
    )
    new FileToolLimits(readLimit, maxLineChars, maxResults, maxResultChars)

/**
 * The filesystem tools over a [[FileBackend]] (design §4.32): `ls`, `read_file`, `write_file`, `edit_file`,
 * `delete`, `glob` and `grep`, as `AgentTool`s.
 *
 * {{{
 * val files = StateFileBackend()
 * Agent.builder("coder", client).withTools(ToolSet.of(FileTools(files)*).fold(e => throw ..., identity))
 * }}}
 *
 * The backend does the work and owns its obligations - locks, replay, bounds and tenant (§4.25); the tools
 * translate arguments into backend calls and results into bounded text:
 *
 *  - '''Paths''' are absolute (`/src/a.scala`): `.`, `..` and empty segments are refused, never normalised.
 *  - '''Resources''' (C2): `Read` of the path for `ls` and `read_file`; `Write` for `write_file` and
 *    `edit_file`; `Delete` for `delete`; and for `glob` and `grep` the envelope of every path they may read
 *    (`<path>/<pattern>`, `<path>/&#42;&#42;`), which a permission rule decides as a whole.
 *  - '''Locks''': each mutation holds `backend.locks(path)`, so two mutations of one file in one model message
 *    apply in order, the second against the first's result. Reads take no lock, so a read in the same message
 *    as a write is not ordered with it: over thread state it sees the file as it was before the message (the
 *    batch's snapshot); over an external backend (a store, a workspace) it may see either version.
 *  - '''Bounds''': `read_file` returns at most `readLimit` lines, each cut at `maxLineChars`; `ls`, `glob` and
 *    `grep` at most `maxResults` entries, and say when they stopped; every result is at most `maxResultChars`.
 *
 * Do not register these beside `llm4s-agent-tools`' host file tools: `read_file` and `write_file` are both
 * suites' names, and a `ToolSet` refuses a duplicate.
 */
@Experimental
object FileTools:

  val Ls: String        = "ls"
  val ReadFile: String  = "read_file"
  val WriteFile: String = "write_file"
  val EditFile: String  = "edit_file"
  val Delete: String    = "delete"
  val Glob: String      = "glob"
  val Grep: String      = "grep"

  /** The tools' names, in the order [[apply]] returns them. */
  val Names: Vector[String] = Vector(Ls, ReadFile, WriteFile, EditFile, Delete, Glob, Grep)

  /** The seven tools over `backend`, bounded by `limits`, in the order of [[Names]]. */
  def apply(backend: FileBackend, limits: FileToolLimits = FileToolLimits.default): Vector[AgentTool[?]] =
    new Suite(backend, limits).all

  // ---- arguments: private, decoded from the model's JSON each call ----

  final private case class LsArgs(path: Option[String] = None) derives ReadWriter
  final private case class ReadArgs(path: String, offset: Option[Int] = None, limit: Option[Int] = None)
      derives ReadWriter
  final private case class WriteArgs(path: String, content: String) derives ReadWriter
  final private case class EditArgs(
    path: String,
    old_string: String,
    new_string: String,
    replace_all: Option[Boolean] = None
  ) derives ReadWriter
  final private case class DeleteArgs(path: String) derives ReadWriter
  final private case class GlobArgs(pattern: String, path: Option[String] = None) derives ReadWriter
  final private case class GrepArgs(pattern: String, path: Option[String] = None, include: Option[String] = None)
      derives ReadWriter

  private val readOnly: ToolHints =
    ToolHints(readOnly = true, destructive = false, idempotent = true, openWorld = false)
  private val writing: ToolHints = ToolHints(readOnly = false, destructive = true, idempotent = true, openWorld = false)
  private val editing: ToolHints = writing.withIdempotent(false)

  private val SnapshotNote =
    "A read in the same message as a write may not see that write: to read what you wrote, read it in a later message."

  private def pathOf(s: String): Result[VPath] =
    VPath(s).left.map(e => ValidationError("path", s"${e.message}; use an absolute path such as /src/main.scala"))

  private def pathOr(s: Option[String]): Result[VPath] = s.fold[Result[VPath]](Right(VPath.root))(pathOf)

  /** The pattern `rest` beneath `path`: `/rest` at the root, never `//rest`. */
  private def beneath(path: VPath, rest: String): String =
    if path == VPath.root then s"/$rest" else s"${path.value}/$rest"

  /**
   * What a search from `root` may read: the envelope beneath it, and `root` itself, which a backend searches when
   * it is a file (a path is within itself). The root is declared whatever it is, since what it is can change
   * between the permission check and the read.
   */
  private def searched(root: VPath, envelope: String): Vector[ToolResource] =
    Vector(ToolResource(Operation.Read, root.value), ToolResource(Operation.Read, envelope))

  private def nullable[A](schema: org.llm4s.toolapi.SchemaDefinition[A]) = Schema.nullable(schema)

  /** Unit, or a `ValidationError` listing `problems`. */
  private def checked(problems: Seq[Option[String]]): Result[Unit] =
    problems.flatten match
      case Seq() => Right(())
      case found => Left(ValidationError("arguments", found.toList))

  final private class Suite(backend: FileBackend, limits: FileToolLimits):
    import limits.*

    /** A mutation of `path` locks what the backend says; an unparseable path (refused before scheduling) the root. */
    private def locksOf(path: String): Set[LockKey] = VPath(path).fold(_ => Set(LockKey("")), backend.locks)

    /** A tool's text result, cut at `maxResultChars`. */
    private def text(s: String): ToolOutcome =
      ToolOutcome.Success(ToolContent.Json(ujson.Str(Bounded.cap(s, maxResultChars))))

    /** A backend error: a refusal the model sees, cut at `maxResultChars`; anything else is `Fatal`. */
    private def failed(error: LLMError): ToolOutcome =
      BackendRefused.toolOutcome(error) match
        case ToolOutcome.Error(message) => ToolOutcome.Error(Bounded.error(message, maxResultChars))
        case other                      => other

    private def argError(error: LLMError): ToolOutcome = ToolOutcome.Error(Bounded.error(error.message, maxResultChars))

    private def mutated[A](result: Result[(A, StateUpdate)])(render: A => String): ToolOutcome =
      result.fold(
        failed,
        (a, update) => ToolOutcome.Success(ToolContent.Json(ujson.Str(Bounded.cap(render(a), maxResultChars))), update)
      )

    private def stopped(listing: Listing[?]): Option[String] =
      Option.when(listing.truncated)(s"(stopped at $maxResults results; narrow the path or pattern)")

    // ---- ls ----

    private val ls: AgentTool[LsArgs] = AgentTool(
      AgentToolSpec[LsArgs](
        Ls,
        "List the files and directories directly inside a directory. Directories end with /; files show their " +
          s"size. At most $maxResults entries.",
        Schema
          .`object`[LsArgs]("ls arguments")
          .withOptionalField("path", nullable(Schema.string("The absolute directory to list; / when null")))
      ).withHints(readOnly)
        .withValidation(a => pathOr(a.path).map(_ => ()))
        .withResources(a => pathOr(a.path).toOption.map(p => ToolResource(Operation.Read, p.value)).toVector)
    ) { (args, context) =>
      pathOr(args.path).fold(
        argError,
        path =>
          backend
            .ls(path, maxResults, FileContext(context))
            .fold(
              failed,
              listing =>
                if listing.items.isEmpty then text(s"${path.value} is empty")
                else
                  text(
                    Bounded.render(
                      listing.items.iterator
                        .map(i => if i.isDirectory then s"${i.path.value}/" else s"${i.path.value} (${i.size} bytes)"),
                      stopped(listing),
                      maxResultChars
                    )
                  )
            )
      )
    }

    // ---- read_file ----

    private val readFile: AgentTool[ReadArgs] = AgentTool(
      AgentToolSpec[ReadArgs](
        ReadFile,
        "Read a text file. Lines are numbered from 1; `offset` is the line to start at (default 1) and `limit` the " +
          s"most lines to read (default and at most $readLimit). Lines longer than $maxLineChars characters are cut. " +
          SnapshotNote,
        Schema
          .`object`[ReadArgs]("read_file arguments")
          .withRequiredField("path", Schema.string("The absolute path of the file"))
          .withOptionalField(
            "offset",
            nullable(Schema.integer("The 1-based line to start at; 1 when null").withRange(min = Some(1)))
          )
          .withOptionalField(
            "limit",
            nullable(Schema.integer(s"The most lines to read; $readLimit when null").withRange(min = Some(1)))
          )
      ).withHints(readOnly)
        .withValidation(a =>
          pathOf(a.path).flatMap(_ =>
            checked(
              Seq(
                a.offset.filter(_ < 1).map(o => s"offset must be at least 1, was $o"),
                a.limit.filter(_ < 1).map(l => s"limit must be at least 1, was $l")
              )
            )
          )
        )
        .withResources(a => pathOf(a.path).toOption.map(p => ToolResource(Operation.Read, p.value)).toVector)
    ) { (args, context) =>
      pathOf(args.path).fold(
        argError,
        path =>
          val offset = args.offset.getOrElse(1)
          val limit  = math.min(args.limit.getOrElse(readLimit), readLimit)
          backend.read(path, offset - 1, limit, FileContext(context)).fold(failed, lines => readResult(path, lines))
      )
    }

    private def readResult(path: VPath, lines: FileLines): ToolOutcome =
      if lines.totalLines == 0 then text(s"(${path.value} is an empty file)")
      else if lines.lines.isEmpty then
        ToolOutcome.Error(
          Bounded.error(
            s"offset ${lines.offset + 1} is past the end of ${path.value}, which has ${lines.totalLines} lines",
            maxResultChars
          )
        )
      else
        val first = lines.offset + 1
        val last  = lines.offset + lines.lines.size
        val more =
          Option.when(last < lines.totalLines)(
            s"(lines $first-$last of ${lines.totalLines}; continue with offset=${last + 1})"
          )
        text(
          Bounded.render(
            lines.lines.iterator.zipWithIndex.map((line, i) => f"${first + i}%6d\t${Bounded.line(line, maxLineChars)}"),
            more,
            maxResultChars
          )
        )

    // ---- write_file ----

    private val writeFile: AgentTool[WriteArgs] = AgentTool(
      AgentToolSpec[WriteArgs](
        WriteFile,
        "Create a text file, or replace a file's whole content. Parent directories are implicit. " + SnapshotNote,
        Schema
          .`object`[WriteArgs]("write_file arguments")
          .withRequiredField("path", Schema.string("The absolute path of the file"))
          .withRequiredField("content", Schema.string("The file's new content"))
      ).withHints(writing)
        .withValidation(a => pathOf(a.path).map(_ => ()))
        .withResources(a => pathOf(a.path).toOption.map(p => ToolResource(Operation.Write, p.value)).toVector)
        .withLocks(a => locksOf(a.path)),
      writes = backend.writes
    ) { (args, context) =>
      pathOf(args.path).fold(
        argError,
        path =>
          mutated(backend.write(path, args.content, FileContext(context))) { w =>
            s"${if w.created then "Created" else "Overwrote"} ${path.value} (${w.bytes} bytes)"
          }
      )
    }

    // ---- edit_file ----

    private val editFile: AgentTool[EditArgs] = AgentTool(
      AgentToolSpec[EditArgs](
        EditFile,
        "Replace exact text in a file. `old_string` must occur exactly once, unless `replace_all` is true, which " +
          "replaces every occurrence. Edits of one file in one message apply in order, each to the result of the " +
          "one before. " + SnapshotNote,
        Schema
          .`object`[EditArgs]("edit_file arguments")
          .withRequiredField("path", Schema.string("The absolute path of the file"))
          .withRequiredField("old_string", Schema.string("The exact text to replace"))
          .withRequiredField("new_string", Schema.string("The text to put in its place"))
          .withOptionalField(
            "replace_all",
            nullable(Schema.boolean("Replace every occurrence; false when null"))
          )
      ).withHints(editing)
        .withValidation(a => pathOf(a.path).map(_ => ()))
        .withResources(a => pathOf(a.path).toOption.map(p => ToolResource(Operation.Write, p.value)).toVector)
        .withLocks(a => locksOf(a.path)),
      writes = backend.writes
    ) { (args, context) =>
      pathOf(args.path).fold(
        argError,
        path =>
          mutated(
            backend
              .edit(path, args.old_string, args.new_string, args.replace_all.getOrElse(false), FileContext(context))
          ) { e =>
            s"Edited ${path.value}: ${e.replacements} ${if e.replacements == 1 then "replacement" else "replacements"}"
          }
      )
    }

    // ---- delete ----

    private val delete: AgentTool[DeleteArgs] = AgentTool(
      AgentToolSpec[DeleteArgs](
        Delete,
        "Delete a file. A directory goes when its last file does.",
        Schema
          .`object`[DeleteArgs]("delete arguments")
          .withRequiredField("path", Schema.string("The absolute path of the file"))
      ).withHints(writing)
        .withValidation(a => pathOf(a.path).map(_ => ()))
        .withResources(a => pathOf(a.path).toOption.map(p => ToolResource(Operation.Delete, p.value)).toVector)
        .withLocks(a => locksOf(a.path)),
      writes = backend.writes
    ) { (args, context) =>
      pathOf(args.path).fold(
        argError,
        path =>
          mutated(backend.delete(path, FileContext(context))) { d =>
            s"Deleted ${if d.wasDirectory then "directory " else ""}${path.value}"
          }
      )
    }

    // ---- glob ----

    private val glob: AgentTool[GlobArgs] = AgentTool(
      AgentToolSpec[GlobArgs](
        Glob,
        "Find files by a glob matched against their path relative to `path`: * matches within one directory " +
          "level, ? one character, ** any number of levels (**/*.scala). " +
          s"At most $maxResults paths, in path order.",
        Schema
          .`object`[GlobArgs]("glob arguments")
          .withRequiredField("pattern", Schema.string("The glob, relative to path"))
          .withOptionalField("path", nullable(Schema.string("The absolute directory to search; / when null")))
      ).withHints(readOnly)
        // the pattern bounded before the resource is built from it, so a permission rule never compares an oversized glob
        .withValidation(a => Text.checkPattern(Glob, a.pattern).flatMap(_ => pathOr(a.path)).map(_ => ()))
        .withResources(a => pathOr(a.path).toOption.toVector.flatMap(p => searched(p, beneath(p, a.pattern))))
    ) { (args, context) =>
      pathOr(args.path).fold(
        argError,
        under =>
          backend
            .glob(org.llm4s.agent.graph.tool.Glob(args.pattern), under, maxResults, FileContext(context))
            .fold(
              failed,
              listing =>
                if listing.items.isEmpty then text(s"No files match ${Text.quoted(args.pattern)} under ${under.value}")
                else text(Bounded.render(listing.items.iterator.map(_.value), stopped(listing), maxResultChars))
            )
      )
    }

    // ---- grep ----

    private def grepEnvelope(a: GrepArgs, under: VPath): String = a.include match
      case None                                    => beneath(under, "**")
      case Some(include) if !include.contains('/') => beneath(under, s"**/$include")
      case Some(include)                           => beneath(under, include)

    private val grep: AgentTool[GrepArgs] = AgentTool(
      AgentToolSpec[GrepArgs](
        Grep,
        "Search the files under `path` for lines matching a regular expression (Java syntax). `include` limits " +
          "the files searched: a glob such as *.scala matches a file's name, one with a / its path relative to " +
          s"`path`. At most $maxResults matching lines, as path:line: text.",
        Schema
          .`object`[GrepArgs]("grep arguments")
          .withRequiredField("pattern", Schema.string("The regular expression"))
          .withOptionalField("path", nullable(Schema.string("The absolute directory to search; / when null")))
          .withOptionalField("include", nullable(Schema.string("A glob limiting the files searched; all when null")))
      ).withHints(readOnly)
        .withValidation(a =>
          for
            _ <- Text.checkPattern(Grep, a.pattern)
            _ <- a.include.fold[Result[Unit]](Right(()))(Text.checkPattern(Grep, _))
            _ <- pathOr(a.path)
          yield ()
        )
        .withResources(a => pathOr(a.path).toOption.toVector.flatMap(p => searched(p, grepEnvelope(a, p))))
    ) { (args, context) =>
      pathOr(args.path).fold(
        argError,
        under =>
          backend
            .grep(
              args.pattern,
              under,
              args.include.map(org.llm4s.agent.graph.tool.Glob(_)),
              maxResults,
              FileContext(context)
            )
            .fold(
              failed,
              listing =>
                if listing.items.isEmpty then text(s"No lines match ${Text.quoted(args.pattern)} under ${under.value}")
                else
                  text(
                    Bounded.render(
                      listing.items.iterator
                        .map(m => s"${m.path.value}:${m.line}: ${Bounded.line(m.text, maxLineChars)}"),
                      stopped(listing),
                      maxResultChars
                    )
                  )
            )
      )
    }

    val all: Vector[AgentTool[?]] = Vector(ls, readFile, writeFile, editFile, delete, glob, grep)

/** Text assembled within a bound: lines added until the next would pass it, then cut and marked (rule 12). */
private[harness] object Bounded:

  /** The first `n` characters of `s`, never splitting a surrogate pair. */
  private def prefix(s: String, n: Int): String =
    if s.length <= n then s
    else if n <= 0 then ""
    else if Character.isHighSurrogate(s.charAt(n - 1)) then s.substring(0, n - 1)
    else s.substring(0, n)

  /** `s` cut to `max` characters, marked when cut. */
  def line(s: String, max: Int): String =
    if s.length <= max then s else s"${prefix(s, max)} [... line cut at $max characters]"

  private def cutNote(max: Int): String = s"[output cut at $max characters]"

  /** `s` whole if it is at most `max` characters, else cut to fit with its note. */
  def cap(s: String, max: Int): String =
    if s.length <= max then s
    else
      val note = cutNote(max)
      s"${prefix(s, max - note.length - 1)}\n$note"

  /** The characters `c` takes inside a JSON string as ujson renders it; a lone surrogate counted high. */
  private def escapedWidth(c: Char): Int =
    if c == '"' || c == '\\' || c == '\n' || c == '\r' || c == '\t' || c == '\b' || c == '\f' then 2
    else if c < 0x20 || Character.isSurrogate(c) then 6
    else 1

  /** The characters `s` takes inside a JSON string, counted only until it passes `max`. */
  private def escapedLength(s: String, max: Int): Int =
    var total = 0
    var i     = 0
    while i < s.length && total <= max do
      total += escapedWidth(s.charAt(i))
      i += 1
    total

  /** The longest prefix of `s` that takes at most `max` characters escaped, never splitting a surrogate pair. */
  private def escapedPrefix(s: String, max: Int): String =
    var total = 0
    var i     = 0
    // a surrogate pair is kept whole: both halves fit, or neither
    def width(at: Int): (Int, Int) =
      if Character.isHighSurrogate(s.charAt(at)) && at + 1 < s.length && Character.isLowSurrogate(s.charAt(at + 1))
      then (2, 2)
      else (escapedWidth(s.charAt(at)), 1)
    var done = false
    while !done && i < s.length do
      val (w, n) = width(i)
      if total + w > max then done = true
      else
        total += w
        i += n
    s.substring(0, i)

  /** What `{"error":""}` adds around an error's message when the loop renders it for the model. */
  private val ErrorEnvelope: Int = ujson.Obj("error" -> "").render().length

  /**
   * An error's message cut so that the loop's rendering of it, `{"error": <message>}` with JSON escapes, is at
   * most `max` characters: quotes, backslashes and control characters take more than one escaped.
   */
  def error(message: String, max: Int): String =
    val budget = max - ErrorEnvelope
    if escapedLength(message, budget) <= budget then message
    else
      val note = s"\n${cutNote(max)}"
      s"${escapedPrefix(message, budget - escapedLength(note, budget))}$note"

  /**
   * `items` one per line, then `trailer`, in at most `max` characters: room for the trailer and the cut note is
   * kept back, and once an item would not fit, what fits of it is kept, the note added, and no further item read.
   */
  def render(items: Iterator[String], trailer: Option[String], max: Int): String =
    val note   = cutNote(max)
    val budget = max - trailer.fold(0)(_.length + 1) - note.length - 1
    val out    = new java.lang.StringBuilder()
    var cut    = false
    while !cut && items.hasNext do
      val item = items.next()
      val sep  = if out.length == 0 then 0 else 1
      val room = budget - out.length - sep
      if item.length <= room then
        if sep == 1 then out.append('\n')
        out.append(item): Unit
      else
        if room > 0 then
          if sep == 1 then out.append('\n')
          out.append(prefix(item, room)): Unit
        cut = true
    trailer.foreach(t => out.append('\n').append(t))
    if cut then out.append('\n').append(note)
    out.toString
