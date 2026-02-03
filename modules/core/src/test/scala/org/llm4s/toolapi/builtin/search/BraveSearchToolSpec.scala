package org.llm4s.toolapi.builtin.search

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.Llm4sConfig

class BraveSearchToolSpec extends AnyFlatSpec with Matchers {
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
}
