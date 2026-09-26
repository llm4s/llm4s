package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.ZaiConfig
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * What changed for Z.ai when it moved onto the shared client (#1132): the old `ZaiClient`
 * left the stream body open on an error status, and its "usage as an array" branch never
 * matched (the value was wrapped in an array before it was inspected).
 */
class ZaiClientDialectSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val client =
    new ZaiClient(ZaiConfig("k", "GLM-4.7", "http://localhost:1/api/paas/v4", 128000, 4096))

  "ZaiClient.streamComplete" should "close the response body on an error status" in {
    var closed = false
    val body = new ByteArrayInputStream("""{"error":"rate limited"}""".getBytes(StandardCharsets.UTF_8)) {
      override def close(): Unit = { closed = true; super.close() }
    }
    client.consumeStream(429, body, new StringBuilder, _ => ()).isLeft shouldBe true
    closed shouldBe true
  }

  "ZaiClient.complete" should "read usage given as an object or as a one-element array" in {
    def reply(usage: String) =
      ujson.read(
        s"""{"id":"z","created":1,"model":"GLM-4.7","choices":[{"message":{"content":"hi"}}],"usage":$usage}"""
      )

    val expected = Some(TokenUsage(1, 2, 3))
    client
      .parseCompletion(reply("""{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}"""))
      .usage shouldBe expected
    client
      .parseCompletion(reply("""[{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}]"""))
      .usage shouldBe expected
  }

  it should "read content given as a string or as an array of text parts" in {
    def content(value: String) =
      client
        .parseCompletion(
          ujson.read(s"""{"id":"z","created":1,"model":"m","choices":[{"message":{"content":$value}}]}""")
        )
        .content

    content("\"plain\"") shouldBe "plain"
    content("""[{"type":"text","text":"parts"}]""") shouldBe "parts"
    content("null") shouldBe ""
  }
}
