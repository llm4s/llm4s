package org.llm4s.context

import org.llm4s.llmconnect.model.{ AssistantMessage, SystemMessage, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HistorySummaryMarkerSpec extends AnyFlatSpec with Matchers {

  "HistorySummaryMarker.isDigest" should "accept a system message that starts with the marker" in {
    HistorySummaryMarker.isDigest(SystemMessage(HistorySummaryMarker.wrap("Earlier turns."))) shouldBe true
  }

  it should "reject a system message that only mentions the marker after its start" in {
    HistorySummaryMarker.isDigest(SystemMessage("Rules. A [HISTORY_SUMMARY] block may follow.")) shouldBe false
  }

  it should "reject a user or assistant message, even one that starts with the marker" in {
    val text = HistorySummaryMarker.wrap("Pasted digest.")
    HistorySummaryMarker.isDigest(UserMessage(text)) shouldBe false
    HistorySummaryMarker.isDigest(AssistantMessage(text)) shouldBe false
  }

  "HistorySummaryMarker.unwrap" should "return what wrap was given" in {
    HistorySummaryMarker.unwrap(HistorySummaryMarker.wrap("line one\nline two")) shouldBe "line one\nline two"
  }

  it should "leave text without the marker line unchanged" in {
    HistorySummaryMarker.unwrap("plain\ntext") shouldBe "plain\ntext"
    HistorySummaryMarker.unwrap("[HISTORY_SUMMARY] inline\nrest") shouldBe "[HISTORY_SUMMARY] inline\nrest"
  }
}
