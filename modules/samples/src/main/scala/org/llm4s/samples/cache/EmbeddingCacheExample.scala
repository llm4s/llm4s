package org.llm4s.samples.cache

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.caching.{ CacheStats, CachedEmbeddingClient, InMemoryEmbeddingCache }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/**
 * Sample demonstrating `CachedEmbeddingClient`: the same text is embedded twice, and the second call is
 * served from the cache without reaching the provider.
 *
 * The provider is wrapped in a small [[CountingProvider]], so the output shows how many calls actually went
 * out. Three calls are made:
 *  1. `"hello world"`: a miss, sent to the provider
 *  2. `"hello world"` again: a hit, the provider is not called
 *  3. `"hello world"` and `"something new"`: only the new text is a miss, and goes out alone
 *
 * Prerequisites: an embedding provider, configured as for the other embedding samples
 * (`EMBEDDING_MODEL=openai/text-embedding-3-small` and the provider's API key).
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.cache.EmbeddingCacheExample"
 */
object EmbeddingCacheExample {

  /** An [[org.llm4s.llmconnect.provider.EmbeddingProvider EmbeddingProvider]] that counts the calls and the texts that reach the wrapped provider. */
  final class CountingProvider(delegate: EmbeddingProvider) extends EmbeddingProvider {
    private val callCounter = new AtomicInteger(0)
    private val textCounter = new AtomicInteger(0)

    def calls: Int = callCounter.get()
    def texts: Int = textCounter.get()

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      callCounter.incrementAndGet()
      textCounter.addAndGet(request.input.size)
      delegate.embed(request)
    }
  }

  /** What one call did: the texts asked for, and how many provider calls and texts it cost. */
  final case class Step(label: String, texts: Seq[String], providerCalls: Int, providerTexts: Int, hit: Boolean)

  final case class Report(steps: Seq[Step], stats: CacheStats)

  /** Runs the three calls against `provider` through a `CachedEmbeddingClient`; the logic of the sample. */
  def run(provider: EmbeddingProvider, model: EmbeddingModelConfig)(using ModelRegistryService): Result[Report] = {
    val counting = new CountingProvider(provider)
    val cache    = new InMemoryEmbeddingCache[Seq[Double]](maxSize = 1000, ttl = Some(1.hour))
    val cached   = new CachedEmbeddingClient(new EmbeddingClient(counting), cache)

    def step(label: String, texts: Seq[String]): Result[Step] = {
      val (callsBefore, textsBefore) = (counting.calls, counting.texts)
      cached.embed(EmbeddingRequest(texts, model)).map { _ =>
        val calls = counting.calls - callsBefore
        Step(label, texts, calls, counting.texts - textsBefore, hit = calls == 0)
      }
    }

    for {
      first  <- step("1. first call", Seq("hello world"))
      second <- step("2. same text again", Seq("hello world"))
      third  <- step("3. one cached, one new", Seq("hello world", "something new"))
    } yield Report(Seq(first, second, third), cached.cacheStats)
  }

  def print(report: Report): Unit = {
    println("=== Embedding Cache Demo ===\n")
    report.steps.foreach { s =>
      val outcome = if (s.hit) "CACHE HIT " else "CACHE MISS"
      println(
        f"${s.label}%-24s $outcome  provider calls: ${s.providerCalls}, texts sent: ${s.providerTexts}  ${s.texts
            .mkString("[\"", "\", \"", "\"]")}"
      )
    }
    println(
      s"\nCache stats: ${report.stats.hits} hits, ${report.stats.misses} misses, " +
        f"hit rate ${report.stats.hitRatePercent}%.1f%%, ${report.stats.size} entries"
    )
  }

  def main(args: Array[String]): Unit = {
    val result = for {
      embeddings <- Llm4sConfig.embeddings()
      textModel  <- Llm4sConfig.textEmbeddingModel()
      service    <- Llm4sConfig.modelRegistryService()
      report <- {
        given ModelRegistryService = service
        val (providerName, config) = embeddings
        val registry               = summon[ProviderRegistry]
        for {
          descriptor <- registry.resolveEmbedding(registry.canonicalEmbeddingId(providerName))
          provider   <- descriptor.build(config)
          report     <- run(provider, EmbeddingModelConfig(config.model, textModel.dimensions))
        } yield report
      }
    } yield report

    result match {
      case Right(report) => print(report)
      case Left(error) =>
        System.err.println(s"Embedding cache demo failed: ${error.message}")
        sys.exit(1)
    }
  }
}
