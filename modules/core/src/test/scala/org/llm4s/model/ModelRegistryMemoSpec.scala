package org.llm4s.model

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.llmconnect.provider.CostEstimator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/** A registry resolves an id that is not an exact key once, not on every reply (#2632). */
class ModelRegistryMemoSpec extends AnyFlatSpec with Matchers:

  private def model(id: String, provider: String): ModelMetadata =
    ModelMetadata(
      modelId = ModelName(id),
      provider = provider,
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(1024),
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
    val map = new CountingMap(models.map(m => m.modelId.asString -> m).toMap)
    (map, new DefaultModelRegistryService(map))

  private val usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)

  "CostEstimator.estimate" should "scan the registry once for a model it does not know, not on every reply" in {
    val (map, registry)        = counting()
    given ModelRegistryService = registry
    CostEstimator.estimate(ModelName("bench-model"), usage) shouldBe None
    val afterFirst = map.scans.get()
    afterFirst should be > 0
    (1 to 100).foreach(_ => CostEstimator.estimate(ModelName("bench-model"), usage) shouldBe None)
    map.scans.get() shouldBe afterFirst
  }

  it should "not scan at all for an exact id" in {
    val (map, registry)        = counting()
    given ModelRegistryService = registry
    (1 to 10).foreach { _ =>
      CostEstimator.estimate(ModelName("model-b"), usage): Unit
      registry.lookup(ModelName("model-b")).map(_.modelId) shouldBe Right(ModelName("model-b"))
    }
    map.scans.get() shouldBe 0
  }

  "lookup(provider, model)" should "scan once for a pair it does not know" in {
    val (map, registry) = counting()
    registry.lookup("acme", ModelName("nope")).isLeft shouldBe true
    val afterFirst = map.scans.get()
    (1 to 10).foreach(_ => registry.lookup("acme", ModelName("nope")).isLeft shouldBe true)
    map.scans.get() shouldBe afterFirst
  }

  "A memoized lookup" should "return what findModel returns, the first time and every time after" in {
    val (map, registry) = counting()
    val ids = Seq(
      "model-b",       // exact
      "MODEL-B",       // case-insensitive
      "other/model-b", // provider prefix stripped
      "zebra/model-d", // exact, prefixed
      "model-d",       // contained in one id: not found, lookup is exact (#2823)
      "model-a",       // contained in several ids: not found
      "  model-b  ",   // trimmed
      "bench-model"    // not found
    )
    ids.foreach { id =>
      val expected = DefaultModelRegistryService.findModel(map, id)
      registry.lookup(ModelName(id)) shouldBe expected
      registry.lookup(ModelName(id)) shouldBe expected
    }
    registry.lookup(ModelName("model-a")).left.toOption.get.message should include("Model not found: model-a")
    registry.lookup(ModelName("zebra/model-d")).map(_.modelId) shouldBe Right(ModelName("zebra/model-d"))
    registry.lookup(ModelName("model-d")).isLeft shouldBe true
    registry.lookup(ModelName("other/model-b")).map(_.modelId) shouldBe Right(ModelName("model-b"))
  }

  it should "stop remembering past its bound, and still answer" in {
    val (map, registry) = counting()
    (0 until DefaultModelRegistryService.MaxMemoized).foreach(i => registry.lookup(ModelName(s"unknown-$i")))
    val full = map.scans.get()
    registry.lookup(ModelName("one-more")).isLeft shouldBe true
    registry.lookup(ModelName("one-more")).isLeft shouldBe true
    map.scans.get() should be > full + 3 // each lookup past the bound scans again
  }
