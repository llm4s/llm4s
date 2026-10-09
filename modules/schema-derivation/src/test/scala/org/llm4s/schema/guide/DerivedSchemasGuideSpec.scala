package org.llm4s.schema.guide

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }

/**
 * The code of `docs/guide/derived-schemas.md`, compiled and run as written.
 *
 * Every `scala` block of the guide must appear in this file word for word (the first spec below checks it, ignoring
 * whitespace), so a guide that teaches something that no longer compiles fails here. Its `json` block must be the
 * schema that is derived. Change the guide and this spec together.
 */
class DerivedSchemasGuideSpec extends AnyFlatSpec with Matchers {

  // ---- A first call

  object Quickstart {
    import org.llm4s.schema.*
    import upickle.default.ReadWriter

    @description("An invoice extracted from text")
    case class Invoice(
      @description("Name of the vendor or supplier") vendor: String,
      @description("Total invoice amount as a decimal number") amount: Double,
      @description("ISO 4217 currency code, e.g. USD, EUR, GBP") currency: String
    ) derives SchemaOf,
          ReadWriter

    def extract(client: LLMClient, conversation: Conversation): Result[Invoice] = {
      val invoice: Result[Invoice] = client.completeStructuredOf[Invoice](conversation)
      invoice
    }

    def schema(): ujson.Value = {
      val json = SchemaOf[Invoice].toJsonSchema()
      json
    }
  }

  // ---- What is derived

  object Models {
    import org.llm4s.schema.*
    import upickle.default.ReadWriter

    case class Contact(
      name: String,
      email: Option[String],
      tags: List[String] = Nil
    ) derives SchemaOf,
          ReadWriter

    enum Priority derives SchemaOf, ReadWriter {
      case Low, Medium, High
    }
  }

  // ---- Types the module does not know

  object Meetings {
    import org.llm4s.schema.*
    import upickle.default.ReadWriter

    import java.time.Instant

    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap[Instant](_.toString, Instant.parse)
    given SchemaOf[Instant]   = SchemaOf.string("ISO-8601 instant, for example 2026-10-08T09:30:00Z")

    case class Meeting(title: String, starts: Instant) derives SchemaOf, ReadWriter
  }

  // ---- The guide, read from the repository

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("docs/guide/derived-schemas.md")))
      .getOrElse(fail("docs/guide/derived-schemas.md was not found in this directory or above it"))

  private val guide = Files.readString(root.resolve("docs/guide/derived-schemas.md"))
  private val thisSource =
    Files.readString(
      root.resolve("modules/schema-derivation/src/test/scala/org/llm4s/schema/guide/DerivedSchemasGuideSpec.scala")
    )

  private def blocks(language: String): List[String] =
    ("(?s)```" + language + "\n(.*?)```").r.findAllMatchIn(guide).map(_.group(1)).toList

  private def squash(text: String): String = text.replaceAll("\\s+", " ").trim

  private class Scripted(reply: String) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, reply, "test-model", AssistantMessage(reply)))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "The guide's Scala blocks" should "all appear in this spec word for word" in {
    val scalaBlocks = blocks("scala")
    scalaBlocks should have size 5
    scalaBlocks.foreach(block =>
      withClue(s"This block of the guide is not in the code the spec compiles:\n$block\n") {
        squash(thisSource) should include(squash(block))
      }
    )
  }

  it should "be matched by an sbt block that names the artifact the build publishes" in {
    blocks("sbt").map(squash) shouldBe List(
      """libraryDependencies += "org.llm4s" %% "llm4s-schema-derivation" % "{{ site.data.project.latest_release }}""""
    )
  }

  "The first call" should "send the derived schema and read the answer" in {
    import Quickstart.*
    val reply = """{"vendor":"Acme","amount":1250.0,"currency":"GBP"}"""
    extract(new Scripted(reply), Conversation(Seq(UserMessage("Extract the invoice.")))) shouldBe
      Right(Invoice("Acme", 1250.0, "GBP"))
  }

  it should "show the schema the guide prints" in {
    val printed = blocks("json")
    printed should have size 1
    ujson.write(Quickstart.schema()) shouldBe ujson.write(ujson.read(printed.head))
  }

  "The models of the guide" should "describe an Option as nullable and an enum as its case names" in {
    val contact = ujson.read(org.llm4s.schema.SchemaOf[Models.Contact].toJsonSchema().render())
    contact("properties")("email")("type") shouldBe ujson.Arr("string", "null")
    contact("properties")("tags")("type") shouldBe ujson.Str("array")
    org.llm4s.schema.SchemaOf[Models.Priority].toJsonSchema()("enum") shouldBe ujson.Arr("Low", "Medium", "High")
  }

  "A type the module does not know" should "be described by the given the guide defines, and round-trip" in {
    import Meetings.*
    val schema = org.llm4s.schema.SchemaOf[Meeting].toJsonSchema()
    schema("properties")("starts")("type") shouldBe ujson.Str("string")
    schema("properties")("starts")("description").str should startWith("ISO-8601 instant")
    val meeting = Meeting("Standup", java.time.Instant.parse("2026-10-08T09:30:00Z"))
    upickle.default.read[Meeting](upickle.default.write(meeting)) shouldBe meeting
  }
}
