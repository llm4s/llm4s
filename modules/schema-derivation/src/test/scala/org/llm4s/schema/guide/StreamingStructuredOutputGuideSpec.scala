package org.llm4s.schema.guide

// The build compiles with -source:3.3, which turns off field access by name on named tuples; Scala 3.7's default
// source level, which the guide's readers have, has it.
import scala.language.`3.7`

import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Paths }

/**
 * The code of section 10 of `docs/guide/structured-output.md`, "Streaming structured output", compiled and run.
 *
 * The rest of that guide is core's `StructuredOutputGuideSpec`; this section needs `llm4s-schema-derivation`, so its
 * blocks are here. Every `scala` block of the section must appear in this file word for word (ignoring whitespace).
 * Change the guide and this spec together.
 */
class StreamingStructuredOutputGuideSpec extends AnyFlatSpec with Matchers {

  object Streaming {
    import org.llm4s.llmconnect.LLMClient
    import org.llm4s.llmconnect.model.Conversation
    import org.llm4s.schema.*
    import org.llm4s.types.Result
    import upickle.default.ReadWriter

    case class LineItem(sku: String, quantity: Int, price: BigDecimal) derives SchemaOf, ReadWriter
    case class Order(vendor: String, currency: String, items: List[LineItem]) derives SchemaOf, ReadWriter

    def extractOrder(client: LLMClient, conversation: Conversation): Result[Order] =
      client.streamStructuredOf[Order](conversation) { p =>
        p.vendor.foreach(vendor => println(s"vendor: $vendor")) // p.vendor: Option[String]
        println(s"rows: ${p.items.getOrElse(Nil).size}")        // p.items: Option[List[(sku: Option[String], ...)]]
      }

    val orders = summon[PartialOf[Order]]

    def render(p: orders.Out): String =
      s"${p.vendor.getOrElse("...")}: ${p.items.getOrElse(Nil).size} rows"
  }

  private val reply =
    """{"vendor": "Acme", "currency": "GBP", "items": [{"sku": "D-1", "quantity": 2, "price": "3.5"}]}"""

  "The streaming example" should "stream the order and return it whole" in {
    import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
    val client = ScriptedLLMClient.always(Reply.text(reply)).withChunkSize(4)
    Streaming.extractOrder(client, Conversation(Seq(UserMessage("Extract the order.")))) shouldBe
      Right(Streaming.Order("Acme", "GBP", List(Streaming.LineItem("D-1", 2, BigDecimal("3.5")))))
  }

  "render" should "show what has arrived" in {
    Streaming.render((vendor = Some("Ac"), currency = None, items = Some(Nil))) shouldBe "Ac: 0 rows"
    Streaming.render((vendor = None, currency = None, items = None)) shouldBe "...: 0 rows"
  }

  // ---- The guide, read from the repository

  private lazy val root = Iterator
    .iterate(Paths.get("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .find(dir => Files.exists(dir.resolve("docs/guide/structured-output.md")))
    .getOrElse(fail("docs/guide/structured-output.md was not found in this directory or above it"))

  private lazy val section = {
    val guide = Files.readString(root.resolve("docs/guide/structured-output.md"))
    val start = guide.indexOf("## 10. Streaming structured output")
    guide.substring(start, guide.indexOf("\n## ", start + 1))
  }

  private def squash(text: String): String = text.replaceAll("\\s+", " ").trim

  "Section 10's Scala blocks" should "all appear in this spec word for word" in {
    val blocks = "(?s)```scala\n(.*?)```".r.findAllMatchIn(section).map(_.group(1)).toList
    blocks should have size 2
    val spec = squash(
      Files.readString(
        root.resolve(
          "modules/schema-derivation/src/test/scala/org/llm4s/schema/guide/StreamingStructuredOutputGuideSpec.scala"
        )
      )
    )
    blocks.foreach { block =>
      val lines = block.linesIterator.filterNot(_.trim.isEmpty).toList
      withClue(s"guide block not found in this spec:\n$block\n")(spec should include(squash(lines.mkString("\n"))))
    }
  }
}
