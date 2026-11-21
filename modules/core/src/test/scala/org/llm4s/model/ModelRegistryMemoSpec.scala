package org.llm4s.model

import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.llmconnect.provider.CostEstimator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/** A registry resolves an id that is not an exact key once, not on every reply (#2632). */
class ModelRegistryMemoSpec extends AnyFlatSpec with Matchers:

  private def model(id: String, provider: String): ModelMetadata =
    ModelMetadata(
      modelId = id,
      provider = provider,
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(1024),
      inputCostPerToken = Some(0.000001),
      outputCostPerToken = Some(0.000002),
      capabilities = ModelCapabilities(),
      pricing = ModelPricing(),
      deprecationDate = None
    )

  private val models = Seq(
    model("acme/model-a", "acme"),
    model("acme/model-a-pro", "acme"),
    model("model-b", "acme"),
    model("zebra/model-d", "zebra")
  )

  /** The registry's map, counting every traversal: each scan of `findModel` starts one. */
  final private class CountingMap(underlying: Map[String, ModelMetadata])
      extends scala.collection.immutable.AbstractMap[String, ModelMetadata]:
    val scans                                            = new AtomicInteger(0)
    override def get(key: String): Option[ModelMetadata] = underlying.get(key)
    override def iterator: Iterator[(String, ModelMetadata)] =
      scans.incrementAndGet()
      underlying.iterator
    override def removed(key: String): Map[String, ModelMetadata]                      = underlying.removed(key)
    override def updated[V1 >: ModelMetadata](key: String, value: V1): Map[String, V1] = underlying.updated(key, value)

  private def counting(): (CountingMap, ModelRegistryService) =
    val map = new CountingMap(models.map(m => m.modelId -> m).toMap)
    (map, new DefaultModelRegistryService(map))

  private val usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)

  "CostEstimator.estimate" should "scan the registry once for a model it does not know, not on every reply" in {
    val (map, registry)        = counting()
    given ModelRegistryService = registry
    CostEstimator.estimate("bench-model", usage) shouldBe None
    val afterFirst = map.scans.get()
    afterFirst should be > 0
    (1 to 100).foreach(_ => CostEstimator.estimate("bench-model", usage) shouldBe None)
    map.scans.get() shouldBe afterFirst
  }

  it should "not scan at all for an exact id" in {
    val (map, registry)        = counting()
    given ModelRegistryService = registry
    (1 to 10).foreach { _ =>
      CostEstimator.estimate("model-b", usage): Unit
      registry.lookup("model-b").map(_.modelId) shouldBe Right("model-b")
    }
    map.scans.get() shouldBe 0
  }

  "lookup(provider, model)" should "scan once for a pair it does not know" in {
    val (map, registry) = counting()
    registry.lookup("acme", "nope").isLeft shouldBe true
    val afterFirst = map.scans.get()
    (1 to 10).foreach(_ => registry.lookup("acme", "nope").isLeft shouldBe true)
    map.scans.get() shouldBe afterFirst
  }

  "A memoized lookup" should "return what findModel returns, the first time and every time after" in {
    val (map, registry) = counting()
    val ids = Seq(
      "model-b",       // exact
      "MODEL-B",       // case-insensitive
      "other/model-b", // provider prefix stripped
      "model-d",       // fuzzy, one match
      "model-a",       // fuzzy, several matches: ambiguous
      "  model-b  ",   // trimmed
      "bench-model"    // not found
    )
    ids.foreach { id =>
      val expected = DefaultModelRegistryService.findModel(map, id)
      registry.lookup(id) shouldBe expected
      registry.lookup(id) shouldBe expected
    }
    registry.lookup("model-a").left.toOption.get.message should include("Ambiguous model identifier 'model-a'")
    registry.lookup("model-d").map(_.modelId) shouldBe Right("zebra/model-d")
    registry.lookup("other/model-b").map(_.modelId) shouldBe Right("model-b")
  }

  it should "stop remembering past its bound, and still answer" in {
    val (map, registry) = counting()
    (0 until DefaultModelRegistryService.MaxMemoized).foreach(i => registry.lookup(s"unknown-$i"))
    val full = map.scans.get()
    registry.lookup("one-more").isLeft shouldBe true
    registry.lookup("one-more").isLeft shouldBe true
    map.scans.get() should be > full + 3 // each lookup past the bound scans again
  }
