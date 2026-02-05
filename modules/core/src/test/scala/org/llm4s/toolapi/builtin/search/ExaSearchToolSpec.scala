package org.llm4s.toolapi.builtin.search

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.ExaSearchToolConfig
import upickle.default._

class ExaSearchToolSpec extends AnyFlatSpec with Matchers {

  "ExaSearchTool" should "have correct name and description" in {
    val toolConfig = ExaSearchToolConfig(
      apiKey = "test-key",
      apiUrl = "https://api.exa.ai",
      numResults = 10,
      searchType = "auto",
      maxCharacters = 500
    )
    val tool = ExaSearchTool.create(toolConfig)

    tool.name shouldBe "exa_search"
    tool.description shouldBe "Search the web using Exa. Supports auto, neural, and keyword search with rich content extraction."
  }

  "ExaSearchConfig" should "initialize with valid default parameters" in {
    val config = ExaSearchConfig()
    config.timeoutMs shouldBe 10000
    config.numResults shouldBe 10
    config.searchType shouldBe SearchType.Auto
    config.maxCharacters shouldBe 500
    config.extraParams shouldBe Map.empty
    config.userLocation shouldBe None
    config.livecrawlTimeout shouldBe None
    config.maxAgeHours shouldBe 1
    config.category shouldBe None
    config.additionalQueries shouldBe None
  }

  it should "allow overriding default values" in {
    val config = ExaSearchConfig(
      timeoutMs = 15000,
      numResults = 20,
      searchType = SearchType.Deep,
      maxCharacters = 2000,
      maxAgeHours = 48,
      category = Some(Category.ResearchPaper),
      userLocation = Some("San Francisco, CA"),
      livecrawlTimeout = Some(5000),
      additionalQueries = Some(List("query1", "query2")),
      extraParams = Map("custom" -> ujson.Str("value"))
    )
    config.timeoutMs shouldBe 15000
    config.numResults shouldBe 20
    config.searchType shouldBe SearchType.Deep
    config.maxCharacters shouldBe 2000
    config.maxAgeHours shouldBe 48
    config.category shouldBe Some(Category.ResearchPaper)
    config.userLocation shouldBe Some("San Francisco, CA")
    config.livecrawlTimeout shouldBe Some(5000)
    config.additionalQueries shouldBe Some(List("query1", "query2"))
    config.extraParams shouldBe Map("custom" -> ujson.Str("value"))
  }

  "SearchType" should "return correct values for all types" in {
    SearchType.Auto.value shouldBe "auto"
    SearchType.Neural.value shouldBe "neural"
    SearchType.Fast.value shouldBe "fast"
    SearchType.Deep.value shouldBe "deep"
  }

  it should "parse string values correctly" in {
    SearchType.fromString("auto") shouldBe SearchType.Auto
    SearchType.fromString("neural") shouldBe SearchType.Neural
    SearchType.fromString("fast") shouldBe SearchType.Fast
    SearchType.fromString("deep") shouldBe SearchType.Deep

    // Case-insensitive
    SearchType.fromString("AUTO") shouldBe SearchType.Auto
    SearchType.fromString("NEURAL") shouldBe SearchType.Neural

    // Whitespace handling
    SearchType.fromString("  auto  ") shouldBe SearchType.Auto

    // Invalid defaults to Auto
    SearchType.fromString("invalid") shouldBe SearchType.Auto
    SearchType.fromString("") shouldBe SearchType.Auto
  }

  "Category" should "return correct values for all categories" in {
    Category.Company.value shouldBe "company"
    Category.ResearchPaper.value shouldBe "research paper"
    Category.News.value shouldBe "news"
    Category.Pdf.value shouldBe "pdf"
    Category.Github.value shouldBe "github"
    Category.Tweet.value shouldBe "tweet"
    Category.PersonalSite.value shouldBe "personal site"
    Category.LinkedinProfile.value shouldBe "linkedin profile"
    Category.FinancialReport.value shouldBe "financial report"
  }

  it should "parse string values correctly" in {
    Category.fromString("company") shouldBe Some(Category.Company)
    Category.fromString("research paper") shouldBe Some(Category.ResearchPaper)
    Category.fromString("news") shouldBe Some(Category.News)
    Category.fromString("pdf") shouldBe Some(Category.Pdf)
    Category.fromString("github") shouldBe Some(Category.Github)
    Category.fromString("tweet") shouldBe Some(Category.Tweet)
    Category.fromString("personal site") shouldBe Some(Category.PersonalSite)
    Category.fromString("linkedin profile") shouldBe Some(Category.LinkedinProfile)
    Category.fromString("financial report") shouldBe Some(Category.FinancialReport)

    // Case-insensitive
    Category.fromString("COMPANY") shouldBe Some(Category.Company)
    Category.fromString("NEWS") shouldBe Some(Category.News)

    // Whitespace handling
    Category.fromString("  news  ") shouldBe Some(Category.News)

    // Invalid returns None
    Category.fromString("invalid") shouldBe None
    Category.fromString("") shouldBe None
    Category.fromString("random") shouldBe None
  }

  "ExaResult" should "create result with complete fields" in {
    val result = ExaResult(
      title = "Test Title",
      url = "https://example.com",
      id = Some("test-id-123"),
      publishedDate = Some("2024-01-15"),
      author = Some("John Doe"),
      text = Some("Sample text content"),
      highlights = Some(List("highlight1", "highlight2")),
      highlightScores = Some(List(0.9, 0.8)),
      summary = Some("Test summary"),
      favicon = Some("https://example.com/favicon.ico"),
      image = Some("https://example.com/image.png")
    )

    result.title shouldBe "Test Title"
    result.url shouldBe "https://example.com"
    result.id shouldBe Some("test-id-123")
    result.publishedDate shouldBe Some("2024-01-15")
    result.author shouldBe Some("John Doe")
    result.text shouldBe Some("Sample text content")
    result.highlights shouldBe Some(List("highlight1", "highlight2"))
    result.highlightScores shouldBe Some(List(0.9, 0.8))
    result.summary shouldBe Some("Test summary")
    result.favicon shouldBe Some("https://example.com/favicon.ico")
    result.image shouldBe Some("https://example.com/image.png")
  }

  it should "create result with only required fields" in {
    val result = ExaResult(
      title = "Minimal Title",
      url = "https://minimal.com"
    )

    result.title shouldBe "Minimal Title"
    result.url shouldBe "https://minimal.com"
    result.id shouldBe None
    result.publishedDate shouldBe None
    result.author shouldBe None
    result.text shouldBe None
    result.highlights shouldBe None
    result.highlightScores shouldBe None
    result.summary shouldBe None
    result.favicon shouldBe None
    result.image shouldBe None
    result.subPages shouldBe None
  }

  "ExaSearchResult" should "create search result with complete fields" in {
    val results = List(
      ExaResult(
        title = "Result 1",
        url = "https://example1.com",
        id = Some("id-1"),
        text = Some("Text 1")
      ),
      ExaResult(
        title = "Result 2",
        url = "https://example2.com",
        id = Some("id-2"),
        text = Some("Text 2")
      )
    )

    val searchResult = ExaSearchResult(
      query = "test query",
      results = results,
      requestId = Some("req-123"),
      searchType = Some("neural")
    )

    searchResult.query shouldBe "test query"
    (searchResult.results should have).length(2)
    searchResult.results.head.title shouldBe "Result 1"
    searchResult.results(1).title shouldBe "Result 2"
    searchResult.requestId shouldBe Some("req-123")
    searchResult.searchType shouldBe Some("neural")
  }

  it should "parse minimal search response" in {
    val searchResult = ExaSearchResult(
      query = "minimal query",
      results = List.empty
    )

    searchResult.query shouldBe "minimal query"
    searchResult.results shouldBe List.empty
    searchResult.requestId shouldBe None
    searchResult.searchType shouldBe None
  }

  "parseResponse" should "parse complete JSON response with all fields" in {
    val json = ujson.Obj(
      "requestId"  -> ujson.Str("req-abc-123"),
      "searchType" -> ujson.Str("neural"),
      "results" -> ujson.Arr(
        ujson.Obj(
          "title"           -> ujson.Str("Complete Result"),
          "url"             -> ujson.Str("https://example.com/article"),
          "id"              -> ujson.Str("result-id-1"),
          "publishedDate"   -> ujson.Str("2024-01-15T10:30:00Z"),
          "author"          -> ujson.Str("Jane Smith"),
          "text"            -> ujson.Str("This is the full text content of the article..."),
          "highlights"      -> ujson.Arr(ujson.Str("highlight one"), ujson.Str("highlight two")),
          "highlightScores" -> ujson.Arr(ujson.Num(0.95), ujson.Num(0.87)),
          "summary"         -> ujson.Str("A comprehensive summary of the article"),
          "favicon"         -> ujson.Str("https://example.com/favicon.ico"),
          "image"           -> ujson.Str("https://example.com/image.jpg")
        )
      )
    )

    val query  = "test query"
    val result = ExaSearchTool.parseResponse(json, query)

    result.query shouldBe "test query"
    result.requestId shouldBe Some("req-abc-123")
    result.searchType shouldBe Some("neural")
    (result.results should have).length(1)

    val firstResult = result.results.head
    firstResult.title shouldBe "Complete Result"
    firstResult.url shouldBe "https://example.com/article"
    firstResult.id shouldBe Some("result-id-1")
    firstResult.publishedDate shouldBe Some("2024-01-15T10:30:00Z")
    firstResult.author shouldBe Some("Jane Smith")
    firstResult.text shouldBe Some("This is the full text content of the article...")
    firstResult.highlights shouldBe Some(List("highlight one", "highlight two"))
    firstResult.highlightScores shouldBe Some(List(0.95, 0.87))
    firstResult.summary shouldBe Some("A comprehensive summary of the article")
    firstResult.favicon shouldBe Some("https://example.com/favicon.ico")
    firstResult.image shouldBe Some("https://example.com/image.jpg")
  }

  it should "parse minimal JSON response with only required fields" in {
    val json = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj(
          "title" -> ujson.Str("Minimal Result"),
          "url"   -> ujson.Str("https://minimal.com")
        )
      )
    )

    val query  = "minimal query"
    val result = ExaSearchTool.parseResponse(json, query)

    result.query shouldBe "minimal query"
    result.requestId shouldBe None
    result.searchType shouldBe None
    (result.results should have).length(1)

    val firstResult = result.results.head
    firstResult.title shouldBe "Minimal Result"
    firstResult.url shouldBe "https://minimal.com"
    firstResult.id shouldBe None
    firstResult.publishedDate shouldBe None
    firstResult.author shouldBe None
    firstResult.text shouldBe None
  }

  it should "parse empty results array" in {
    val json = ujson.Obj(
      "requestId"  -> ujson.Str("req-empty-123"),
      "searchType" -> ujson.Str("auto"),
      "results"    -> ujson.Arr()
    )

    val query  = "no results query"
    val result = ExaSearchTool.parseResponse(json, query)

    result.query shouldBe "no results query"
    result.requestId shouldBe Some("req-empty-123")
    result.searchType shouldBe Some("auto")
    result.results shouldBe List.empty
  }

  it should "parse response with multiple results" in {
    val json = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj(
          "title" -> ujson.Str("First Result"),
          "url"   -> ujson.Str("https://first.com"),
          "text"  -> ujson.Str("First result text")
        ),
        ujson.Obj(
          "title" -> ujson.Str("Second Result"),
          "url"   -> ujson.Str("https://second.com"),
          "text"  -> ujson.Str("Second result text")
        )
      )
    )

    val query  = "multi result query"
    val result = ExaSearchTool.parseResponse(json, query)

    result.query shouldBe "multi result query"
    (result.results should have).length(2)
    result.results(0).title shouldBe "First Result"
    result.results(1).title shouldBe "Second Result"
  }

  it should "parse response with nested subPages" in {
    val json = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj(
          "title" -> ujson.Str("Parent Page"),
          "url"   -> ujson.Str("https://parent.com"),
          "subPages" -> ujson.Arr(
            ujson.Obj(
              "title" -> ujson.Str("SubPage 1"),
              "url"   -> ujson.Str("https://parent.com/sub1"),
              "text"  -> ujson.Str("SubPage 1 content")
            )
          )
        )
      )
    )

    val query  = "nested pages query"
    val result = ExaSearchTool.parseResponse(json, query)

    result.query shouldBe "nested pages query"
    (result.results should have).length(1)

    val parentResult = result.results.head
    parentResult.title shouldBe "Parent Page"
    parentResult.subPages shouldBe defined
    (parentResult.subPages.get should have).length(1)
    parentResult.subPages.get(0).title shouldBe "SubPage 1"
    parentResult.subPages.get(0).text shouldBe Some("SubPage 1 content")
  }

  "buildRequestBody" should "build request with default config" in {
    val query  = "test query"
    val config = ExaSearchConfig()
    val body   = ExaSearchTool.buildRequestBody(query, config)

    body.obj("query").str shouldBe "test query"
    body.obj("type").str shouldBe "auto"
    body.obj("numResults").num shouldBe 10
    body.obj("contents").obj("text").obj("maxCharacters").num shouldBe 500
    body.obj("contents").obj("maxAgeHours").num shouldBe 1
    body.obj.contains("category") shouldBe false
    body.obj.contains("userLocation") shouldBe false
    body.obj.contains("additionalQueries") shouldBe false
  }

  it should "build request with all optional parameters" in {
    val query = "comprehensive query"
    val config = ExaSearchConfig(
      numResults = 20,
      searchType = SearchType.Neural,
      maxCharacters = 2000,
      maxAgeHours = 48,
      category = Some(Category.ResearchPaper),
      additionalQueries = Some(List("query1", "query2")),
      userLocation = Some("New York, NY"),
      livecrawlTimeout = Some(5000)
    )
    val body = ExaSearchTool.buildRequestBody(query, config)

    body.obj("query").str shouldBe "comprehensive query"
    body.obj("type").str shouldBe "neural"
    body.obj("numResults").num shouldBe 20
    body.obj("contents").obj("text").obj("maxCharacters").num shouldBe 2000
    body.obj("contents").obj("maxAgeHours").num shouldBe 48
    body.obj("contents").obj("livecrawlTimeout").num shouldBe 5000
    body.obj("category").str shouldBe "research paper"
    body.obj("userLocation").str shouldBe "New York, NY"
    (body.obj("additionalQueries").arr should have).length(2)
    body.obj("additionalQueries").arr(0).str shouldBe "query1"
    body.obj("additionalQueries").arr(1).str shouldBe "query2"
  }

  it should "merge extraParams into request body" in {
    val query = "custom query"
    val config = ExaSearchConfig(
      extraParams = Map(
        "customField"  -> ujson.Str("customValue"),
        "numericField" -> ujson.Num(42)
      )
    )
    val body = ExaSearchTool.buildRequestBody(query, config)

    body.obj("query").str shouldBe "custom query"
    body.obj("customField").str shouldBe "customValue"
    body.obj("numericField").num shouldBe 42
  }

  it should "merge extraParams contents object correctly" in {
    val query = "contents merge query"
    val config = ExaSearchConfig(
      maxCharacters = 1000,
      extraParams = Map(
        "contents" -> ujson.Obj(
          "highlights" -> ujson.Obj("numSentences" -> ujson.Num(3)),
          "summary"    -> ujson.Bool(true)
        )
      )
    )
    val body = ExaSearchTool.buildRequestBody(query, config)

    body.obj("query").str shouldBe "contents merge query"
    body.obj("contents").obj("text").obj("maxCharacters").num shouldBe 1000
    body.obj("contents").obj("maxAgeHours").num shouldBe 1
    body.obj("contents").obj("highlights").obj("numSentences").num shouldBe 3
    body.obj("contents").obj("summary").bool shouldBe true
  }

  "Serialization" should "serialize ExaResult and ExaSearchResult to JSON" in {
    val exaResult = ExaResult(
      title = "Test Article",
      url = "https://example.com/article",
      id = Some("test-id"),
      publishedDate = Some("2024-01-15"),
      author = Some("John Doe"),
      text = Some("Article content"),
      highlights = Some(List("highlight1", "highlight2")),
      highlightScores = Some(List(0.9, 0.8)),
      summary = Some("Article summary"),
      favicon = Some("https://example.com/favicon.ico"),
      image = Some("https://example.com/image.jpg")
    )

    val searchResult = ExaSearchResult(
      query = "test query",
      results = List(exaResult),
      requestId = Some("req-123"),
      searchType = Some("neural")
    )

    // Serialize to JSON
    val resultJson       = write(exaResult)
    val searchResultJson = write(searchResult)

    // Verify JSON contains expected fields
    resultJson should include("Test Article")
    resultJson should include("https://example.com/article")
    resultJson should include("test-id")

    searchResultJson should include("test query")
    searchResultJson should include("req-123")
    searchResultJson should include("neural")
  }

  "Deserialization" should "deserialize JSON to ExaResult and ExaSearchResult" in {
    val resultJson = """{
      "title": "Deserialized Article",
      "url": "https://example.com/deserialized",
      "id": "deser-id",
      "publishedDate": "2024-02-01",
      "author": "Jane Smith",
      "text": "Deserialized content",
      "highlights": ["highlight1", "highlight2"],
      "highlightScores": [0.95, 0.85],
      "summary": "Deserialized summary",
      "favicon": "https://example.com/fav.ico",
      "image": "https://example.com/img.jpg"
    }"""

    val searchResultJson = """{
      "query": "deserialization test",
      "results": [
        {
          "title": "Result 1",
          "url": "https://example.com/result1",
          "id": "r1",
          "text": "Result 1 text"
        },
        {
          "title": "Result 2",
          "url": "https://example.com/result2",
          "id": "r2",
          "text": "Result 2 text"
        }
      ],
      "requestId": "req-deser-456",
      "searchType": "auto"
    }"""

    // Deserialize from JSON
    val exaResult    = read[ExaResult](resultJson)
    val searchResult = read[ExaSearchResult](searchResultJson)

    // Verify ExaResult fields
    exaResult.title shouldBe "Deserialized Article"
    exaResult.url shouldBe "https://example.com/deserialized"
    exaResult.id shouldBe Some("deser-id")
    exaResult.publishedDate shouldBe Some("2024-02-01")
    exaResult.author shouldBe Some("Jane Smith")
    exaResult.text shouldBe Some("Deserialized content")
    exaResult.highlights shouldBe Some(List("highlight1", "highlight2"))
    exaResult.highlightScores shouldBe Some(List(0.95, 0.85))
    exaResult.summary shouldBe Some("Deserialized summary")

    // Verify ExaSearchResult fields
    searchResult.query shouldBe "deserialization test"
    searchResult.requestId shouldBe Some("req-deser-456")
    searchResult.searchType shouldBe Some("auto")
    (searchResult.results should have).length(2)
    searchResult.results(0).title shouldBe "Result 1"
    searchResult.results(0).id shouldBe Some("r1")
    searchResult.results(1).title shouldBe "Result 2"
    searchResult.results(1).id shouldBe Some("r2")
  }
}
