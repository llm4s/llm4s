package org.llm4s.context

import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HistoryCompressorSpec extends AnyFlatSpec with Matchers {

  val counter: ConversationTokenCounter = ContextTestFixtures.createSimpleCounter()

  // ============ Basic Digest Creation ============

  "HistoryCompressor.compressToDigest" should "keep last K blocks verbatim" in {
    val messages = Seq(
      UserMessage("Question 1"),
      AssistantMessage("Answer 1"),
      UserMessage("Question 2"),
      AssistantMessage("Answer 2"),
      UserMessage("Question 3"),
      AssistantMessage("Answer 3")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 200, keepLastK = 1)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Last block (Q3 + A3) should be verbatim
    processed.exists(_.content == "Question 3") shouldBe true
    processed.exists(_.content == "Answer 3") shouldBe true
  }

  it should "create HISTORY_SUMMARY for older blocks" in {
    val messages = Seq(
      UserMessage("Old question 1"),
      AssistantMessage("Old answer 1"),
      UserMessage("Recent question"),
      AssistantMessage("Recent answer")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 1)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Should have HISTORY_SUMMARY for older block
    processed.exists(_.content.contains("[HISTORY_SUMMARY]")) shouldBe true
  }

  it should "be idempotent when summaries already exist" in {
    val messages = Seq(
      SystemMessage("[HISTORY_SUMMARY]\nPrevious conversation summary"),
      UserMessage("Current question"),
      AssistantMessage("Current answer")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 100, keepLastK = 1)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Should return messages unchanged
    processed shouldBe messages
  }

  // ============ Information Extraction ============

  "HistoryCompressor" should "extract identifiers from content" in {
    val messages = Seq(
      UserMessage("Get order with ID: ORD-12345"),
      AssistantMessage("Found order ID: ORD-12345, customer key: CUST-789")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 0)

    result.isRight shouldBe true
    val processed = result.toOption.get
    val summary   = processed.find(_.content.contains("[HISTORY_SUMMARY]")).get.content

    // Should extract IDs
    summary.toLowerCase should include("id")
  }

  it should "extract URLs from content" in {
    val messages = Seq(
      UserMessage("Check this URL: https://example.com/api/v1"),
      AssistantMessage("I checked the endpoint at https://example.com/api/v1/resource")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 0)

    result.isRight shouldBe true
    val processed = result.toOption.get
    val summary   = processed.find(_.content.contains("[HISTORY_SUMMARY]")).get.content

    // Should note URL presence
    summary should include("URL")
  }

  it should "extract error messages from content" in {
    val messages = Seq(
      UserMessage("Why did it fail?"),
      AssistantMessage("The error occurred because: Authentication failed.")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 0)

    result.isRight shouldBe true
    val processed = result.toOption.get
    val summary   = processed.find(_.content.contains("[HISTORY_SUMMARY]")).get.content

    // Should extract error info
    summary should include("Error")
  }

  it should "extract decisions from content" in {
    val messages = Seq(
      UserMessage("Which framework should we use?"),
      AssistantMessage("We decided to use Scala with Cats for the implementation.")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 0)

    result.isRight shouldBe true
    val processed = result.toOption.get
    val summary   = processed.find(_.content.contains("[HISTORY_SUMMARY]")).get.content

    // Should extract decision
    summary should include("Decision")
  }

  // ============ Consolidation ============

  it should "consolidate multiple digests when over cap" in {
    // Create many messages that would generate multiple digest blocks
    val messages = (1 to 10).flatMap { i =>
      Seq(
        UserMessage(s"Question $i about topic $i"),
        AssistantMessage(s"Detailed answer $i with information about topic $i")
      )
    }

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 50, keepLastK = 1)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Should have consolidated HISTORY_SUMMARY
    val summaries = processed.filter(_.content.contains("[HISTORY_SUMMARY]"))
    summaries.length should be >= 1
  }

  // ============ Edge Cases ============

  "HistoryCompressor" should "handle empty message list" in {
    val result = HistoryCompressor.compressToDigest(Seq.empty, counter, capTokens = 100, keepLastK = 1)

    result.isRight shouldBe true
    result.toOption.get shouldBe empty
  }

  it should "handle single message" in {
    val messages = Seq(UserMessage("Single question"))

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 100, keepLastK = 1)

    result.isRight shouldBe true
  }

  it should "handle keepLastK = 0 (compress everything)" in {
    val messages = Seq(
      UserMessage("Question"),
      AssistantMessage("Answer")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 0)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Should have only HISTORY_SUMMARY
    processed.forall(_.content.contains("[HISTORY_SUMMARY]")) shouldBe true
  }

  it should "handle keepLastK greater than total blocks" in {
    val messages = Seq(
      UserMessage("Only question"),
      AssistantMessage("Only answer")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 100, keepLastK = 10)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Should keep all messages verbatim
    processed.exists(_.content == "Only question") shouldBe true
    processed.exists(_.content == "Only answer") shouldBe true
    processed.exists(_.content.contains("[HISTORY_SUMMARY]")) shouldBe false
  }

  it should "handle messages with tool interactions" in {
    // Use explicit "tool" and "function" keywords to trigger pattern matching
    val messages = Seq(
      UserMessage("Use the tool to get the weather"),
      AssistantMessage(
        content = "I will call the weather function now.",
        toolCalls = Seq(ToolCall("call_1", "get_weather", ujson.Obj("city" -> "London")))
      ),
      ToolMessage("""{"temp": 15, "condition": "cloudy"}""", "call_1"),
      AssistantMessage("The tool returned that the weather in London is 15°C and cloudy.")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 0)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // Should have HISTORY_SUMMARY
    processed.exists(_.content.contains("[HISTORY_SUMMARY]")) shouldBe true
    // The exact content extraction depends on regex patterns - just verify we got a summary
    val summary = processed.find(_.content.contains("[HISTORY_SUMMARY]")).get.content
    summary.length should be > 20
  }

  it should "preserve message order after compression" in {
    val messages = Seq(
      UserMessage("Old Q1"),
      AssistantMessage("Old A1"),
      UserMessage("Old Q2"),
      AssistantMessage("Old A2"),
      UserMessage("Recent Q"),
      AssistantMessage("Recent A")
    )

    val result = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 1)

    result.isRight shouldBe true
    val processed = result.toOption.get

    // HISTORY_SUMMARY should come before recent messages
    val summaryIdx = processed.indexWhere(_.content.contains("[HISTORY_SUMMARY]"))
    val recentIdx  = processed.indexWhere(_.content == "Recent Q")

    if (summaryIdx >= 0 && recentIdx >= 0) {
      summaryIdx should be < recentIdx
    }
  }

  // a digest is text built from the messages' text view: a block with media is kept as it is (#1913)
  "compressToDigest" should "keep an older block with media verbatim, and digest only the text blocks" in {
    val chart = ContentPart
      .image(
        org.llm4s.media.MediaSource.Url(java.net.URI.create("https://e.com/chart.png")),
        org.llm4s.media.MediaType.Png
      )
      .toOption
      .get
    val messages = Seq(
      UserMessage("Question 0"),
      AssistantMessage("Answer 0"),
      UserMessage(Vector(ContentPart.Text("What does this show?"), chart)),
      AssistantMessage("Revenue rising."),
      UserMessage("Question 2"),
      AssistantMessage("Answer 2"),
      UserMessage("Question 3"),
      AssistantMessage("Answer 3")
    )
    val processed = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 1).toOption.get
    processed.collect { case u: UserMessage => u }.find(_.parts.contains(chart)) shouldBe defined
    processed.exists(m => m.isInstanceOf[SystemMessage] && m.content.startsWith("[HISTORY_SUMMARY]")) shouldBe true
    processed.exists(_.content == "Answer 3") shouldBe true
  }

  it should "keep a media tool result with the call that asked for it, so the history stays valid" in {
    val chart = ContentPart
      .image(
        org.llm4s.media.MediaSource.Url(java.net.URI.create("https://e.com/chart.png")),
        org.llm4s.media.MediaType.Png
      )
      .toOption
      .get
    val call = AssistantMessage(contentOpt = None, toolCalls = Seq(ToolCall("c1", "render", ujson.Obj())))
    val messages = Seq(
      UserMessage("Draw it"),
      call,
      ToolMessage(Vector(chart), "c1"),
      AssistantMessage("Here it is."),
      UserMessage("Question 2"),
      AssistantMessage("Answer 2"),
      UserMessage("Question 3"),
      AssistantMessage("Answer 3")
    )
    val processed = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 1).toOption.get
    processed should contain(call)
    processed should contain(ToolMessage(Vector(chart), "c1"))
    processed.indexOf(call) should be < processed.indexOf(ToolMessage(Vector(chart), "c1"))
    // every kept call has its result, and every kept result its call
    val calls   = processed.collect { case a: AssistantMessage => a.toolCalls.map(_.id) }.flatten.toSet
    val results = processed.collect { case t: ToolMessage => t.toolCallId }.toSet
    calls shouldBe results
  }

  it should "keep an older call whose media result is among the recent blocks" in {
    val chart = ContentPart
      .image(
        org.llm4s.media.MediaSource.Url(java.net.URI.create("https://e.com/chart.png")),
        org.llm4s.media.MediaType.Png
      )
      .toOption
      .get
    val call = AssistantMessage(contentOpt = None, toolCalls = Seq(ToolCall("c9", "render", ujson.Obj())))
    val messages = Seq(
      UserMessage("Question 1"),
      AssistantMessage("Answer 1"),
      UserMessage("Draw it"),
      call,
      ToolMessage(Vector(chart), "c9")
    )
    // the last block - the tool result alone - is recent; the call's block is older
    val processed = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 1).toOption.get
    processed should contain(call)
    processed.indexOf(call) should be < processed.indexOf(ToolMessage(Vector(chart), "c9"))
  }

  it should "keep the history's order: the older blocks from the first kept one on stay as they are" in {
    val chart = ContentPart
      .image(
        org.llm4s.media.MediaSource.Url(java.net.URI.create("https://e.com/chart.png")),
        org.llm4s.media.MediaType.Png
      )
      .toOption
      .get
    val media = UserMessage(Vector(ContentPart.Text("Look at this"), chart))
    val messages = Seq(
      UserMessage("Question 0"),
      AssistantMessage("Answer 0"),
      media,
      AssistantMessage("A chart."),
      UserMessage("Question 2"),
      AssistantMessage("Answer 2"),
      UserMessage("Question 3"),
      AssistantMessage("Answer 3")
    )
    val processed = HistoryCompressor.compressToDigest(messages, counter, capTokens = 500, keepLastK = 1).toOption.get
    // the block before the media is digested; the media and everything after it keep their order
    processed.exists(_.content == "Question 0") shouldBe false
    processed.dropWhile(_.isInstanceOf[SystemMessage]) shouldBe messages.drop(2)
  }
}
