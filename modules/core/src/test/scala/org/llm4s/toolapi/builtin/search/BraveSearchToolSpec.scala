package org.llm4s.toolapi.builtin.search

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.{ BraveSearchToolConfig, Llm4sConfig }
import org.llm4s.http.{ FailingHttpClient, HttpResponse, MockHttpClient }

class BraveSearchToolSpec extends AnyFlatSpec with Matchers {

  private def testToolConfig = BraveSearchToolConfig(
    apiKey = "test-api-key",
    apiUrl = "https://api.search.brave.com/res/v1",
    count = 5,
    safeSearch = "moderate"
  )
  "BraveSearchConfig" should "initialize with valid default parameters" in {
    val config = BraveSearchConfig()

    config.timeoutMs shouldBe 10000
    config.count shouldBe 5
    config.extraParams shouldBe Map.empty
    config.safeSearch shouldBe SafeSearch.Strict
  }
  it should "allow overriding defaults with custom values" in {
    val config = BraveSearchConfig(
      timeoutMs = 5000,
      count = 5,
      extraParams = Map(
        "some key" -> "value"
      ),
      safeSearch = SafeSearch.Moderate
    )
    config.timeoutMs shouldBe 5000
    config.count shouldBe 5
    config.extraParams shouldBe Map(
      "some key" -> ujson.Str("value")
    )
    config.safeSearch shouldBe SafeSearch.Moderate
  }
  "SafeSearch" should "correctly parse valid configuration strings into enum members" in {
    val safeSearch = SafeSearch.fromString("moderate")
    safeSearch shouldBe SafeSearch.Moderate
  }
  it should "fall back to Moderate when encountering unrecognized configuration strings" in {
    val safeSearch = SafeSearch.fromString("invalid value")
    safeSearch shouldBe SafeSearch.Moderate
  }

  "BraveSearchTool" should "expose accurate name and description for each search category" in {
    val braveConfigResult = Llm4sConfig.loadBraveSearchTool()

    braveConfigResult match {
      case Right(config) =>
        def testCategory[R: upickle.default.ReadWriter](
          category: BraveSearchCategory[R],
          expectedName: String,
          expectedDescription: String
        ): Unit = {
          val tool = BraveSearchTool.create(config, category)
          tool.name shouldBe expectedName
          tool.description shouldBe expectedDescription
        }

        testCategory(BraveSearchCategory.Web, "brave_web_search", "Searches the web using Brave Search")
        testCategory(BraveSearchCategory.Image, "brave_image_search", "Searches for images using Brave Search")
        testCategory(BraveSearchCategory.Video, "brave_video_search", "Searches for videos using Brave Search")
        testCategory(BraveSearchCategory.News, "brave_news_search", "Searches for news using Brave Search")

      case Left(err) =>
        fail(s"Failed to load Brave configuration: $err")
    }
  }

  "BraveSearchCategory" should "correctly map SafeSearch levels to API parameter strings" in {
    // Web
    BraveSearchCategory.Web.mapSafeSearch(SafeSearch.Off) shouldBe "off"
    BraveSearchCategory.Web.mapSafeSearch(SafeSearch.Moderate) shouldBe "moderate"
    BraveSearchCategory.Web.mapSafeSearch(SafeSearch.Strict) shouldBe "strict"

    // Image (Moderate -> Strict fallback)
    BraveSearchCategory.Image.mapSafeSearch(SafeSearch.Off) shouldBe "off"
    BraveSearchCategory.Image.mapSafeSearch(SafeSearch.Moderate) shouldBe "strict"
    BraveSearchCategory.Image.mapSafeSearch(SafeSearch.Strict) shouldBe "strict"

    // Video
    BraveSearchCategory.Video.mapSafeSearch(SafeSearch.Off) shouldBe "off"
    BraveSearchCategory.Video.mapSafeSearch(SafeSearch.Moderate) shouldBe "moderate"
    BraveSearchCategory.Video.mapSafeSearch(SafeSearch.Strict) shouldBe "strict"

    // News
    BraveSearchCategory.News.mapSafeSearch(SafeSearch.Off) shouldBe "off"
    BraveSearchCategory.News.mapSafeSearch(SafeSearch.Moderate) shouldBe "moderate"
    BraveSearchCategory.News.mapSafeSearch(SafeSearch.Strict) shouldBe "strict"
  }

  def verifyRoundTrip[T: upickle.default.ReadWriter](obj: T): Unit = {
    import upickle.default._
    val json    = write(obj)
    val backObj = read[T](json)
    backObj shouldBe obj
  }

  "Brave Result Models" should "support consistent round-trip JSON serialization and deserialization" in {
    // Web
    val webResult = BraveWebResult("title", "url", "desc")
    verifyRoundTrip(webResult)
    verifyRoundTrip(BraveWebSearchResult("query", List(webResult)))

    // Image
    val imageResult = BraveImageResult("title", "url", "thumb")
    verifyRoundTrip(imageResult)
    verifyRoundTrip(BraveImageSearchResult("query", List(imageResult)))

    // Video
    val videoResult = BraveVideoResult("title", "url", "desc")
    verifyRoundTrip(videoResult)
    verifyRoundTrip(BraveVideoSearchResult("query", List(videoResult)))

    // News
    val newsResult = BraveNewsResult("title", "url", "desc")
    verifyRoundTrip(newsResult)
    verifyRoundTrip(BraveNewsSearchResult("query", List(newsResult)))
  }

  "BraveSearchCategory.parseResults" should "correctly parse Web results" in {
    val json = ujson.Obj(
      "web" -> ujson.Obj(
        "results" -> ujson.Arr(
          ujson.Obj(
            "title"       -> "Web Title",
            "url"         -> "https://example.com",
            "description" -> "Web Description"
          )
        )
      )
    )
    val result = BraveSearchCategory.Web.parseResults(json, "scala")
    result.query shouldBe "scala"
    result.results should have size 1
    result.results.head shouldBe BraveWebResult("Web Title", "https://example.com", "Web Description")
  }

  it should "correctly parse Image results" in {
    val json = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj(
          "title" -> "Image Title",
          "url"   -> "https://example.com/image.png",
          "thumbnail" -> ujson.Obj(
            "src" -> "https://example.com/thumb.png"
          )
        )
      )
    )
    val result = BraveSearchCategory.Image.parseResults(json, "scala logo")
    result.query shouldBe "scala logo"
    result.results should have size 1
    result.results.head shouldBe BraveImageResult(
      "Image Title",
      "https://example.com/image.png",
      "https://example.com/thumb.png"
    )
  }

  it should "correctly parse Video results" in {
    val json = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj(
          "title"       -> "Video Title",
          "url"         -> "https://example.com/video",
          "description" -> "Video Description"
        )
      )
    )
    val result = BraveSearchCategory.Video.parseResults(json, "scala tutorial")
    result.query shouldBe "scala tutorial"
    result.results should have size 1
    result.results.head shouldBe BraveVideoResult(
      "Video Title",
      "https://example.com/video",
      "Video Description"
    )
  }

  it should "correctly parse News results" in {
    val json = ujson.Obj(
      "results" -> ujson.Arr(
        ujson.Obj(
          "title"       -> "News Title",
          "url"         -> "https://example.com/news",
          "description" -> "News Description"
        )
      )
    )
    val result = BraveSearchCategory.News.parseResults(json, "scala news")
    result.query shouldBe "scala news"
    result.results should have size 1
    result.results.head shouldBe BraveNewsResult(
      "News Title",
      "https://example.com/news",
      "News Description"
    )
  }

  it should "handle missing or empty results gracefully" in {
    val emptyJson = ujson.Obj("results" -> ujson.Arr())
    val webJson   = ujson.Obj("web" -> ujson.Obj("results" -> ujson.Arr()))

    BraveSearchCategory.Web.parseResults(webJson, "q").results shouldBe empty
    BraveSearchCategory.Image.parseResults(emptyJson, "q").results shouldBe empty
    BraveSearchCategory.Video.parseResults(emptyJson, "q").results shouldBe empty
    BraveSearchCategory.News.parseResults(emptyJson, "q").results shouldBe empty
  }

  // --- Unit tests for BraveSearchTool.search() with mocked HTTP ---

  "BraveSearchTool.search" should "return parsed web results on 200 response" in {
    val body = ujson
      .Obj(
        "web" -> ujson.Obj(
          "results" -> ujson.Arr(
            ujson.Obj("title" -> "Scala", "url" -> "https://scala-lang.org", "description" -> "The Scala language")
          )
        )
      )
      .render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    val result = BraveSearchTool.search(
      "scala",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Web,
      mockClient,
      () => ()
    )

    result.isRight shouldBe true
    val searchResult = result.getOrElse(fail("Expected Right"))
    searchResult.query shouldBe "scala"
    searchResult.results should have size 1
    searchResult.results.head.title shouldBe "Scala"
  }

  it should "return parsed image results on 200 response" in {
    val body = ujson
      .Obj(
        "results" -> ujson.Arr(
          ujson.Obj(
            "title"     -> "Logo",
            "url"       -> "https://example.com/logo.png",
            "thumbnail" -> ujson.Obj("src" -> "https://example.com/thumb.png")
          )
        )
      )
      .render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    val result = BraveSearchTool.search(
      "scala logo",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Image,
      mockClient,
      () => ()
    )

    result.isRight shouldBe true
    val searchResult = result.getOrElse(fail("Expected Right"))
    searchResult.results should have size 1
    searchResult.results.head.thumbnail shouldBe "https://example.com/thumb.png"
  }

  it should "return parsed video results on 200 response" in {
    val body = ujson
      .Obj(
        "results" -> ujson.Arr(
          ujson.Obj("title" -> "Tutorial", "url" -> "https://example.com/vid", "description" -> "A tutorial")
        )
      )
      .render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    val result = BraveSearchTool.search(
      "scala tutorial",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Video,
      mockClient,
      () => ()
    )

    result.isRight shouldBe true
    val searchResult = result.getOrElse(fail("Expected Right"))
    searchResult.results should have size 1
    searchResult.results.head.description shouldBe "A tutorial"
  }

  it should "return parsed news results on 200 response" in {
    val body = ujson
      .Obj(
        "results" -> ujson.Arr(
          ujson.Obj("title" -> "Release", "url" -> "https://example.com/news", "description" -> "Scala 3 released")
        )
      )
      .render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    val result = BraveSearchTool.search(
      "scala news",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.News,
      mockClient,
      () => ()
    )

    result.isRight shouldBe true
    val searchResult = result.getOrElse(fail("Expected Right"))
    searchResult.results should have size 1
    searchResult.results.head.title shouldBe "Release"
  }

  it should "return error on non-200 status code" in {
    val mockClient = new MockHttpClient(HttpResponse(403, """{"error":"Forbidden"}"""))

    val result = BraveSearchTool.search(
      "test",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Web,
      mockClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("403")
  }

  it should "return sanitized error on invalid JSON response" in {
    val mockClient = new MockHttpClient(HttpResponse(200, "not valid json {{{"))

    val result = BraveSearchTool.search(
      "test",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Web,
      mockClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should (include("parse").or(include("invalid")))
  }

  it should "send correct headers including API key" in {
    val body       = ujson.Obj("web" -> ujson.Obj("results" -> ujson.Arr())).render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    BraveSearchTool.search(
      "test",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Web,
      mockClient,
      () => ()
    )

    mockClient.lastHeaders.flatMap(_.get("X-Subscription-Token")) shouldBe Some("test-api-key")
    mockClient.lastHeaders.flatMap(_.get("Accept")) shouldBe Some("application/json")
    mockClient.lastHeaders.flatMap(_.get("User-Agent")) shouldBe Some("llm4s-brave-search/1.0")
  }

  it should "send correct query params" in {
    val body       = ujson.Obj("web" -> ujson.Obj("results" -> ujson.Arr())).render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    BraveSearchTool.search(
      "scala lang",
      BraveSearchConfig(count = 3, safeSearch = SafeSearch.Strict),
      testToolConfig,
      BraveSearchCategory.Web,
      mockClient,
      () => ()
    )

    mockClient.lastParams.flatMap(_.get("q")) shouldBe Some("scala lang")
    mockClient.lastParams.flatMap(_.get("count")) shouldBe Some("3")
    mockClient.lastParams.flatMap(_.get("safesearch")) shouldBe Some("strict")
  }

  it should "build correct URL for each category" in {
    val body       = ujson.Obj("web" -> ujson.Obj("results" -> ujson.Arr())).render()
    val mockClient = new MockHttpClient(HttpResponse(200, body))

    BraveSearchTool.search("q", BraveSearchConfig(), testToolConfig, BraveSearchCategory.Web, mockClient, () => ())
    mockClient.lastUrl shouldBe Some("https://api.search.brave.com/res/v1/web/search")

    val imgBody       = ujson.Obj("results" -> ujson.Arr()).render()
    val imgMockClient = new MockHttpClient(HttpResponse(200, imgBody))
    BraveSearchTool.search("q", BraveSearchConfig(), testToolConfig, BraveSearchCategory.Image, imgMockClient, () => ())
    imgMockClient.lastUrl shouldBe Some("https://api.search.brave.com/res/v1/images/search")
  }

  it should "return sanitized error on timeout" in {
    val failingClient = new FailingHttpClient(new java.net.http.HttpTimeoutException("timed out"))

    val result = BraveSearchTool.search(
      "test",
      BraveSearchConfig(timeoutMs = 5000),
      testToolConfig,
      BraveSearchCategory.Web,
      failingClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("timed out")
    error should include("5000ms")
  }

  it should "return sanitized error on unknown host" in {
    val failingClient = new FailingHttpClient(new java.net.UnknownHostException("no such host"))

    val result = BraveSearchTool.search(
      "test",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Web,
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

    val result = BraveSearchTool.search(
      "test",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Web,
      failingClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("Failed to connect")
    error should include("temporarily unavailable")
  }

  it should "return generic sanitized error on unexpected exception" in {
    val failingClient = new FailingHttpClient(new RuntimeException("unexpected internal error"))

    val result = BraveSearchTool.search(
      "test",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Web,
      failingClient,
      () => ()
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should include("network error")
    (error should not).include("unexpected internal error")
  }

  it should "call restoreInterrupt and return sanitized error on InterruptedException" in {
    var interruptRestored = false
    val mockRestore       = () => interruptRestored = true
    val failingClient     = new FailingHttpClient(new InterruptedException("interrupted"))

    val result = BraveSearchTool.search(
      "test",
      BraveSearchConfig(),
      testToolConfig,
      BraveSearchCategory.Web,
      failingClient,
      mockRestore
    )

    result.isLeft shouldBe true
    val error = result.swap.getOrElse("")
    error should (include("cancelled").or(include("interrupted")))
    interruptRestored shouldBe true
  }
}
