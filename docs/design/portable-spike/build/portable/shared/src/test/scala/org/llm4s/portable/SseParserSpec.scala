package org.llm4s.portable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SseParserSpec extends AnyFlatSpec with Matchers {

  "SseParser" should "return each event's data once the blank line that ends it arrives" in {
    val p = new SseParser
    p.feed("data: one\n") shouldBe empty
    p.feed("\ndata: two\n\n") shouldBe Vector("one", "two")
  }

  it should "join multi-line data and skip comments and other fields" in {
    new SseParser().feed(": keep-alive\nevent: message\nid: 7\ndata: a\ndata:b\n\n") shouldBe Vector("a\nb")
  }

  it should "accept CRLF and CR line ends, including a CRLF split across pieces" in {
    val p = new SseParser
    p.feed("data: x\r") shouldBe empty
    p.feed("\n\r\n") shouldBe Vector("x")
    p.feed("data: y\r\rdata: w\n\n") shouldBe Vector("y", "w")
  }

  it should "ignore blank lines that end no event" in {
    new SseParser().feed("\n\n\ndata: z\n\n") shouldBe Vector("z")
  }
}
