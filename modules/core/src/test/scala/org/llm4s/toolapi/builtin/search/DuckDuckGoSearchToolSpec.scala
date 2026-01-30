package org.llm4s.toolapi.builtin.search

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DuckDuckGoSearchToolSpec extends AnyFlatSpec with Matchers {

  "DuckDuckGoSearchConfig" should "have correct default values" in {
    val config = DuckDuckGoSearchConfig()

    config.timeoutMs shouldBe 10000
    config.maxResults shouldBe 10
    config.safeSearch shouldBe true
  }

  it should "accept custom configuration values" in {
    val config = DuckDuckGoSearchConfig(
      timeoutMs = 5000,
      maxResults = 5,
      safeSearch = false
    )
    config.timeoutMs shouldBe 5000
    config.maxResults shouldBe 5
    config.safeSearch shouldBe false
  }

  "DuckDuckGoSearchTool" should "have the correct metadata" in {
    val tool = DuckDuckGoSearchTool.create()
    tool.name shouldBe "duckduckgo_search"
    tool.description shouldBe
      "Search the web for definitions, facts, and quick answers using DuckDuckGo. Best for factual queries and definitions. Does not provide full web search results."

  }

  "RelatedTopic" should "serialize and deserialize correctly" in {
    val topic = RelatedTopic(
      text = "Test topic",
      url = Some("https://example.com")
    )
    val json         = upickle.default.write(topic)
    val deserialized = upickle.default.read[RelatedTopic](json)
    deserialized shouldBe topic
  }
  it should "handle missing URL" in {
    val topic = RelatedTopic(
      text = "Test topic without URL",
      url = None
    )
    val json         = upickle.default.write(topic)
    val deserialized = upickle.default.read[RelatedTopic](json)
    deserialized shouldBe topic
    deserialized.url shouldBe None
  }
  "DuckDuckGoSearchResult" should "serialize and deserialize correctly" in {
    val result = DuckDuckGoSearchResult(
      query = "test query",
      abstract_ = "Test abstract",
      abstractSource = "Wikipedia",
      abstractUrl = "https://en.wikipedia.org/wiki/Test",
      answer = "Test answer",
      answerType = "instant_answer",
      relatedTopics = Seq(
        RelatedTopic("Topic 1", Some("https://example.com/1")),
        RelatedTopic("Topic 2", None)
      )
    )
    val json         = upickle.default.write(result)
    val deserialized = upickle.default.read[DuckDuckGoSearchResult](json)
    deserialized shouldBe result
  }
  it should "handle empty fields" in {
    val result = DuckDuckGoSearchResult(
      query = "test",
      abstract_ = "",
      abstractSource = "",
      abstractUrl = "",
      answer = "",
      answerType = "",
      relatedTopics = Seq.empty
    )
    val json         = upickle.default.write(result)
    val deserialized = upickle.default.read[DuckDuckGoSearchResult](json)
    deserialized shouldBe result
    deserialized.relatedTopics shouldBe empty
  }
}
