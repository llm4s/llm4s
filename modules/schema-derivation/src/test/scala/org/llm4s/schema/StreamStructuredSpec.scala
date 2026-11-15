package org.llm4s.schema

// The build compiles with -source:3.3, which turns off field access by name on named tuples; Scala 3.7's default
// source level, which users have, has it.
import scala.language.`3.7`

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.schema.StreamFixtures.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Instant
import scala.collection.mutable.ListBuffer

/** `streamStructuredOf` against a scripted model streaming its reply in chunks of every size. */
class StreamStructuredSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  private val conversation = Conversation(Seq(UserMessage("Extract the invoice.")))
  private val invoices     = summon[PartialOf[Invoice]]

  private def stream(reply: String, chunkSize: Int): (Result[Invoice], List[invoices.Out], ScriptedLLMClient) = {
    val client = ScriptedLLMClient.always(Reply.text(reply)).withChunkSize(chunkSize)
    val seen   = ListBuffer.empty[invoices.Out]
    val result = client.streamStructuredOf[Invoice](conversation)(seen += _)
    (result, seen.toList, client)
  }

  /**
   * Whether `next` extends `previous`: a present value stays present, a string grows, a list keeps its elements and
   * may grow its last one, and every other value is unchanged. Named tuples are tuples at run time.
   */
  private def grows(previous: Any, next: Any): Boolean = (previous, next) match {
    case (None, _)              => true
    case (Some(p), Some(n))     => grows(p, n)
    case (p: String, n: String) => n.startsWith(p)
    case (p: Seq[?], n: Seq[?]) =>
      p.size <= n.size && p.indices.forall(i => if (i == p.size - 1) grows(p(i), n(i)) else p(i) == n(i))
    case (p: Tuple, n: Tuple) =>
      p.size == n.size && p.productIterator.zip(n.productIterator).forall { case (a, b) => grows(a, b) }
    case _ => previous == next
  }

  /** Every leaf has the type of its field: a check that a wrong-typed value never reaches the callback. */
  private def welltyped(p: invoices.Out): Boolean = {
    val strings = p.vendor.toList ++ p.currency.toList ++ p.note.flatten.toList ++ p.tags.toList.flatten
    strings.forall(_.isInstanceOf[String]) &&
    p.items.toList.flatten.forall(item =>
      item.sku.forall(_.isInstanceOf[String]) && item.quantity.forall(_.isInstanceOf[Int]) &&
        item.price.forall(_.isInstanceOf[BigDecimal])
    ) &&
    p.status.forall(_.isInstanceOf[Status]) && p.totalCents.forall(_.isInstanceOf[Long]) &&
    p.lines.toList.flatten.forall(_.isInstanceOf[Int])
  }

  "streamStructuredOf" should "return what completeStructuredOf returns for the same reply, at every chunk size" in {
    val expected = ScriptedLLMClient.always(Reply.text(invoiceJson)).completeStructuredOf[Invoice](conversation)
    expected shouldBe Right(invoice)
    (1 to invoiceJson.length + 1).foreach { size =>
      val (result, _, _) = stream(invoiceJson, size)
      withClue(s"chunk size $size: ")(result shouldBe expected)
    }
  }

  it should "show partial values that only ever grow, each well typed, ending with the whole value" in {
    forAll(Gen.choose(1, 40)) { size =>
      val (_, seen, _) = stream(invoiceJson, size)
      seen should not be empty
      seen.zip(seen.drop(1)).foreach { case (previous, next) =>
        withClue(s"chunk size $size, $previous then $next: ")(grows(previous, next) shouldBe true)
        previous should not be next
      }
      seen.foreach(p => welltyped(p) shouldBe true)
      val last = seen.last
      last.vendor shouldBe Some(invoice.vendor)
      last.items.map(_.size) shouldBe Some(2)
      last.items.flatMap(_.headOption).flatMap(_.price) shouldBe Some(BigDecimal("12.50"))
      last.status shouldBe Some(Status.Paid)
      last.note shouldBe Some(None)
      last.totalCents shouldBe Some(9007199254740993L)
      last.tags shouldBe Some(Vector("urgent", "q3"))
      last.lines shouldBe Some(Seq(1, -20, 300))
    }
  }

  it should "show a string as it grows and a list item while it is written, and a number only once complete" in {
    val (_, seen, _) = stream(invoiceJson, 1)
    val vendors      = seen.flatMap(_.vendor).distinct
    vendors.take(3) shouldBe List("", "A", "Ac")
    // the first item appears as soon as its object opens, before any of its fields
    val firstItems = seen.flatMap(_.items).flatMap(_.headOption)
    firstItems.head shouldBe ((sku = None, quantity = None, price = None))
    // the quantity 12 is never shown as 1
    firstItems.flatMap(_.quantity).distinct shouldBe List(12)
    seen.flatMap(_.totalCents).distinct shouldBe List(9007199254740993L)
    seen.flatMap(_.lines).distinct shouldBe List(Nil, Seq(1), Seq(1, -20), Seq(1, -20, 300))
  }

  it should "send the response format completeStructuredOf sends, through streamComplete" in {
    val (_, _, client) = stream(invoiceJson, 7)
    val scripted       = ScriptedLLMClient.always(Reply.text(invoiceJson))
    scripted.completeStructuredOf[Invoice](conversation)
    client.lastCall.map(_.streamed) shouldBe Some(true)
    client.lastCall.flatMap(_.options.responseFormat) shouldBe scripted.lastCall.flatMap(_.options.responseFormat)
    client.lastCall.flatMap(_.options.responseFormat) shouldBe defined
  }

  it should "pass the caller's options on" in {
    val client = ScriptedLLMClient.always(Reply.text(invoiceJson))
    client.streamStructuredOf[Invoice](conversation, CompletionOptions().withTemperature(0.0))(_ => ())
    client.lastCall.map(_.options.temperature) shouldBe Some(0.0)
  }

  it should "skip a fence or a preamble before the object, and read the reply as completeStructuredOf does" in {
    val fenced            = s"Here it is:\n```json\n$invoiceJson\n```"
    val (result, seen, _) = stream(fenced, 5)
    result shouldBe Right(invoice)
    seen.last.vendor shouldBe Some(invoice.vendor)
  }

  it should "return completeStructuredOf's error for a reply it cannot read, after the partial values" in {
    val wrongType         = """{"vendor": "Acme", "currency": 7}"""
    val (result, seen, _) = stream(wrongType, 3)
    val expected          = ScriptedLLMClient.always(Reply.text(wrongType)).completeStructuredOf[Invoice](conversation)
    result shouldBe expected
    result.left.toOption.collect { case e: ValidationError => e.field } shouldBe Some("structured_output")
    // the number written where a string belongs is never shown as the currency
    seen.flatMap(_.currency) shouldBe empty
    seen.last.vendor shouldBe Some("Acme")
  }

  it should "return the provider's error unchanged" in {
    val failure = ValidationError("boom", "provider failed")
    val client  = ScriptedLLMClient.always(Reply.failure(failure))
    client.streamStructuredOf[Invoice](conversation)(_ => fail("no partial value expected")) shouldBe Left(failure)
  }

  it should "parse content only, not thinking deltas or tool calls" in {
    val client = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Left(ValidationError("complete", "not used"))
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        onChunk(StreamedChunk("s", None, thinkingDelta = Some("""{"vendor": "thinking"}""")))
        onChunk(StreamedChunk("s", Some("")))
        onChunk(StreamedChunk("s", Some(invoiceJson)))
        Right(Completion("s", Instant.EPOCH, invoiceJson, "m", AssistantMessage(invoiceJson)))
      }
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }
    val seen = ListBuffer.empty[invoices.Out]
    client.streamStructuredOf[Invoice](conversation)(seen += _) shouldBe Right(invoice)
    seen.map(_.vendor) shouldBe List(Some(invoice.vendor))
  }

  it should "show nothing before the object starts" in {
    val (_, seen, _) = stream("no json at all", 4)
    seen shouldBe empty
  }
}
