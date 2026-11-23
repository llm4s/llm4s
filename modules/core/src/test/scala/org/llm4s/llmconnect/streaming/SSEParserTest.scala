package org.llm4s.llmconnect.streaming

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class SSEParserTest extends AnyFunSuite with Matchers {

  test("parseEvent should parse simple SSE event") {
    val eventString = "data: {\"test\": \"value\"}\n"
    val event       = SSEParser.parseEvent(eventString)

    event.data shouldBe Some("{\"test\": \"value\"}")
    event.event shouldBe None
    event.id shouldBe None
    event.retry shouldBe None
  }

  test("parseEvent should parse event with multiple fields") {
    val eventString = """event: message
                         |data: {"content": "hello"}
                         |id: msg-123
                         |retry: 1000
                         |""".stripMargin

    val event = SSEParser.parseEvent(eventString)

    event.event shouldBe Some("message")
    event.data shouldBe Some("{\"content\": \"hello\"}")
    event.id shouldBe Some("msg-123")
    event.retry shouldBe Some(1000.millis)
  }

  test("parseEvent should handle comments") {
    val eventString = """: This is a comment
                         |data: test data
                         |: Another comment
                         |""".stripMargin

    val event = SSEParser.parseEvent(eventString)

    event.data shouldBe Some("test data")
    event.event shouldBe None
  }

  test("parseEvent should handle multi-line data with newline joins") {
    val eventString = """data: line 1
                         |data: line 2
                         |data: line 3
                         |""".stripMargin

    val event = SSEParser.parseEvent(eventString)

    event.data shouldBe Some("line 1\nline 2\nline 3")
  }

  test("StreamingParser should accumulate chunks") {
    val parser = SSEParser.createStreamingParser()

    parser.addChunk("data: part")
    parser.hasEvents shouldBe false

    parser.addChunk("ial data\n\n")
    parser.hasEvents shouldBe true

    val event = parser.nextEvent()
    event.isDefined shouldBe true
    event.get.data shouldBe Some("partial data")
  }

  test("StreamingParser should handle incomplete events") {
    val parser = SSEParser.createStreamingParser()

    parser.addChunk("data: {\"content\": ")
    parser.addChunk("\"hello world\"")
    parser.addChunk("}\n")
    parser.addChunk("\n")

    parser.hasEvents shouldBe true
    val event = parser.nextEvent()
    event.isDefined shouldBe true
    event.get.data shouldBe Some("{\"content\": \"hello world\"}")
  }

  test("StreamingParser should handle OpenAI [DONE] message") {
    val parser = SSEParser.createStreamingParser()

    parser.addChunk("data: [DONE]\n\n")

    parser.hasEvents shouldBe true
    val event = parser.nextEvent()
    event.isDefined shouldBe true
    event.get.data shouldBe Some("[DONE]")
  }

  test("StreamingParser should flush remaining data") {
    val parser = SSEParser.createStreamingParser()

    parser.addChunk("data: incomplete")
    parser.hasEvents shouldBe false

    val flushed = parser.flush()
    flushed.isDefined shouldBe true
    flushed.get.data shouldBe Some("incomplete")
  }

  private val threeEvents = List(
    "event: message\ndata: {\"a\":1}\nid: 1\n\n",
    ": keep-alive\ndata: line 1\ndata: line 2\n\n",
    "data: [DONE]\n\n"
  ).mkString

  private def eventsOf(stream: String, chunkSizes: Iterator[Int]): Vector[SSEParser.SSEEvent] = {
    val parser = SSEParser.createStreamingParser()
    val out    = Vector.newBuilder[SSEParser.SSEEvent]
    var rest   = stream
    while (rest.nonEmpty) {
      val (chunk, tail) = rest.splitAt(chunkSizes.next())
      parser.addChunk(chunk)
      while (parser.hasEvents) parser.nextEvent().foreach(out += _)
      rest = tail
    }
    parser.flush().foreach(out += _)
    out.result()
  }

  private val expected = eventsOf(threeEvents, Iterator.continually(threeEvents.length))

  test("StreamingParser should read the three events of an LF stream") {
    expected.map(_.data) shouldBe Vector(Some("{\"a\":1}"), Some("line 1\nline 2"), Some("[DONE]"))
    expected.head.event shouldBe Some("message")
    expected.head.id shouldBe Some("1")
  }

  test("StreamingParser should end an event only at a blank line, however an LF stream is chunked") {
    (1 to threeEvents.length).foreach(size => eventsOf(threeEvents, Iterator.continually(size)) shouldBe expected)
  }

  test("StreamingParser should read a CRLF stream fed in arbitrary chunks as the LF stream") {
    val crlf = threeEvents.replace("\n", "\r\n")
    val rnd  = new scala.util.Random(2813)
    (1 to crlf.length).foreach(size => eventsOf(crlf, Iterator.continually(size)) shouldBe expected)
    (1 to 50).foreach(_ => eventsOf(crlf, Iterator.continually(1 + rnd.nextInt(7))) shouldBe expected)
  }

  test("StreamingParser should read a CR-only stream fed in arbitrary chunks as the LF stream") {
    val cr = threeEvents.replace("\n", "\r")
    (1 to cr.length).foreach(size => eventsOf(cr, Iterator.continually(size)) shouldBe expected)
  }

  test("StreamingParser should emit each CRLF event as soon as its blank line arrives") {
    val parser = SSEParser.createStreamingParser()
    parser.addChunk("data: one\r\n\r")
    parser.nextEvent().flatMap(_.data) shouldBe Some("one")
    parser.addChunk("\ndata: two\r\n\r\n")
    parser.nextEvent().flatMap(_.data) shouldBe Some("two")
    parser.hasEvents shouldBe false
    parser.flush() shouldBe None
  }

  test("parseEvent should read CRLF and CR line endings") {
    SSEParser.parseEvent("event: e\r\ndata: a\r\ndata: b\r\n").data shouldBe Some("a\nb")
    SSEParser.parseEvent("event: e\rdata: a\rdata: b\r").event shouldBe Some("e")
    SSEParser.parseEvent("event: e\rdata: a\rdata: b\r").data shouldBe Some("a\nb")
  }

  test("parseEvent should read retry as a duration in milliseconds") {
    SSEParser.parseEvent("retry: 1500\n").retry shouldBe Some(1500.millis)
    SSEParser.parseEvent("retry: soon\n").retry shouldBe None
    SSEParser.parseEvent("retry: -5\n").retry shouldBe None
  }
}
