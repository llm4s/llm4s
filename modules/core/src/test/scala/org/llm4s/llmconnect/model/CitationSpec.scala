package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[Citation]] and [[Completion.citations]]: what a completion says about the sources behind its answer. */
class CitationSpec extends AnyFlatSpec with Matchers {

  private val message = AssistantMessage(contentOpt = Some("answer"))
  private val source =
    Citation(url = "https://example.com/a", title = Some("A"), startIndex = Some(0), endIndex = Some(6))

  "Citation" should "need only a url, and report nothing it was not given" in {
    val c = Citation("https://example.com")

    c.url shouldBe "https://example.com"
    c.title shouldBe None
    c.citedText shouldBe None
    c.startIndex shouldBe None
    c.endIndex shouldBe None
    c.hasSpan shouldBe false
  }

  it should "carry every field it is given, positionally or by name" in {
    val c = Citation("https://example.com", Some("Title"), Some("passage"), Some(3), Some(9))

    c shouldBe Citation(
      url = "https://example.com",
      title = Some("Title"),
      citedText = Some("passage"),
      startIndex = Some(3),
      endIndex = Some(9)
    )
    c.hasSpan shouldBe true
  }

  it should "say it has a span only when both ends are known" in {
    Citation("u", startIndex = Some(1)).hasSpan shouldBe false
    Citation("u", endIndex = Some(1)).hasSpan shouldBe false
    Citation("u", startIndex = Some(1), endIndex = Some(2)).hasSpan shouldBe true
  }

  it should "change one field at a time, leaving the others as they were" in {
    val base = Citation("u", Some("t"), Some("c"), Some(1), Some(2))

    base.withUrl("v") shouldBe Citation("v", Some("t"), Some("c"), Some(1), Some(2))
    base.withTitle("x") shouldBe Citation("u", Some("x"), Some("c"), Some(1), Some(2))
    base.withTitle(None) shouldBe Citation("u", None, Some("c"), Some(1), Some(2))
    base.withCitedText("y") shouldBe Citation("u", Some("t"), Some("y"), Some(1), Some(2))
    base.withCitedText(None) shouldBe Citation("u", Some("t"), None, Some(1), Some(2))
    base.withStartIndex(5) shouldBe Citation("u", Some("t"), Some("c"), Some(5), Some(2))
    base.withStartIndex(None) shouldBe Citation("u", Some("t"), Some("c"), None, Some(2))
    base.withEndIndex(8) shouldBe Citation("u", Some("t"), Some("c"), Some(1), Some(8))
    base.withEndIndex(None) shouldBe Citation("u", Some("t"), Some("c"), Some(1), None)
  }

  it should "keep its constructor and copy private, so a later field cannot break a caller" in {
    val base = Citation("u")

    base.withTitle("t").title shouldBe Some("t") // the supported way
    scala.compiletime.testing.typeCheckErrors("base.copy(url = \"v\")") should not be empty
    scala.compiletime.testing.typeCheckErrors("new Citation(\"u\", None, None, None, None)") should not be empty
  }

  "Completion" should "have no citations unless it is given some" in {
    val completion = Completion(id = "1", created = 0L, content = "answer", model = "m", message = message)

    completion.citations shouldBe Nil
    completion.hasCitations shouldBe false
  }

  it should "still be built the way it was before it had citations" in {
    // eight positional arguments: what every caller wrote when the last field was the estimated cost
    val positional = Completion("1", 0L, "answer", "m", message, List.empty, None, Some(0.5))

    positional.estimatedCost shouldBe Some(0.5)
    positional.citations shouldBe Nil

    // and by name, leaving the later fields out
    val named = Completion(id = "1", created = 0L, content = "answer", model = "m", message = message, usage = None)
    named.citations shouldBe Nil
  }

  it should "carry the citations it is given, in order" in {
    val second = Citation("https://example.com/b")
    val c = Completion(
      id = "1",
      created = 0L,
      content = "answer",
      model = "m",
      message = message,
      citations = List(source, second)
    )

    c.citations shouldBe List(source, second)
    c.hasCitations shouldBe true
  }

  it should "set citations with withCitations, changing nothing else" in {
    val base  = Completion(id = "1", created = 7L, content = "answer", model = "m", message = message)
    val with1 = base.withCitations(List(source))

    with1.citations shouldBe List(source)
    with1.withCitations(Nil) shouldBe base
    with1.withId("1") shouldBe with1
  }

  it should "treat completions that differ only in their citations as different" in {
    val base = Completion(id = "1", created = 0L, content = "answer", model = "m", message = message)

    (base should not).equal(base.withCitations(List(source)))
    base.withCitations(List(source)) shouldBe base.withCitations(List(source))
  }

  "A completion assembled from streamed chunks" should "carry no citations, because a chunk reports none" in {
    val acc = StreamingAccumulator.create()
    acc.addChunk(StreamedChunk(id = "s", content = Some("answer"), finishReason = Some("stop")))

    acc.toCompletion(0L).map(_.citations) shouldBe Right(Nil)
  }
}
