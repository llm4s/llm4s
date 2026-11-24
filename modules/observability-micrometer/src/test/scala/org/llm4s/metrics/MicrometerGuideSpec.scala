package org.llm4s.metrics

import org.llm4s.reliability.ReliabilityMetrics
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.{ PrometheusConfig, PrometheusMeterRegistry }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Keeps `docs/guide/observability/micrometer.md` true. The quick start is compiled here, and the page's table of meters is
 * checked against what the collector really records: every meter name, its tag names and the Prometheus series it exports.
 */
@org.llm4s.tags.DocsSpec
class MicrometerGuideSpec extends AnyFlatSpec with Matchers {

  // The quick start of the guide, compiled (it needs a configured provider, so it is not run).
  def clientWithMetrics(meterRegistry: MeterRegistry) =
    for {
      providerConfig  <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registryService
      metrics                    = new MicrometerMetrics(meterRegistry)
      client <- LLMConnect.getClient(providerConfig, metrics)
    } yield client

  // The "Errors and ReliableClient" example, compiled (not run, for the same reason).
  def reliableWithMetrics(meterRegistry: MeterRegistry, providerConfig: ProviderConfig)(using ModelRegistryService) = {
    val metrics = new MicrometerMetrics(meterRegistry)
    val client = LLMConnect
      .getClient(providerConfig, metrics)
      .map(c => ReliableClient(c, "openai", ReliabilityConfig.default).withMetrics(metrics))
    client
  }

  private val ReliableLines = Seq(
    "val metrics = new MicrometerMetrics(meterRegistry)",
    "val client = LLMConnect",
    ".getClient(providerConfig, metrics)",
    """.map(c => ReliableClient(c, "openai", ReliabilityConfig.default).withMetrics(metrics))"""
  )

  /** The lines of the quick start as the page shows them; the page must contain each. */
  private val QuickStartLines = Seq(
    "import io.micrometer.core.instrument.MeterRegistry",
    "import org.llm4s.config.Llm4sConfig",
    "import org.llm4s.llmconnect.LLMConnect",
    "import org.llm4s.metrics.MicrometerMetrics",
    "import org.llm4s.model.ModelRegistryService",
    "def clientWithMetrics(meterRegistry: MeterRegistry) =",
    "providerConfig  <- Llm4sConfig.defaultProvider()",
    "registryService <- Llm4sConfig.modelRegistryService()",
    "given ModelRegistryService = registryService",
    "metrics                    = new MicrometerMetrics(meterRegistry)",
    "client <- LLMConnect.getClient(providerConfig, metrics)"
  )

  private val GuidePath = "docs/guide/observability/micrometer.md"

  /** The guide, found by walking up from the working directory (sbt runs tests from the build root, or the module). */
  private lazy val guide: String = {
    val start = new File(".").getAbsoluteFile
    val file = Iterator
      .iterate(Option(start))(_.flatMap(f => Option(f.getParentFile)))
      .takeWhile(_.isDefined)
      .flatten
      .map(dir => new File(dir, GuidePath))
      .find(_.isFile)
      .getOrElse(fail(s"cannot find $GuidePath above ${start.getPath}"))
    Using.resource(Source.fromFile(file, "UTF-8"))(_.mkString)
  }

  /** The documented meter rows: name -> (tag names, Prometheus series). */
  private lazy val documented: Map[String, (Set[String], String)] = {
    val Row = """^\| `([^`]+)` \| (?:counter|timer) \| ([^|]+) \| `([^`]+)` \|$""".r
    guide.linesIterator
      .map(_.trim)
      .flatMap {
        case Row(name, tags, series) =>
          Some(name -> ("""`([^`]+)`""".r.findAllMatchIn(tags).map(_.group(1)).toSet, series))
        case _ => None
      }
      .toMap
  }

  private lazy val allNames: Seq[String] = {
    import MicrometerMetrics.Names.*
    Seq(
      Requests,
      RequestDuration,
      Tokens,
      CostUsd,
      Errors,
      ReliabilityErrors,
      Retries,
      CircuitBreakerTransitions
    ) ++ ImageMeters.map(_.name)
  }

  /**
   * `ImageMetrics` of `llm4s-image`, which this module cannot depend on: the guide's rows for the image meters are
   * checked against these copies. `ImageMetricsSeriesSpec` in `llm4s-image` pins the real definitions to the same
   * series, through this collector.
   */
  private val imageGenerations =
    Metric.counter("llm4s.image.generations", "Image generation operations", "provider", "model", "operation", "status")
  private val imagesGenerated = Metric.counter("llm4s.images.generated", "Images generated", "provider", "model")
  private val imageDuration = Metric.timer(
    "llm4s.image.generation.duration",
    "Image generation duration",
    Seq(1.second, 300.seconds),
    "provider",
    "model",
    "operation"
  )
  private val imageCost = Metric.counter("llm4s.image.generation.cost.usd", "Image cost", "provider", "model")
  private val imageErrors = Metric.counter(
    "llm4s.image.generation.errors",
    "Image generation errors",
    "provider",
    "model",
    "operation",
    "error_type"
  )
  private val ImageMeters: Seq[Metric] =
    Seq(imageGenerations, imagesGenerated, imageDuration, imageCost, imageErrors)

  private def exerciseEverything(metrics: MetricsCollector): Unit = {
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.Timeout), 1.second)
    metrics.addTokens("openai", "gpt-4o", 1L, 1L)
    metrics.recordCost("openai", "gpt-4o", 0.1)
    metrics.increment(ReliabilityMetrics.Retries, Seq("openai"))
    metrics.increment(ReliabilityMetrics.CircuitBreakerTransitions, Seq("openai", "open"))
    metrics.increment(ReliabilityMetrics.Errors, Seq("openai", ReliabilityMetrics.errorTypeLabel(ErrorKind.Timeout)))
    metrics.increment(imageGenerations, Seq("openai", "dall-e-3", "generate", "success"))
    metrics.increment(imagesGenerated, Seq("openai", "dall-e-3"))
    metrics.observe(imageDuration, Seq("openai", "dall-e-3", "generate"), 1.second)
    metrics.increment(imageCost, Seq("openai", "dall-e-3"), 0.1)
    metrics.increment(imageErrors, Seq("openai", "dall-e-3", "generate", "network"))
  }

  "The guide" should "show the quick start that compiles" in {
    QuickStartLines.foreach(line => withClue(s"missing from the guide: $line\n")(guide should include(line)))
  }

  it should "show the ReliableClient example that compiles" in {
    ReliableLines.foreach(line => withClue(s"missing from the guide: $line\n")(guide should include(line)))
  }

  it should "document every meter the module defines, and no other" in {
    // `Names` has one constant per meter: if one is added without a row, the lists differ.
    val declared = MicrometerMetrics.Names.getClass.getDeclaredMethods
      .filter(m => m.getParameterCount == 0 && m.getReturnType == classOf[String] && !m.getName.contains("$"))
      .size
    // ...plus the image meters, which `llm4s-image` declares
    (declared + ImageMeters.size) shouldBe allNames.size
    documented.keySet shouldBe allNames.toSet
  }

  it should "list the tag names each meter really has" in {
    val registry = new SimpleMeterRegistry()
    exerciseEverything(new MicrometerMetrics(registry))
    allNames.foreach { name =>
      val actual = registry.find(name).meters().asScala.flatMap(_.getId.getTags.asScala.map(_.getKey)).toSet
      withClue(s"tags of $name: ")(documented(name)._1 shouldBe actual)
    }
  }

  it should "name the Prometheus series each meter is exported as" in {
    val prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    exerciseEverything(new MicrometerMetrics(prometheus))
    val exposed =
      prometheus.scrape().linesIterator.filterNot(_.startsWith("#")).map(_.takeWhile(c => c != '{' && c != ' ')).toSet
    allNames.foreach { name =>
      val series = documented(name)._2
      withClue(s"$name is documented as $series: ") {
        exposed.exists(s => s == series || s.startsWith(series + "_")) shouldBe true
      }
    }
  }

  it should "give the example that reads a value back" in {
    val meterRegistry = new SimpleMeterRegistry()
    new MicrometerMetrics(meterRegistry).observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    guide should include("""meterRegistry.find("llm4s.requests").tag("status", "success").counter().count()""")
    meterRegistry.find("llm4s.requests").tag("status", "success").counter().count() shouldBe 1.0
  }
}
