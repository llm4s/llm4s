package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * The sources a model cites (#1216), as OpenAI and OpenRouter report them: `url_citation`
 * annotations on the assistant message, `{"type":"url_citation","url_citation":{"url", "title",
 * "content", "start_index", "end_index"}}`. Search models reach them without any request option
 * (OpenAI's `gpt-5-search-api`, OpenRouter's `:online` suffix). Streamed chunks report none, and
 * no citation is ever made up: a malformed annotation is dropped, never a failed completion.
 */
class OpenAICompatibleCitationsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def clientFor(baseUrl: String = "http://localhost:1/v1") = new OpenAICompatibleClient(
    OpenAICompatibleClient.settings(OpenAICompatibleConfig("gpt-5-search-api", baseUrl, None)),
    OpenAICompatibleDialect.Standard
  )

  private val client = clientFor()

  private def reply(annotations: String, content: String = "Scala 3 was released in 2021."): ujson.Value =
    ujson.read(s"""{"id":"c1","created":1,"model":"m","choices":[{"index":0,"message":{
        |"role":"assistant","content":${ujson.write(content)}$annotations},"finish_reason":"stop"}]}""".stripMargin)

  private def annotated(urlCitations: String*): ujson.Value =
    reply(s""","annotations":[${urlCitations.mkString(",")}]""")

  private def urlCitation(body: String) = s"""{"type":"url_citation","url_citation":{$body}}"""

  private val full =
    urlCitation(
      """"url":"https://example.com/scala3","title":"Scala 3","content":"Scala 3 was released.","start_index":0,"end_index":28"""
    )

  "a completion" should "carry the url citations of the message, with every field the provider sent" in {
    val completion = client.parseCompletion(annotated(full))

    completion.citations shouldBe List(
      Citation(
        url = "https://example.com/scala3",
        title = Some("Scala 3"),
        citedText = Some("Scala 3 was released."),
        startIndex = Some(0),
        endIndex = Some(28)
      )
    )
    completion.content shouldBe "Scala 3 was released in 2021."
  }

  it should "keep several citations in the order the provider sent them" in {
    val a = urlCitation(""""url":"https://a.example","title":"A","start_index":0,"end_index":5""")
    val b = urlCitation(""""url":"https://b.example","title":"B","start_index":6,"end_index":9""")
    val c = urlCitation(""""url":"https://c.example"""")

    client.parseCompletion(annotated(a, b, c)).citations.map(_.url) shouldBe
      List("https://a.example", "https://b.example", "https://c.example")
  }

  it should "report only what was sent, leaving the rest unset" in {
    val completion = client.parseCompletion(annotated(urlCitation(""""url":"https://only.example"""")))

    completion.citations shouldBe List(Citation("https://only.example"))
    completion.citations.head.hasSpan shouldBe false
  }

  it should "have no citations when the message has no annotations, or an empty or null list" in {
    client.parseCompletion(reply("")).citations shouldBe Nil
    client.parseCompletion(reply(""","annotations":[]""")).citations shouldBe Nil
    client.parseCompletion(reply(""","annotations":null""")).hasCitations shouldBe false
  }

  it should "skip an annotation of another type, and keep the url citations around it" in {
    val other = """{"type":"file_citation","file_citation":{"file_id":"f","quote":"q"}}"""

    client.parseCompletion(annotated(other, full, other)).citations.map(_.url) shouldBe
      List("https://example.com/scala3")
  }

  it should "go by the annotation's type, not by whether it happens to carry a url_citation body" in {
    val wrongType = """{"type":"file_citation","url_citation":{"url":"https://wrong-type.example"}}"""
    val noType    = """{"url_citation":{"url":"https://no-type.example"}}"""

    client.parseCompletion(annotated(wrongType, noType, full)).citations.map(_.url) shouldBe
      List("https://example.com/scala3")
  }

  it should "read an index that is negative, fractional or too large for an Int as absent" in {
    val bad = urlCitation(
      """"url":"https://idx.example","start_index":-1,"end_index":3000000000"""
    )
    val fractional = urlCitation(""""url":"https://frac.example","start_index":1.5,"end_index":2""")

    client.parseCompletion(annotated(bad, fractional)).citations shouldBe List(
      Citation("https://idx.example"),
      Citation("https://frac.example", endIndex = Some(2))
    )
  }

  it should "never make a citation up: one with no url is dropped" in {
    val noUrl     = urlCitation(""""title":"No url","start_index":0,"end_index":1""")
    val emptyUrl  = urlCitation(""""url":"","title":"Empty"""")
    val nullUrl   = urlCitation(""""url":null,"title":"Null"""")
    val badCited  = """{"type":"url_citation"}"""
    val nonObject = """{"type":"url_citation","url_citation":"https://not-an-object.example"}"""

    client.parseCompletion(annotated(noUrl, emptyUrl, nullUrl, badCited, nonObject, full)).citations.map(_.url) shouldBe
      List("https://example.com/scala3")
  }

  it should "read a field of the wrong type as absent, not as a failure or a guess" in {
    val odd = urlCitation(
      """"url":"https://odd.example","title":42,"content":["x"],"start_index":"0","end_index":3.5"""
    )

    client.parseCompletion(annotated(odd)).citations shouldBe List(Citation("https://odd.example"))
  }

  it should "survive annotations that are not a list, or entries that are not objects" in {
    client.parseCompletion(reply(""","annotations":"nope"""")).citations shouldBe Nil
    client.parseCompletion(reply(""","annotations":{"type":"url_citation"}""")).citations shouldBe Nil

    val mixed = client.parseCompletion(reply(s""","annotations":[7,null,"x",$full,[1]]"""))
    mixed.citations.map(_.url) shouldBe List("https://example.com/scala3")
    mixed.content shouldBe "Scala 3 was released in 2021."
  }

  it should "keep citations from changing the message the conversation continues with" in {
    val withCitations    = client.parseCompletion(annotated(full))
    val withoutCitations = client.parseCompletion(reply(""))

    withCitations.message shouldBe withoutCitations.message
    withCitations.withCitations(Nil) shouldBe withoutCitations.withId("c1")
  }

  "complete" should "return the citations over the wire" in withServer("/chat/completions") { exchange =>
    sendJsonResponse(exchange, 200, ujson.write(annotated(full)))
  } { baseUrl =>
    val completion = clientFor(baseUrl).complete(Conversation(Seq(UserMessage("When?"))), CompletionOptions()).value

    completion.citations.map(c => (c.url, c.title)) shouldBe List(("https://example.com/scala3", Some("Scala 3")))
    completion.content shouldBe "Scala 3 was released in 2021."
  }

  it should "return no citations for a reply that carries none" in withServer("/chat/completions") { exchange =>
    sendJsonResponse(exchange, 200, openAICompletion("plain"))
  } { baseUrl =>
    val completion = clientFor(baseUrl).complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions()).value

    completion.citations shouldBe Nil
    completion.content shouldBe "plain"
  }

  "a streamed completion" should "carry no citations, and not fail when the stream names some" in {
    val events = Seq(
      """{"id":"s","choices":[{"index":0,"delta":{"content":"Scala 3"},"finish_reason":null}]}""",
      """{"id":"s","choices":[{"index":0,"delta":{"annotations":[""" + full +
        """]},"finish_reason":"stop"}]}"""
    )
    val sse = new ByteArrayInputStream(
      (events.map(e => s"data: $e").mkString("\n\n") + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)
    )

    val completion = client.consumeStream(200, sse, new StringBuilder, _ => ()).value

    completion.content shouldBe "Scala 3"
    completion.citations shouldBe Nil
  }
}
