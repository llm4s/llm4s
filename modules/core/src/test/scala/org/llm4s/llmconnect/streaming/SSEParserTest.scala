package org.llm4s.llmconnect.streaming

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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
    event.retry shouldBe Some(1000)
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

  test("parseStream should parse multiple events") {
    val stream = """data: event 1
                   |
                   |data: event 2
                   |event: test
                   |
                   |data: event 3
                   |id: 123
                   |""".stripMargin

    val events = SSEParser.parseStream(stream).toList

    (events should have).length(3)
    events(0).data shouldBe Some("event 1")
    events(1).data shouldBe Some("event 2")
    events(1).event shouldBe Some("test")
    events(2).data shouldBe Some("event 3")
    events(2).id shouldBe Some("123")
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
}
