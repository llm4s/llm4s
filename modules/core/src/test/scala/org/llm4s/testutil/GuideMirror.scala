package org.llm4s.testutil

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * Keeps a spec that runs a guide's code from drifting away from the guide (#2833).
 *
 * A guide's Scala blocks are compiled by `DocSnippets` (`project/DocSnippets.scala`). A spec that also *runs* a
 * block, to assert what the prose around it claims, needs the block as code of its own, and that copy can drift:
 * the page changes, the copy does not, and the spec goes on passing against code the page no longer shows. So
 * the copied code is marked in the spec's source, between a line `// guide-block` and a line `// end-guide-block`,
 * and [[missingFromPage]] fails each marked region that is not part of a `scala` block of the page as written.
 * Whitespace is ignored (a copy inside a method is indented further), nothing else is: a renamed value, a changed
 * literal or a changed comment in either place is a difference.
 *
 * Paths are relative to the repository root, found by walking up from the working directory, so a spec runs from
 * the build root or from its module's directory.
 */
object GuideMirror:

  private val Begin = "// guide-block"
  private val End   = "// end-guide-block"

  lazy val root: Path =
    Iterator
      .iterate(Paths.get(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.isDirectory(dir.resolve("docs/guide")) && Files.isRegularFile(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException(s"no repository root above ${System.getProperty("user.dir")}"))

  def read(relative: String): String =
    val file = root.resolve(relative)
    require(Files.isRegularFile(file), s"$relative not found under $root")
    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)

  private def normalise(text: String): String = text.replaceAll("\\s+", " ").trim

  /** The fenced `scala` blocks of a page. */
  def scalaBlocks(page: String): Vector[String] =
    val lines = read(page).split("\r?\n", -1).toVector
    val out   = Vector.newBuilder[String]
    var i     = 0
    while i < lines.length do
      if lines(i).startsWith("```") then
        val lang  = lines(i).drop(3).takeWhile(!_.isWhitespace)
        val start = i + 1
        var end   = start
        while end < lines.length && !lines(end).matches("^```\\s*$") do end += 1
        if lang == "scala" then out += lines.slice(start, end).mkString("\n")
        i = end + 1
      else i += 1
    out.result()

  /**
   * The marked regions of a source file, with the line of each `// guide-block`. A file with no region, an
   * unclosed region or an empty one is an error: the spec is then checking nothing.
   */
  def regions(source: String): Vector[(Int, String)] =
    val lines = read(source).split("\r?\n", -1).toVector
    val out   = Vector.newBuilder[(Int, String)]
    var i     = 0
    while i < lines.length do
      if lines(i).trim == Begin then
        val start = i + 1
        var end   = start
        while end < lines.length && lines(end).trim != End do end += 1
        require(end < lines.length, s"$source:${i + 1}: `$Begin` is never closed by `$End`")
        val text = lines.slice(start, end).mkString("\n")
        require(normalise(text).nonEmpty, s"$source:${i + 1}: an empty `$Begin` region")
        out += (i + 1) -> text
        i = end + 1
      else i += 1
    val found = out.result()
    require(found.nonEmpty, s"$source has no `$Begin` region")
    found

  /** The regions of `source` that no `scala` block of `page` contains as written, whitespace aside. */
  def missingFromPage(page: String, source: String): Vector[String] =
    val blocks = scalaBlocks(page).map(normalise)
    regions(source).collect {
      case (line, text) if !blocks.exists(_.contains(normalise(text))) =>
        s"$source:$line is not on $page as written:\n$text"
    }
