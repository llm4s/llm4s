package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.Citation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class GeminiGroundingSpec extends AnyFlatSpec with Matchers {

  private def candidate(metadata: String): ujson.Value =
    ujson.read(s"""{"content":{"parts":[{"text":"answer"}]},"groundingMetadata":$metadata}""")

  "GeminiGrounding.citations" should "read Google Search sources as url and title, without indices" in {
    val c = candidate(
      """{"webSearchQueries":["q"],
        | "groundingChunks":[
        |   {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/a","title":"example.com"}},
        |   {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/b","title":"other.org"}}],
        | "groundingSupports":[{"segment":{"startIndex":0,"endIndex":6,"text":"answer"},"groundingChunkIndices":[0,1]}]
        |}""".stripMargin
    )
    GeminiGrounding.citations(c) shouldBe List(
      Citation(url = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/a", title = Some("example.com")),
      Citation(url = "https://vertexaisearch.cloud.google.com/grounding-api-redirect/b", title = Some("other.org"))
    )
    GeminiGrounding.citations(c).exists(_.hasSpan) shouldBe false
  }

  it should "read a retrieved context's text as the cited text, and Maps sources" in {
    val c = candidate(
      """{"groundingChunks":[
        |  {"retrievedContext":{"uri":"gs://bucket/doc.pdf","title":"Doc","text":"the passage"}},
        |  {"maps":{"uri":"https://maps.google.com/?cid=1","title":"Cafe"}}]}""".stripMargin
    )
    GeminiGrounding.citations(c) shouldBe List(
      Citation(url = "gs://bucket/doc.pdf", title = Some("Doc"), citedText = Some("the passage")),
      Citation(url = "https://maps.google.com/?cid=1", title = Some("Cafe"))
    )
  }

  it should "skip a chunk without a uri, drop blank titles and report a repeated source once, in order" in {
    val c = candidate(
      """{"groundingChunks":[
        |  {"web":{"title":"no uri"}},
        |  {"web":{"uri":"https://b","title":"  "}},
        |  {"unknown":{"uri":"https://ignored"}},
        |  {"web":{"uri":"https://a","title":"A"}},
        |  {"web":{"uri":"https://b","title":"  "}},
        |  "not an object"]}""".stripMargin
    )
    GeminiGrounding.citations(c) shouldBe List(
      Citation(url = "https://b"),
      Citation(url = "https://a", title = Some("A"))
    )
  }

  it should "return nothing when there is no grounding metadata or it cannot be read" in {
    GeminiGrounding.citations(ujson.read("""{"content":{"parts":[{"text":"x"}]}}""")) shouldBe Nil
    GeminiGrounding.citations(candidate("""{"webSearchQueries":["q"]}""")) shouldBe Nil
    GeminiGrounding.citations(candidate("""{"groundingChunks":"oops"}""")) shouldBe Nil
    GeminiGrounding.citations(candidate("null")) shouldBe Nil
  }
}
