package org.llm4s.testutil

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * Ties a spec that runs a guide's code to the guide itself, so the two cannot drift (#2833).
 *
 * The spec marks each piece of the guide's code it runs with a `// snippet: <name>` line before it and an
 * `// end snippet` line after it. [[check]] then reads the spec's source and the page and reports
 *
 *   - a region that is not, line for line, part of one of the page's Scala blocks (the spec runs code the page does
 *     not show, or the page changed);
 *   - a Scala block of the page that no region is part of and that is not listed in `notRun` (the page shows code that
 *     nothing runs). `notRun` names such a block by its first line, for a block that cannot run in a test, such as an
 *     sbt dependency line. A spec that runs only some sections of a page whose blocks `DocSnippets` compiles passes
 *     `everyBlock = false` instead.
 *
 * A region may be a run of whole lines of a block, not all of it, so a spec can leave out an import it already has or
 * the line that would call the network. Lines compare with their whitespace collapsed and blank lines left out, so
 * indentation and scalafmt's alignment do not count; a line break does, so a region that scalafmt would wrap
 * differently from the page sits between `// format: off` and `// format: on`.
 *
 * Paths are relative to the repository root (the directory with `build.sbt`), and are given by the spec, so the
 * literal `docs/...` path is in the spec: `scripts/check-docs-tests.sh` then requires it to be tagged `DocsSpec`.
 */
object GuideSnippets {

  /** The repository root: the first directory above the working directory that has a `build.sbt`. */
  lazy val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  /** The file at `relative` (to the repository root), with Windows line endings made `\n`. */
  def read(relative: String): String =
    new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8).replace("\r\n", "\n")

  /** The non-blank lines of a block, each with its whitespace collapsed. */
  def normalise(block: String): List[String] =
    block.split("\n", -1).toList.map(_.trim.replaceAll("\\s+", " ")).filter(_.nonEmpty)

  /** The fenced `scala` blocks of a page, normalised. */
  def scalaBlocks(page: String): List[List[String]] =
    """(?ms)^```scala[^\n]*\n(.*?)^```[ \t]*$""".r.findAllMatchIn(page).map(m => normalise(m.group(1))).toList

  /** The `// snippet: <name>` regions of a source file, by name, normalised. */
  def regions(source: String): List[(String, List[String])] =
    """(?ms)^[ \t]*// snippet: ([A-Za-z0-9-]+)[ \t]*\n(.*?)^[ \t]*// end snippet[ \t]*$""".r
      .findAllMatchIn(source)
      .map(m => (m.group(1), normalise(m.group(2))))
      .toList

  /** Whether `part` is a run of consecutive lines of `block`. */
  private def isPartOf(part: List[String], block: List[String]): Boolean =
    part.nonEmpty && block.sliding(part.length).contains(part)

  /**
   * The problems between `pagePath` and the regions of `specPaths` (one spec, or the specs that share a page), empty
   * when they agree. `notRun` lists, by first line, the page's blocks that no spec runs, each for a reason the caller
   * documents; with `everyBlock = false` only the regions are checked.
   */
  def check(
    pagePath: String,
    specPaths: Seq[String],
    notRun: Set[String] = Set.empty,
    everyBlock: Boolean = true
  ): List[String] =
    problems(pagePath, read(pagePath), specPaths.map(path => path -> read(path)), notRun, everyBlock)

  /** [[check]] on texts already read: `specs` are (name, source) pairs. */
  def problems(
    pageName: String,
    page: String,
    specs: Seq[(String, String)],
    notRun: Set[String],
    everyBlock: Boolean
  ): List[String] = {
    val blocks    = scalaBlocks(page)
    val regs      = specs.flatMap { case (name, source) => regions(source).map { case (r, lines) => (name, r, lines) } }
    val specNames = specs.map(_._1).mkString(", ")
    val exempt    = notRun.map(first => normalise(first).headOption.getOrElse(""))
    val noRegions = if (regs.isEmpty) List(s"$specNames marks no `// snippet:` region") else Nil
    val stray = regs.toList.collect {
      case (spec, name, lines) if !blocks.exists(isPartOf(lines, _)) =>
        s"snippet '$name' of $spec is not part of any Scala block of $pageName:\n${lines.mkString("\n")}"
    }
    def first(block: List[String]): String = block.headOption.getOrElse("")
    val unrun =
      if (!everyBlock) Nil
      else
        blocks.collect {
          case block if !regs.exists { case (_, _, lines) => isPartOf(lines, block) } && !exempt(first(block)) =>
            s"$pageName has a Scala block no snippet of $specNames runs, starting: ${first(block)}"
        }
    val staleNotRun = exempt.toList.sorted.collect {
      case line if !blocks.exists(block => first(block) == line) =>
        s"notRun lists '$line', the first line of no Scala block of $pageName"
    }
    noRegions ++ stray ++ unrun ++ staleNotRun
  }
}
