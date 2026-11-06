package org.llm4s.schema.iron.guide

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ ToolCallError, ToolFunction }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }

/**
 * The code of the "Iron refinements" section of `docs/guide/derived-schemas.md`, compiled and run as written.
 *
 * Every `scala` block of the section must appear in this file word for word (the first spec below checks it,
 * ignoring whitespace), its `json` block must be the properties of the schema that is derived, and its `sbt` block
 * must name this module's artifact. `DerivedSchemasGuideSpec` in llm4s-schema-derivation checks the rest of the page.
 */
class IronGuideSpec extends AnyFlatSpec with Matchers {

  object Refinements {
    import io.github.iltotore.iron.*
    import io.github.iltotore.iron.constraint.all.*
    import org.llm4s.schema.*
    import org.llm4s.schema.iron.given
    import upickle.default.ReadWriter

    @description("A product review")
    case class Review(
      @description("Short headline") title: String :| (Not[Blank] & MaxLength[80]),
      @description("Stars") rating: Int :| Interval.Closed[1, 5],
      @description("One to ten topics") tags: List[String] :| (MinLength[1] & MaxLength[10])
    ) derives SchemaOf,
          ReadWriter

    def review(client: LLMClient, conversation: Conversation): Result[Review] =
      client.completeStructuredOf[Review](conversation)

    case class Booking(
      @description("Guest name") guest: String :| Not[Blank],
      @description("Nights to stay") nights: Int :| Interval.Closed[1, 14]
    ) derives SchemaOf,
          ReadWriter

    val book: Result[ToolFunction[Booking, String]] =
      Tool.of[Booking, String]("book_stay", "Books a stay")(b => Right(s"Booked ${b.nights} nights for ${b.guest}"))

    def schema: ujson.Value = SchemaOf[Review].toJsonSchema()
  }

  // ---- The section, read from the repository

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("docs/guide/derived-schemas.md")))
      .getOrElse(fail("docs/guide/derived-schemas.md was not found in this directory or above it"))

  private val section = {
    val full  = Files.readString(root.resolve("docs/guide/derived-schemas.md"))
    val start = full.indexOf("\n## Iron refinements\n")
    val end   = full.indexOf("\n## ", start + 1)
    if (start < 0 || end < 0) fail("docs/guide/derived-schemas.md has no \"Iron refinements\" section before another")
    full.substring(start, end)
  }

  private val thisSource =
    Files.readString(
      root.resolve("modules/schema-iron/src/test/scala/org/llm4s/schema/iron/guide/IronGuideSpec.scala")
    )

  private def blocks(language: String): List[String] =
    ("(?s)```" + language + "\n(.*?)```").r.findAllMatchIn(section).map(_.group(1)).toList

  private def squash(text: String): String = text.replaceAll("\\s+", " ").trim

  private val conversation = Conversation(Seq(UserMessage("Review the kettle.")))

  "The section's Scala blocks" should "all appear in this spec word for word" in {
    val scalaBlocks = blocks("scala")
    scalaBlocks should have size 2
    scalaBlocks.foreach(block =>
      withClue(s"This block of the guide is not in the code the spec compiles:\n$block\n") {
        squash(thisSource) should include(squash(block))
      }
    )
  }

  it should "be matched by an sbt block that names the artifact the build publishes" in {
    blocks("sbt").map(squash) shouldBe List(
      """libraryDependencies += "org.llm4s" %% "llm4s-schema-iron" % "{{ site.data.project.latest_release }}""""
    )
  }

  "The section's schema" should "be the properties derived for Review" in {
    val printed = blocks("json")
    printed should have size 1
    ujson.write(Refinements.schema("properties")) shouldBe ujson.write(ujson.read(printed.head))
  }

  "The review of the section" should "decode a reply in bounds and refuse one out of bounds, naming the bound" in {
    val ok = ScriptedLLMClient.always(Reply.text("""{"title":"Good","rating":5,"tags":["kettle"]}"""))
    Refinements.review(ok, conversation).map(_.rating: Int) shouldBe Right(5)
    val tooHigh = ScriptedLLMClient.always(Reply.text("""{"title":"Good","rating":6,"tags":["kettle"]}"""))
    Refinements.review(tooHigh, conversation).left.map(_.message) match {
      case Left(message) => message should include("$['rating']: Should be included in [1, 5]")
      case other         => fail(s"expected a Left, got $other")
    }
    section should include("`$['rating']: Should be included in [1, 5]`")
  }

  "The tool of the section" should "run in bounds and report the field out of bounds" in {
    val tool = Refinements.book.fold(e => fail(e.message), identity)
    tool.execute(ujson.Obj("guest" -> "Ada", "nights" -> 2)) shouldBe Right(ujson.Str("Booked 2 nights for Ada"))
    tool.execute(ujson.Obj("guest" -> "Ada", "nights" -> 15)) match {
      case Left(ToolCallError.HandlerError("book_stay", message)) => message should include("nights")
      case other                                                  => fail(s"expected the tool's error, got $other")
    }
  }
}
