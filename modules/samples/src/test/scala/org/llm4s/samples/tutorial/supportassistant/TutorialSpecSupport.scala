package org.llm4s.samples.tutorial.supportassistant

import org.scalatest.Assertions

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.util.{ Failure, Success, Try }

/**
 * What every part's spec shares: comparing a page's Scala blocks with the `// snippet:` regions of the part's source,
 * and running an exercise check against the reader's code, which is pending while that code is still a `???`.
 */
trait TutorialSpecSupport { self: Assertions =>

  lazy val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  def read(relative: String): String =
    new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8).replace("\r\n", "\n")

  /** Removes the common indentation and the blank lines around a block. */
  def normalise(block: String): String = {
    val lines  = block.split("\n", -1).toList.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    val indent = lines.filter(_.trim.nonEmpty).map(l => l.length - l.stripLeading().length).minOption.getOrElse(0)
    lines.map(l => if (l.length >= indent) l.substring(indent) else l.trim).mkString("\n")
  }

  /** The `// snippet:` regions of a source file under `modules/samples/src/main/scala/.../supportassistant/`. */
  def regions(sourceFile: String): List[String] =
    """(?ms)^[ ]*// snippet: ([a-z-]+)\n(.*?)^[ ]*// end snippet$""".r
      .findAllMatchIn(
        read(s"modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/$sourceFile")
      )
      .map(m => normalise(m.group(2)))
      .toList

  /** The fenced Scala blocks of a page under `docs/tutorials/support-assistant/`. */
  def pageBlocks(page: String): List[String] =
    """(?ms)^```scala\n(.*?)^```$""".r
      .findAllMatchIn(read(s"docs/tutorials/support-assistant/$page"))
      .map(m => normalise(m.group(1)))
      .toList

  /** Runs `check` on the reader's exercise; pending while the exercise is still the `???` it ships with. */
  def yourExercise(check: => Unit): Unit =
    Try(check) match {
      case Failure(_: NotImplementedError) => self.pending
      case Failure(other)                  => throw other
      case Success(())                     => ()
    }
}
