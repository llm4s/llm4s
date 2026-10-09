package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ Citation, CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, completion => completionOf, stream, transport }
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The sources a search model cites (#1216): `url_citation` annotations on the assistant message,
 * which `openai-java` models as `ChatCompletionMessage.Annotation.UrlCitation` with a `url`, a
 * `title` and the `start_index` / `end_index` of the URL citation in the message (where the inline
 * citation sits, not a supported-claim span). OpenAI's Chat Completions search models
 * (`gpt-5-search-api`; the `*-search-preview` models were retired on 2026-07-23) return them
 * without any request option. A citation is read leniently, as the rest of
 * the response is: one that cannot be read is dropped and never fails a completion whose answer
 * arrived, and none is made up.
 */
final class OpenAIClientCitationsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val model = "gpt-5-search-api"

  private val config = OpenAIConfig
    .fromValues(modelName = model, apiKey = "test-api-key", organization = None, baseUrl = "https://example.invalid/v1")
    .value

  private def complete(annotations: String, content: String = "Scala 3 was released in 2021.") = {
    val response = completionOf(s"""{
        |"id":"chatcmpl-1","created":0,
        |"choices":[{"index":0,"message":{"role":"assistant","content":${ujson.write(content)}$annotations}}]
        |}""".stripMargin)
    OpenAIClient
      .forTest(model, transport(complete = _ => response), config)
      .complete(Conversation(Seq(UserMessage("When?"))), CompletionOptions())
      .value
  }

  private def annotated(urlCitations: String*) = complete(s""","annotations":[${urlCitations.mkString(",")}]""")

  private def urlCitation(body: String) = s"""{"type":"url_citation","url_citation":{$body}}"""

  private val full =
    urlCitation(""""url":"https://example.com/scala3","title":"Scala 3","start_index":0,"end_index":28""")

  "OpenAIClient.complete" should "carry the url citations of the message, with the fields OpenAI sends" in {
    val completion = annotated(full)

    completion.citations shouldBe List(
      Citation(url = "https://example.com/scala3", title = Some("Scala 3"), startIndex = Some(0), endIndex = Some(28))
    )
    completion.content shouldBe "Scala 3 was released in 2021."
  }

  it should "leave the cited text unset, because OpenAI does not return a passage with a citation" in {
    annotated(full).citations.map(_.citedText) shouldBe List(None)
  }

  it should "keep several citations in the order OpenAI sent them" in {
    val a = urlCitation(""""url":"https://a.example","title":"A","start_index":0,"end_index":5""")
    val b = urlCitation(""""url":"https://b.example","title":"B","start_index":6,"end_index":9""")

    annotated(a, b, full).citations.map(_.url) shouldBe
      List("https://a.example", "https://b.example", "https://example.com/scala3")
  }

  it should "have no citations when the message has no annotations, or an empty list" in {
    complete("").citations shouldBe Nil
    complete(""","annotations":[]""").citations shouldBe Nil
    complete("").hasCitations shouldBe false
  }

  it should "never make a citation up: an entry with no url is dropped, the others kept" in {
    val noUrl    = urlCitation(""""title":"No url","start_index":0,"end_index":1""")
    val emptyUrl = urlCitation(""""url":"","title":"Empty"""")

    annotated(noUrl, emptyUrl, full).citations.map(_.url) shouldBe List("https://example.com/scala3")
  }

  it should "read an index that is negative or too large for an Int as absent" in {
    val bad = urlCitation(""""url":"https://idx.example","title":"T","start_index":-1,"end_index":3000000000""")
    val ok  = urlCitation(""""url":"https://ok.example","start_index":0,"end_index":2147483647""")

    annotated(bad, ok).citations shouldBe List(
      Citation("https://idx.example", title = Some("T")),
      Citation("https://ok.example", startIndex = Some(0), endIndex = Some(Int.MaxValue))
    )
  }

  it should "read an annotation list the SDK cannot parse as no citations, and still return the answer" in {
    // one unreadable element makes openai-java treat the whole `annotations` field as unknown, so the
    // valid citation beside it is lost too: conservative, and the completion itself never fails
    val completion = complete(s""","annotations":[7,"x",null,$full]""")

    completion.content shouldBe "Scala 3 was released in 2021."
    completion.citations shouldBe Nil
  }

  it should "keep citations from changing the message the conversation continues with" in {
    annotated(full).message shouldBe complete("").message
  }

  it should "return them whichever provider the client serves, Azure and Requesty included" in {
    val response = completionOf(s"""{"id":"c","created":0,"choices":[{"index":0,"message":{
        |"role":"assistant","content":"ok","annotations":[$full]}}]}""".stripMargin)

    for (provider <- Seq(OpenAIProvider.id, AzureProvider.id, RequestyProvider.id)) {
      val client = OpenAIClient.forTest(model, transport(complete = _ => response), config, provider = provider)
      client
        .complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
        .value
        .citations
        .map(_.url) shouldBe List("https://example.com/scala3")
    }
  }

  "OpenAIClient.streamComplete" should "carry no citations: the SDK's streamed delta has no annotations" in {
    val content = chunk(
      """{"id":"c1","created":0,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}"""
    )
    val stop = chunk(
      """{"id":"c1","created":0,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
    )
    val client = OpenAIClient.forTest(model, transport(streaming = _ => stream(content, stop)), config)

    val completion = client.streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ()).value

    completion.content shouldBe "Hi"
    completion.citations shouldBe Nil
  }
}
