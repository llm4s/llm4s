package org.llm4s.llmconnect

import org.llm4s.config.DiscoveredModel
import org.llm4s.config.ProvidersConfigModel.ProviderId
import org.llm4s.types.ProviderModelTypes.ModelName
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.*

/** `ProviderExchange` and `DiscoveredModel` are growth-prone (#2356): built through `apply`, changed through `with*`. */
class ProviderExchangeSpec extends AnyFlatSpec with Matchers {

  private val started = Instant.parse("2026-01-01T00:00:00Z")

  private val exchange = ProviderExchange(
    exchangeId = "ex-1",
    provider = "openai",
    model = None,
    requestId = None,
    correlationId = None,
    startedAt = started,
    completedAt = started,
    duration = Duration.Zero,
    outcome = ProviderExchangeOutcome.Success,
    requestBody = "{}",
    responseBody = None,
    errorMessage = None
  )

  "ProviderExchange" should "change each field through its setter" in {
    val completed = started.plusMillis(150)
    val changed = exchange
      .withExchangeId("ex-2")
      .withProvider("anthropic")
      .withModel("claude")
      .withRequestId("req-1")
      .withCorrelationId("corr-1")
      .withStartedAt(started.plusMillis(1))
      .withCompletedAt(completed)
      .withDuration(150.millis)
      .withOutcome(ProviderExchangeOutcome.Error)
      .withRequestBody("""{"a":1}""")
      .withResponseBody("""{"b":2}""")
      .withErrorMessage("boom")

    changed shouldBe ProviderExchange(
      "ex-2",
      "anthropic",
      Some("claude"),
      Some("req-1"),
      Some("corr-1"),
      started.plusMillis(1),
      completed,
      150.millis,
      ProviderExchangeOutcome.Error,
      """{"a":1}""",
      Some("""{"b":2}"""),
      Some("boom")
    )
  }

  it should "set or clear an Option field through its Option setter" in {
    val set = exchange
      .withModel(Some("m"))
      .withRequestId(Some("r"))
      .withCorrelationId(Some("c"))
      .withResponseBody(Some("body"))
      .withErrorMessage(Some("e"))

    set.model shouldBe Some("m")
    set.errorMessage shouldBe Some("e")
    set
      .withModel(None)
      .withRequestId(None)
      .withCorrelationId(None)
      .withResponseBody(None)
      .withErrorMessage(None) shouldBe exchange
  }

  "DiscoveredModel" should "default its metadata and change each field through its setter" in {
    val model = DiscoveredModel(ModelName("a"), ProviderId("openai"))
    model.metadata shouldBe empty

    model.withName(ModelName("b")).withProvider(ProviderId("meta")).withMetadata(Map("owned_by" -> "meta")) shouldBe
      DiscoveredModel(ModelName("b"), ProviderId("meta"), Map("owned_by" -> "meta"))
  }
}
