package org.llm4s.llmconnect

import org.llm4s.security.Secret
import org.llm4s.llmconnect.caching.CacheStats
import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.llmconnect.spi.{
  EmbeddingProviderSection,
  ProviderCollisionKind,
  ProviderConfigKey,
  ProviderDiscoveryFailure,
  ProviderIdCollision,
  ProviderModuleReport,
  ProviderRegistryReport
}
import org.llm4s.llmconnect.streaming.SSEParser.SSEEvent
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.concurrent.duration.*

/**
 * The growth-safe shape (#2379) of the provider SPI, caching, streaming and exchange-sink types: a private
 * constructor, a companion `apply` keeping the old defaults, and `with*` setters in place of `copy`.
 */
class GrowthSafeLlmconnectTypesSpec extends AnyFlatSpec with Matchers {

  "JsonlProviderExchangeSink" should "be built by apply and re-pointed by withPath" in {
    val sink = JsonlProviderExchangeSink(Path.of("a.jsonl"))
    sink.path shouldBe Path.of("a.jsonl")
    sink.withPath(Path.of("b.jsonl")).path shouldBe Path.of("b.jsonl")
    sink match { case JsonlProviderExchangeSink(p) => p shouldBe Path.of("a.jsonl") }
  }

  "CacheStats" should "be built by apply and changed by its setters" in {
    val stats = CacheStats(1, 2L, 3L)
    stats shouldBe CacheStats(size = 1, hits = 2L, misses = 3L)
    val changed = stats.withSize(9).withHits(8L).withMisses(7L)
    changed shouldBe CacheStats(9, 8L, 7L)
  }

  "EmbeddingProviderSection" should "default every field and be changed by its setters" in {
    val section = EmbeddingProviderSection()
    section.apiKey shouldBe None
    section.baseUrl shouldBe None
    section.model shouldBe None
    section.timeouts shouldBe ProviderTimeouts.default
    section.extras shouldBe Map.empty

    val timeouts = ProviderTimeouts(request = Some(3.seconds))
    val set = section
      .withApiKey(Secret("k"))
      .withBaseUrl(BaseUrl("http://b"))
      .withModel(ModelName("m"))
      .withTimeouts(timeouts)
    set shouldBe EmbeddingProviderSection(Some(Secret("k")), Some(BaseUrl("http://b")), Some(ModelName("m")), timeouts)

    val cleared = set.withApiKey(None).withBaseUrl(None).withModel(None)
    cleared shouldBe EmbeddingProviderSection(timeouts = timeouts)
    set
      .withApiKey(Some(Secret("k2")))
      .withBaseUrl(Some(BaseUrl("http://c")))
      .withModel(Some(ModelName("m2"))) shouldBe
      EmbeddingProviderSection(Some(Secret("k2")), Some(BaseUrl("http://c")), Some(ModelName("m2")), timeouts)
  }

  it should "hold the provider's declared extras, and keep their values out of toString (#2811)" in {
    val section = EmbeddingProviderSection().withExtras(Map("region" -> "eu-west-secret-1"))
    section.extra("region") shouldBe Some("eu-west-secret-1")
    section.extra("absent") shouldBe None
    section shouldBe EmbeddingProviderSection(extras = Map("region" -> "eu-west-secret-1"))
    section.toString should include("region -> ***")
    (section.toString should not).include("eu-west-secret-1")
    EmbeddingProviderSection(baseUrl = Some(BaseUrl("https://user:pw@host.example/v1?token=abc"))).toString should
      (not.include("pw@").and(not).include("abc"))
  }

  "ProviderConfigKey" should "default to optional with no default or env" in {
    val key = ProviderConfigKey("region", "the region")
    key.required shouldBe false
    key.default shouldBe None
    key.env shouldBe None
  }

  it should "be changed by its setters" in {
    val key = ProviderConfigKey("region", "the region")
      .withName("zone")
      .withDescription("the zone")
      .withRequired(true)
      .withDefault("eu")
      .withEnv("ZONE")
    key shouldBe ProviderConfigKey("zone", "the zone", true, Some("eu"), Some("ZONE"))
    key.withDefault(None).withEnv(None) shouldBe ProviderConfigKey("zone", "the zone", true, None, None)
    key.withDefault(Some("us")).withEnv(Some("Z")).default shouldBe Some("us")
    ProviderConfigKey.optional("r", "d").withEnv("R").env shouldBe Some("R")
  }

  // The registry report types are what the registry hands out, never what a caller builds (#2811): their `apply`
  // is `private[llm4s]` and they have no setters.
  "ProviderModuleReport" should "default to no embedding ids and carry its ids as ProviderId" in {
    val report = ProviderModuleReport("a.Module", Seq(ProviderId("x")), None)
    report.embeddingProviderIds shouldBe Nil
    report.providerIds shouldBe Seq(ProviderId("X"))
  }

  "ProviderDiscoveryFailure" should "default to no throwable" in {
    ProviderDiscoveryFailure("broken").failure shouldBe None
  }

  "ProviderIdCollision" should "say what collided with a kind, not a string" in {
    val collision = ProviderIdCollision(ProviderCollisionKind.Chat, ProviderId("acme"), "b.Module", None)
    collision.kind.label shouldBe "chat"
    collision.kind.isAlias shouldBe false
    ProviderCollisionKind.values.filter(_.isAlias).map(_.label) shouldBe Array("chat alias", "embedding alias")
  }

  "ProviderRegistryReport" should "default to empty lists" in {
    val report = ProviderRegistryReport(discovered = true)
    report.modules shouldBe Nil
    report.failures shouldBe Nil
    report.collisions shouldBe Nil

    val collision = ProviderIdCollision(ProviderCollisionKind.Chat, ProviderId("x"), "a.Module", None)
    report.withCollisions(Seq(collision)).collisions shouldBe Seq(collision)
    ProviderRegistryReport.explicit shouldBe ProviderRegistryReport(discovered = false)
  }

  "SSEEvent" should "default every field to absent and be changed by its setters" in {
    val empty = SSEEvent()
    empty.isEmpty shouldBe true

    val set = empty.withData("d").withEvent("e").withId("i").withRetry(5.millis)
    set shouldBe SSEEvent(Some("d"), Some("e"), Some("i"), Some(5.millis))
    set.withData(None).withEvent(None).withId(None).withRetry(None) shouldBe empty
    empty.withData(Some("x")).withEvent(Some("y")).withId(Some("z")).withRetry(Some(1.second)) shouldBe
      SSEEvent(data = Some("x"), event = Some("y"), id = Some("z"), retry = Some(1.second))
  }
}
