package org.llm4s.imagegeneration

import io.micrometer.prometheusmetrics.{ PrometheusConfig, PrometheusMeterRegistry }
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.llm4s.error.LLMError
import org.llm4s.media.MediaType
import org.llm4s.metrics.{ MetricsCollector, MicrometerMetrics, PrometheusMetrics }
import org.llm4s.trace.NoOpTracing
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{ Path, Paths }
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Pins the series an instrumented image client exports, through both metrics backends: every name, type, label
 * and histogram bucket, and each counter's value. Dashboards and alerts are written against these, so they are
 * part of the module's contract: the image metrics moved from methods of `MetricsCollector` to
 * [[ImageMetrics]] (#2801) without changing one of them, and this spec is what holds that.
 *
 * It drives [[InstrumentedImageGenerationClient]] rather than the metrics directly, so it also pins what the
 * client records for a success and for a failure. One generated image that has a price, and one failed edit.
 */
class ImageMetricsSeriesSpec extends AnyFlatSpec with Matchers {

  private val config = new ImageGenerationConfig {
    def provider: ImageGenerationProvider = ImageGenerationProvider.DALLE
    def model: String                     = "dall-e-3"
  }

  private val image =
    GeneratedImage(data = "ZmFrZQ==", format = MediaType.Png, size = ImageSize.Square1024, prompt = "a cat")

  private val down: LLMError = ImageServiceError("provider is down", 503)

  /** Generates one image and fails every edit. */
  private object Delegate extends ImageGenerationClient {
    override def generateImage(prompt: String, options: ImageGenerationOptions): Either[LLMError, GeneratedImage] =
      Right(image)
    override def generateImages(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions
    ): Either[LLMError, Seq[GeneratedImage]] = Right(Seq.fill(count)(image))
    override def editImage(
      imagePath: Path,
      prompt: String,
      maskPath: Option[Path],
      options: ImageEditOptions
    ): Either[LLMError, Seq[GeneratedImage]] = Left(down)
    override def generateImageAsync(prompt: String, options: ImageGenerationOptions)(implicit
      ec: ExecutionContext
    ): Future[Either[LLMError, GeneratedImage]] = Future.successful(Right(image))
    override def generateImagesAsync(prompt: String, count: Int, options: ImageGenerationOptions)(implicit
      ec: ExecutionContext
    ): Future[Either[LLMError, Seq[GeneratedImage]]] = Future.successful(Right(Seq.fill(count)(image)))
    override def editImageAsync(imagePath: Path, prompt: String, maskPath: Option[Path], options: ImageEditOptions)(
      implicit ec: ExecutionContext
    ): Future[Either[LLMError, Seq[GeneratedImage]]] = Future.successful(Left(down))
    override def health(): Either[LLMError, ServiceStatus] = Right(ServiceStatus(HealthStatus.Healthy, "ok"))
  }

  private def exercise(metrics: MetricsCollector): Unit = {
    val client = new InstrumentedImageGenerationClient(Delegate, config, metrics, new NoOpTracing())
    client.generateImage("a cat", ImageGenerationOptions(size = ImageSize.Square1024)).isRight shouldBe true
    client.editImage(Paths.get("cat.png"), "a dog", None, ImageEditOptions()).isLeft shouldBe true
  }

  private val Sample = """^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})? (\S+)$""".r
  private val Pair   = """([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"""".r
  private val Type   = """^# TYPE (\S+) (\S+)$""".r

  /**
   * The exposition reduced to what a dashboard depends on, one line each, sorted: `TYPE` lines, and samples with
   * their labels in name order. Left out: `HELP` text; `_created` (a client-library timestamp) and `_max` (a gauge
   * Micrometer keeps beside every timer); and the value of `_sum`, which is the time the stub took.
   */
  private def series(exposition: String): Seq[String] =
    exposition.linesIterator
      .map(_.trim)
      .flatMap {
        case Type(name, kind)                             => Some(s"TYPE $name $kind")
        case line if line.isEmpty || line.startsWith("#") => None
        case Sample(name, labels, value) =>
          val pairs = Option(labels).toSeq.flatMap(l => Pair.findAllMatchIn(l).map(m => m.group(1) -> m.group(2)))
          val shown = pairs.sorted.map((k, v) => s"""$k="$v"""").mkString("{", ",", "}")
          Some(if (name.endsWith("_sum")) s"$name$shown" else s"$name$shown ${value.toDouble}")
        case other => fail(s"cannot parse the exposition line: $other")
      }
      .filterNot(line => line.contains("_created") || line.contains("_max"))
      .toSeq
      .sorted

  private def prometheus(): Seq[String] = {
    val registry = new PrometheusRegistry()
    exercise(new PrometheusMetrics(registry))
    val out = new ByteArrayOutputStream()
    PrometheusTextFormatWriter.create().write(out, registry.scrape())
    series(new String(out.toByteArray, StandardCharsets.UTF_8))
  }

  private def micrometer(): Seq[String] = {
    val registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    exercise(new MicrometerMetrics(registry))
    series(registry.scrape())
  }

  /** As exported before #2801, when the image metrics were methods of `MetricsCollector`. Do not edit to fit. */
  private val Expected: Seq[String] =
    """
      |TYPE llm4s_cost_usd_total counter
      |TYPE llm4s_errors_total counter
      |TYPE llm4s_image_generation_cost_usd_total counter
      |TYPE llm4s_image_generation_duration_seconds histogram
      |TYPE llm4s_image_generation_errors_total counter
      |TYPE llm4s_image_generations_total counter
      |TYPE llm4s_images_generated_total counter
      |llm4s_cost_usd_total{model="dall-e-3",provider="openai"} 0.04
      |llm4s_errors_total{error_type="service_error",provider="openai"} 1.0
      |llm4s_image_generation_cost_usd_total{model="dall-e-3",provider="openai"} 0.04
      |llm4s_image_generation_duration_seconds_bucket{le="+Inf",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="+Inf",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="1.0",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="1.0",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="10.0",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="10.0",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="120.0",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="120.0",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="2.0",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="2.0",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="30.0",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="30.0",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="300.0",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="300.0",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="5.0",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="5.0",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="60.0",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_bucket{le="60.0",model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_count{model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_count{model="dall-e-3",operation="generate",provider="openai"} 1.0
      |llm4s_image_generation_duration_seconds_sum{model="dall-e-3",operation="edit",provider="openai"}
      |llm4s_image_generation_duration_seconds_sum{model="dall-e-3",operation="generate",provider="openai"}
      |llm4s_image_generation_errors_total{error_type="service_error",model="dall-e-3",operation="edit",provider="openai"} 1.0
      |llm4s_image_generations_total{model="dall-e-3",operation="edit",provider="openai",status="error_service_error"} 1.0
      |llm4s_image_generations_total{model="dall-e-3",operation="generate",provider="openai",status="success"} 1.0
      |llm4s_images_generated_total{model="dall-e-3",provider="openai"} 1.0
      |""".stripMargin.linesIterator.map(_.trim).filter(_.nonEmpty).toSeq

  "An instrumented image client" should "export exactly the pinned series through PrometheusMetrics" in {
    prometheus().mkString("\n") shouldBe Expected.mkString("\n")
  }

  it should "export exactly the pinned series through MicrometerMetrics behind a Prometheus registry" in {
    micrometer().mkString("\n") shouldBe Expected.mkString("\n")
  }
}
