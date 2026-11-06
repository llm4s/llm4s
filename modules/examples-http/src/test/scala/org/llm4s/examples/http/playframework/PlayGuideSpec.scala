package org.llm4s.examples.http.playframework

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.io.Source
import scala.util.Using

/**
 * Keeps the Play section of `docs/guide/http-reference-apps.md` true: its snippets are excerpts of the app, so every
 * line of a `scala` block must be a line of the app's sources, and every line of a `hocon` block a line of its
 * `application.conf`. The sources are compiled and tested by this module's other specs.
 */
class PlayGuideSpec extends AnyFlatSpec with Matchers {

  private val GuidePath   = "docs/guide/http-reference-apps.md"
  private val SourcesPath = "modules/examples-http/src/main/scala/org/llm4s/examples/http/playframework"
  private val ConfigPath  = "modules/examples-http/src/main/resources/application.conf"

  /** The build root: sbt runs tests from it or from the module, so walk up until the guide is there. */
  private lazy val root: File = {
    val start = new File(".").getAbsoluteFile
    Iterator
      .iterate(Option(start))(_.flatMap(f => Option(f.getParentFile)))
      .takeWhile(_.isDefined)
      .flatten
      .find(dir => new File(dir, GuidePath).isFile)
      .getOrElse(fail(s"cannot find $GuidePath above ${start.getPath}"))
  }

  private def read(file: File): String = Using.resource(Source.fromFile(file, "UTF-8"))(_.mkString)

  private def trimmedLines(text: String): Set[String] = text.linesIterator.map(_.trim).filter(_.nonEmpty).toSet

  /** The guide's Play section: from its heading to the next `## ` heading. */
  private lazy val section: String = {
    val guide = read(new File(root, GuidePath))
    val start = guide.indexOf("## The Play app")
    start should be >= 0
    val end = guide.indexOf("\n## ", start + 1)
    guide.substring(start, if (end < 0) guide.length else end)
  }

  /** The blocks of `language` in the section. */
  private def blocks(language: String): Seq[String] =
    s"(?s)```$language\n(.*?)```".r.findAllMatchIn(section).map(_.group(1)).toSeq

  "The Play section of the guide" should "show only lines of the app's sources in its Scala snippets" in {
    val sources = new File(root, SourcesPath).listFiles().toSeq.filter(_.getName.endsWith(".scala"))
    sources should not be empty
    val known    = sources.flatMap(f => trimmedLines(read(f))).toSet
    val snippets = blocks("scala")
    snippets.size should be >= 5
    snippets.flatMap(trimmedLines).filterNot(known.contains) shouldBe empty
  }

  it should "show only lines of the app's application.conf in its configuration snippet" in {
    val known    = trimmedLines(read(new File(root, ConfigPath)))
    val snippets = blocks("hocon")
    snippets should have size 1
    snippets.flatMap(trimmedLines).filterNot(known.contains) shouldBe empty
  }

  it should "state the Play and Pekko versions the app runs on" in {
    section should include(s"Play ${play.core.PlayVersion.current}")
    section should include(s"Pekko ${org.apache.pekko.Version.current}")
  }
}
