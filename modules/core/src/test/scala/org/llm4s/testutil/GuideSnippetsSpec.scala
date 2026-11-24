package org.llm4s.testutil

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GuideSnippetsSpec extends AnyFlatSpec with Matchers {

  private val fence = "```"

  private val page =
    s"""Some prose.
       |
       |${fence}scala
       |import a.B
       |
       |val x = for {
       |  y <- f()
       |  z <- g(y)
       |} yield z
       |$fence
       |
       |${fence}scala
       |libraryDependencies += "x" %% "y" % v
       |$fence
       |""".stripMargin

  private def spec(body: String): Seq[(String, String)] = Seq("Spec.scala" -> body)

  private def problems(body: String, notRun: Set[String] = Set.empty, everyBlock: Boolean = true) =
    GuideSnippets.problems("page.md", page, spec(body), notRun, everyBlock)

  private val region =
    """  def x() =
      |    for {
      |      // snippet: for
      |      y      <- f()
      |      z      <- g(y)
      |    } yield z
      |    // end snippet
      |""".stripMargin

  "GuideSnippets" should "accept a region that is a run of a block's lines, whatever the indentation and alignment" in {
    problems(region, notRun = Set("libraryDependencies += \"x\" %% \"y\" % v")) shouldBe empty
  }

  it should "report a region the page does not show" in {
    val drifted = region.replace("g(y)", "h(y)")
    val found   = problems(drifted, notRun = Set("libraryDependencies += \"x\" %% \"y\" % v"))
    found.exists(_.contains("snippet 'for' of Spec.scala is not part of any Scala block")) shouldBe true
  }

  it should "report a block no region runs, unless notRun names it or everyBlock is off" in {
    problems(region).exists(_.contains("starting: libraryDependencies")) shouldBe true
    problems(region, everyBlock = false) shouldBe empty
  }

  it should "report a notRun entry that names no block, and a spec with no region" in {
    problems(region, notRun = Set("libraryDependencies += \"x\" %% \"y\" % v", "gone")) shouldBe
      List("notRun lists 'gone', the first line of no Scala block of page.md")
    problems("no regions here", everyBlock = false) shouldBe List("Spec.scala marks no `// snippet:` region")
  }
}
