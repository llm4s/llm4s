package org.llm4s.toolapi.builtin.search

import org.llm4s.config.DuckDuckGoSearchToolConfig
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }
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
    val toolConfig = DuckDuckGoSearchToolConfig(apiUrl = "https://api.duckduckgo.com")
    DuckDuckGoSearchTool
      .create(toolConfig)
      .fold(
        e => fail(s"Tool creation failed: ${e.formatted}"),
        tool => {
          tool.name shouldBe "duckduckgo_search"
          tool.description shouldBe
            "Search the web for definitions, facts, and quick answers using DuckDuckGo. Best for factual queries and definitions. Does not provide full web search results."
        }
      )
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

  "DuckDuckGoSearchTool.parseResults" should "correctly parse Instant Answer results" in {
    val config = DuckDuckGoSearchConfig(maxResults = 2)
    val json = ujson.Obj(
      "Abstract"       -> "Scala Abstract",
      "AbstractSource" -> "Wikipedia",
      "AbstractURL"    -> "https://en.wikipedia.org/wiki/Scala",
      "Answer"         -> "A functional programming language",
      "AnswerType"     -> "definition",
      "RelatedTopics" -> ujson.Arr(
        ujson.Obj(
          "Text"     -> "Topic 1",
          "FirstURL" -> "https://example.com/1"
        ),
        ujson.Obj(
          "Text"     -> "Topic 2",
          "FirstURL" -> "https://example.com/2"
        ),
        ujson.Obj(
          "Text"     -> "Topic 3",
          "FirstURL" -> "https://example.com/3"
        )
      )
    )

    val result = DuckDuckGoSearchTool.parseResults("scala", json, config)

    result.query shouldBe "scala"
    result.abstract_ shouldBe "Scala Abstract"
    result.abstractSource shouldBe "Wikipedia"
    result.abstractUrl shouldBe "https://en.wikipedia.org/wiki/Scala"
    result.answer shouldBe "A functional programming language"
    result.answerType shouldBe "definition"

    // Verify maxResults limit
    result.relatedTopics should have size 2
    result.relatedTopics.head shouldBe RelatedTopic("Topic 1", Some("https://example.com/1"))
    result.relatedTopics(1) shouldBe RelatedTopic("Topic 2", Some("https://example.com/2"))
  }

  it should "handle missing fields by using defaults" in {
    val result = DuckDuckGoSearchTool.parseResults("q", ujson.Obj(), DuckDuckGoSearchConfig())

    result.abstract_ shouldBe ""
    result.abstractSource shouldBe ""
    result.abstractUrl shouldBe ""
    result.answer shouldBe ""
    result.answerType shouldBe ""
    result.relatedTopics shouldBe empty
  }

  // --- Unit tests for DuckDuckGoSearchTool.search() with mocked HTTP ---

  "DuckDuckGoSearchTool.search" should "return parsed results on 200 response" in {
    val body = ujson
      .Obj(
        "Abstract"       -> "Scala is a programming language",
        "AbstractSource" -> "Wikipedia",
        "AbstractURL"    -> "https://en.wikipedia.org/wiki/Scala",
        "Answer"         -> "A JVM language",
        "AnswerType"     -> "definition",
        "RelatedTopics" -> ujson.Arr(
          ujson.Obj("Text" -> "Topic 1", "FirstURL" -> "https://example.com/1")
        )
      )
      .render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    val result = DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "scala",
      DuckDuckGoSearchConfig(),
      mockClient,
      () => ()
    )

    result.isRight shouldBe true
    val searchResult = result.getOrElse(fail("Expected Right"))
    searchResult.query shouldBe "scala"
    searchResult.abstract_ shouldBe "Scala is a programming language"
    searchResult.abstractSource shouldBe "Wikipedia"
    searchResult.answer shouldBe "A JVM language"
    searchResult.relatedTopics should have size 1
    searchResult.relatedTopics.head.text shouldBe "Topic 1"
  }

  it should "return error on non-200 status code" in {
    val mockClient = new MockHttpClient(HttpResponse(500, "Internal Server Error"))

    val result = DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "test",
      DuckDuckGoSearchConfig(),
      mockClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("500")
  }

  it should "return sanitized error on invalid JSON response" in {
    val mockClient = new MockHttpClient(HttpResponse(200, "not json {{{"))

    val result = DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "test",
      DuckDuckGoSearchConfig(),
      mockClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should (include("parse").or(include("invalid")))
  }

  it should "send correct headers and query params" in {
    val body       = ujson.Obj("Abstract" -> "", "RelatedTopics" -> ujson.Arr()).render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "scala lang",
      DuckDuckGoSearchConfig(safeSearch = true),
      mockClient,
      () => ()
    )

    mockClient.lastUrl shouldBe Some("https://api.duckduckgo.com")
    mockClient.lastHeaders.flatMap(_.get("User-Agent")) shouldBe Some("llm4s-duckduckgo-search/1.0")
    mockClient.lastParams.flatMap(_.get("q")) shouldBe Some("scala lang")
    mockClient.lastParams.flatMap(_.get("format")) shouldBe Some("json")
    mockClient.lastParams.flatMap(_.get("safesearch")) shouldBe Some("1")
  }

  it should "send safesearch=-1 when safeSearch is false" in {
    val body       = ujson.Obj("Abstract" -> "", "RelatedTopics" -> ujson.Arr()).render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "test",
      DuckDuckGoSearchConfig(safeSearch = false),
      mockClient,
      () => ()
    )

    mockClient.lastParams.flatMap(_.get("safesearch")) shouldBe Some("-1")
  }

  it should "return sanitized error on timeout" in {
    val failingClient = new FailingHttpClient(new java.net.http.HttpTimeoutException("timed out"))

    val result = DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "test",
      DuckDuckGoSearchConfig(timeoutMs = 3000),
      failingClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("timed out")
    error should include("3000ms")
  }

  it should "return sanitized error on unknown host" in {
    val failingClient = new FailingHttpClient(new java.net.UnknownHostException("no such host"))

    val result = DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "test",
      DuckDuckGoSearchConfig(),
      failingClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("Unable to reach")
    error should include("network connectivity")
  }

  it should "return sanitized error on connection refused" in {
    val failingClient = new FailingHttpClient(new java.net.ConnectException("Connection refused"))

    val result = DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "test",
      DuckDuckGoSearchConfig(),
      failingClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("Failed to connect")
    error should include("temporarily unavailable")
  }

  it should "return generic sanitized error on unexpected exception" in {
    val failingClient = new FailingHttpClient(new RuntimeException("secret internal details"))

    val result = DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "test",
      DuckDuckGoSearchConfig(),
      failingClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("network error")
    (error should not).include("secret internal details")
  }

  it should "call restoreInterrupt and return sanitized error on InterruptedException" in {
    var interruptRestored = false
    val mockRestore       = () => interruptRestored = true
    val failingClient     = new FailingHttpClient(new InterruptedException("interrupted"))

    val result = DuckDuckGoSearchTool.search(
      "https://api.duckduckgo.com",
      "test",
      DuckDuckGoSearchConfig(),
      failingClient,
      mockRestore
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should (include("cancelled").or(include("interrupted")))
    interruptRestored shouldBe true
  }
}
