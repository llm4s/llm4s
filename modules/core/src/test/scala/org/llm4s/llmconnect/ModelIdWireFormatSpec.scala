package org.llm4s.llmconnect

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.model.ModelMetadata
import org.llm4s.trace.TraceEvent
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

import java.time.Instant

/**
 * Pins every wire format that carries a model or provider id (#2822): typing the ids as `ModelName` and `ProviderId`
 * must not change a byte of what is written, and what was written before must still read.
 */
class ModelIdWireFormatSpec extends AnyFlatSpec with Matchers {

  private val at = Instant.parse("2026-01-02T03:04:05Z")

  "ModelMetadata JSON" should "write its model and provider ids as strings" in {
    write(ModelMetadata(modelId = ModelName("gpt-4o"), provider = "openai")) shouldBe
      """{"modelId":"gpt-4o","provider":"openai"}"""
  }

  it should "read what it wrote" in {
    val metadata =
      ModelMetadata(modelId = ModelName("claude-sonnet-4-5"), provider = "anthropic").withMaxInputTokens(200000)
    read[ModelMetadata](write(metadata)) shouldBe metadata
    read[ModelMetadata]("""{"modelId":"gpt-4o","provider":"openai"}""") shouldBe
      ModelMetadata(modelId = ModelName("gpt-4o"), provider = "openai")
  }

  "A ModelName" should "be written as its string, and as an object key in a map keyed by it" in {
    write(ModelName("gpt-4o")) shouldBe "\"gpt-4o\""
    write(Map(ModelName("gpt-4o") -> 1)) shouldBe """{"gpt-4o":1}"""
    read[Map[ModelName, Int]]("""{"gpt-4o":1}""") shouldBe Map(ModelName("gpt-4o") -> 1)
  }

  "The provider exchange log" should "write the model as a string, and an absent one as empty" in {
    val exchange = ProviderExchange(
      exchangeId = "ex-1",
      provider = ProviderId("openai"),
      startedAt = at,
      completedAt = at,
      outcome = ProviderExchangeOutcome.Success,
      requestBody = "{}",
      model = Some(ModelName("gpt-4o"))
    )
    ProviderExchangeJson.toJson(exchange)("model") shouldBe ujson.Str("gpt-4o")
    ProviderExchangeJson.toJson(exchange)("provider") shouldBe ujson.Str("openai")
    ProviderExchangeJson.toJson(exchange.withModel(None))("model") shouldBe ujson.Str("")
  }

  "Trace events" should "write their model ids as strings" in {
    val usage = TokenUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3)
    val completion = TraceEvent.CompletionReceived(
      id = "c-1",
      model = ModelName("gpt-4o"),
      toolCalls = 0,
      content = "hi",
      timestamp = at,
      responseModel = Some(ModelName("gpt-4o-2024-08-06"))
    )
    ujson.write(completion.toJson) shouldBe
      """{"event_type":"completion_received","timestamp":"2026-01-02T03:04:05Z","completion_id":"c-1",""" +
      """"model":"gpt-4o","tool_calls":0,"content":"hi","response_model":"gpt-4o-2024-08-06"}"""

    TraceEvent.TokenUsageRecorded(usage, ModelName("gpt-4o"), "chat", at).toJson("model") shouldBe ujson.Str("gpt-4o")
    TraceEvent
      .ErrorOccurred("boom", "E", "ctx", timestamp = at, model = Some(ModelName("gpt-4o")))
      .toJson("model") shouldBe ujson.Str("gpt-4o")
  }
}
