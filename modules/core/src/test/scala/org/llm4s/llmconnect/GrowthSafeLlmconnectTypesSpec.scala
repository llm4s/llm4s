package org.llm4s.llmconnect

import org.llm4s.llmconnect.caching.{ CacheEntry, CacheStats }
import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions }
import org.llm4s.llmconnect.spi.{
  EmbeddingProviderSection,
  ProviderConfigKey,
  ProviderDiscoveryFailure,
  ProviderIdCollision,
  ProviderModuleReport,
  ProviderRegistryReport
}
import org.llm4s.llmconnect.streaming.SSEParser.SSEEvent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.duration.*

/**
 * The growth-safe shape (#2379) of the provider SPI, caching, streaming and exchange-sink types: a private
 * constructor, a companion `apply` keeping the old defaults, and `with*` setters in place of `copy`.
 */
class GrowthSafeLlmconnectTypesSpec extends AnyFlatSpec with Matchers {

  private val completion = Completion(
    id = "c-1",
    created = Instant.EPOCH,
    content = "hi",
    model = "m",
    message = AssistantMessage("hi"),
    usage = None
  )

  "JsonlProviderExchangeSink" should "be built by apply and re-pointed by withPath" in {
    val sink = JsonlProviderExchangeSink(Path.of("a.jsonl"))
    sink.path shouldBe Path.of("a.jsonl")
    sink.withPath(Path.of("b.jsonl")).path shouldBe Path.of("b.jsonl")
    sink match { case JsonlProviderExchangeSink(p) => p shouldBe Path.of("a.jsonl") }
  }

  "CacheEntry" should "be built by apply and changed by its setters" in {
    val entry = CacheEntry(Seq(1.0), completion, Instant.EPOCH, CompletionOptions())
    entry.embedding shouldBe Seq(1.0)
    entry.response shouldBe completion
    entry.timestamp shouldBe Instant.EPOCH
    entry.options shouldBe CompletionOptions()

    val other = completion.withContent("bye")
    val later = Instant.EPOCH.plusSeconds(5)
    val opts  = CompletionOptions().withTemperature(0.1)
    val changed =
      entry.withEmbedding(Seq(0.5, 0.5)).withResponse(other).withTimestamp(later).withOptions(opts)
    changed.embedding shouldBe Seq(0.5, 0.5)
    changed.response shouldBe other
    changed.timestamp shouldBe later
    changed.options shouldBe opts
  }

  "CacheStats" should "be built by apply and changed by its setters" in {
    val stats = CacheStats(1, 2L, 3L, 5L, 40.0)
    stats shouldBe CacheStats(size = 1, hits = 2L, misses = 3L, totalRequests = 5L, hitRatePercent = 40.0)
    val changed = stats.withSize(9).withHits(8L).withMisses(7L).withTotalRequests(15L).withHitRatePercent(53.3)
    changed shouldBe CacheStats(9, 8L, 7L, 15L, 53.3)
  }

  "EmbeddingProviderSection" should "default every field and be changed by its setters" in {
    val section = EmbeddingProviderSection()
    section.apiKey shouldBe None
    section.baseUrl shouldBe None
    section.model shouldBe None
    section.timeouts shouldBe ProviderTimeouts.default

    val timeouts = ProviderTimeouts(request = Some(3.seconds))
    val set      = section.withApiKey("k").withBaseUrl("http://b").withModel("m").withTimeouts(timeouts)
    set shouldBe EmbeddingProviderSection(Some("k"), Some("http://b"), Some("m"), timeouts)

    val cleared = set.withApiKey(None).withBaseUrl(None).withModel(None)
    cleared shouldBe EmbeddingProviderSection(timeouts = timeouts)
    set.withApiKey(Some("k2")).withBaseUrl(Some("http://c")).withModel(Some("m2")) shouldBe
      EmbeddingProviderSection(Some("k2"), Some("http://c"), Some("m2"), timeouts)
  }

  "ProviderConfigKey" should "default to optional with no default, env or aliases" in {
    val key = ProviderConfigKey("region", "the region")
    key.required shouldBe false
    key.default shouldBe None
    key.env shouldBe None
    key.deprecatedAliases shouldBe Seq.empty
  }

  it should "be changed by its setters" in {
    val key = ProviderConfigKey("region", "the region")
      .withName("zone")
      .withDescription("the zone")
      .withRequired(true)
      .withDefault("eu")
      .withEnv("ZONE")
      .withDeprecatedAliases(Seq("area"))
    key shouldBe ProviderConfigKey("zone", "the zone", true, Some("eu"), Some("ZONE"), Seq("area"))
    key.withDefault(None).withEnv(None) shouldBe ProviderConfigKey("zone", "the zone", true, None, None, Seq("area"))
    key.withDefault(Some("us")).withEnv(Some("Z")).default shouldBe Some("us")
    ProviderConfigKey.optional("r", "d").withEnv("R").env shouldBe Some("R")
  }

  "ProviderModuleReport" should "default to no embedding ids and be changed by its setters" in {
    val report = ProviderModuleReport("a.Module", Seq("x"), None)
    report.embeddingProviderIds shouldBe Nil

    val changed = report
      .withModuleClass("b.Module")
      .withProviderIds(Seq("y"))
      .withSource("file:/b.jar")
      .withEmbeddingProviderIds(Seq("e"))
    changed shouldBe ProviderModuleReport("b.Module", Seq("y"), Some("file:/b.jar"), Seq("e"))
    changed.withSource(None).source shouldBe None
    changed.withSource(Some("dir")).source shouldBe Some("dir")
  }

  "ProviderDiscoveryFailure" should "default to no throwable and be changed by its setters" in {
    val failure = ProviderDiscoveryFailure("broken")
    failure.failure shouldBe None
    val boom    = new IllegalStateException("boom")
    val changed = failure.withDetail("really broken").withFailure(boom)
    changed.detail shouldBe "really broken"
    changed.failure shouldBe Some(boom)
    changed.withFailure(None).failure shouldBe None
    changed.withFailure(Some(boom)).failure shouldBe Some(boom)
  }

  "ProviderIdCollision" should "be built by apply and changed by its setters" in {
    val collision = ProviderIdCollision("chat", "acme", "b.Module", None)
    val changed = collision
      .withKind("embedding")
      .withId("acme2")
      .withKeptModule("c.Module")
      .withDroppedModule("a.Module")
    changed shouldBe ProviderIdCollision("embedding", "acme2", "c.Module", Some("a.Module"))
    changed.withDroppedModule(None).droppedModule shouldBe None
    changed.withDroppedModule(Some("z")).droppedModule shouldBe Some("z")
  }

  "ProviderRegistryReport" should "default to empty lists and be changed by its setters" in {
    val report = ProviderRegistryReport(discovered = true)
    report.modules shouldBe Nil
    report.failures shouldBe Nil
    report.collisions shouldBe Nil

    val module    = ProviderModuleReport("a.Module", Seq("x"), None)
    val failure   = ProviderDiscoveryFailure("broken")
    val collision = ProviderIdCollision("chat", "x", "a.Module", None)
    val changed = report
      .withDiscovered(false)
      .withModules(Seq(module))
      .withFailures(Seq(failure))
      .withCollisions(Seq(collision))
    changed shouldBe ProviderRegistryReport(false, Seq(module), Seq(failure), Seq(collision))
    ProviderRegistryReport.explicit shouldBe ProviderRegistryReport(discovered = false)
  }

  "SSEEvent" should "default every field to absent and be changed by its setters" in {
    val empty = SSEEvent()
    empty.isEmpty shouldBe true

    val set = empty.withData("d").withEvent("e").withId("i").withRetry(5)
    set shouldBe SSEEvent(Some("d"), Some("e"), Some("i"), Some(5))
    set.withData(None).withEvent(None).withId(None).withRetry(None) shouldBe empty
    empty.withData(Some("x")).withEvent(Some("y")).withId(Some("z")).withRetry(Some(1)) shouldBe
      SSEEvent(data = Some("x"), event = Some("y"), id = Some("z"), retry = Some(1))
  }
}
