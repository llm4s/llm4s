package org.llm4s.testutil

import org.llm4s.error.TroubleshootingLinks

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * The First-Run Troubleshooting page as the specs of #2285 read it, from the repository, so that a link in an
 * error message and the entry it names are checked against each other. The repository root is found by walking
 * up from the working directory, so the specs run from the build root or from a module directory.
 */
object TroubleshootingPage:

  lazy val root: Path =
    Iterator
      .iterate(Paths.get(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve(TroubleshootingLinks.PageSource)))
      .getOrElse(
        throw new IllegalStateException(
          s"${TroubleshootingLinks.PageSource} was not found above ${System.getProperty("user.dir")}"
        )
      )

  lazy val text: String =
    new String(Files.readAllBytes(root.resolve(TroubleshootingLinks.PageSource)), StandardCharsets.UTF_8)

  private val HeadingAnchor = """(?m)^#{2,6} .*\{#([A-Za-z0-9_-]+)\}\s*$""".r

  /** The explicit `{#id}` anchors of the page's headings. */
  lazy val anchors: Set[String] = HeadingAnchor.findAllMatchIn(text).map(_.group(1)).toSet

  /** The entry's section: from its heading to the next `## ` heading, or the end of the page. */
  def section(entry: TroubleshootingLinks.Entry): String =
    val heading = s"{#${entry.id}}"
    val start   = text.indexOf(heading)
    require(start >= 0, s"${TroubleshootingLinks.PageSource} has no heading anchored $heading")
    val end = text.indexOf("\n## ", start)
    if end < 0 then text.substring(start) else text.substring(start, end)
