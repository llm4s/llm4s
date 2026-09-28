// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.metrics.{ MetricsCollector, PrometheusMetrics, PrometheusEndpoint }
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }
import org.slf4j.LoggerFactory

/**
 * Loads the Prometheus metrics backend from `llm4s.metrics`: a
 * [[org.llm4s.metrics.MetricsCollector]], plus the [[org.llm4s.metrics.PrometheusEndpoint]]
 * serving it when one was started.
 *
 * This replaces `Llm4sConfig.metrics()`, which left core with Prometheus (#1133):
 * `MetricsConfigLoader.default()` is the same call against the same keys.
 *
 * Configuration keys, with their defaults in this module's `reference.conf`:
 * - `llm4s.metrics.enabled`: enable metrics collection (default: false)
 * - `llm4s.metrics.prometheus.enabled`: use the Prometheus backend when metrics are enabled (default: true)
 * - `llm4s.metrics.prometheus.port`: port for the `/metrics` HTTP endpoint (default: 9090)
 *
 * Example application.conf:
 * {{{
 * llm4s {
 *   metrics {
 *     enabled = true
 *     prometheus {
 *       enabled = true
 *       port = 9090
 *     }
 *   }
 * }
 * }}}
 *
 * When enabled, loading starts the HTTP endpoint; stop it with `PrometheusEndpoint.stop()`.
 * Other code should receive the `MetricsCollector` by dependency injection rather than read
 * these keys itself.
 */
object MetricsConfigLoader {

  private val logger = LoggerFactory.getLogger(getClass)

  final private case class PrometheusSection(
    enabled: Option[Boolean],
    port: Option[Int]
  )

  final private case class MetricsSection(
    enabled: Option[Boolean],
    prometheus: Option[PrometheusSection]
  )

  final private case class MetricsRoot(metrics: Option[MetricsSection])

  implicit private val prometheusSectionReader: PureConfigReader[PrometheusSection] =
    PureConfigReader.forProduct2("enabled", "port")(PrometheusSection.apply)

  implicit private val metricsSectionReader: PureConfigReader[MetricsSection] =
    PureConfigReader.forProduct2("enabled", "prometheus")(MetricsSection.apply)

  implicit private val metricsRootReader: PureConfigReader[MetricsRoot] =
    PureConfigReader.forProduct1("metrics")(MetricsRoot.apply)

  /**
   * Load metrics configuration and construct collector with optional endpoint.
   *
   * @param source Configuration source; it must contain an `llm4s` block
   * @return Result containing (MetricsCollector, Option[PrometheusEndpoint])
   */
  def load(source: ConfigSource): Result[(MetricsCollector, Option[PrometheusEndpoint])] = {
    val rootEither = source.at("llm4s").load[MetricsRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s metrics config via PureConfig: $msg")
      }
      .flatMap(buildMetrics)
  }

  /** [[load]] against the current environment: system properties, `application.conf` and every `reference.conf`. */
  def default(): Result[(MetricsCollector, Option[PrometheusEndpoint])] =
    load(ConfigSource.default)

  private def buildMetrics(
    root: MetricsRoot
  ): Result[(MetricsCollector, Option[PrometheusEndpoint])] = {
    val metricsSection = root.metrics.getOrElse(MetricsSection(None, None))
    val metricsEnabled = metricsSection.enabled.getOrElse(false)

    if (!metricsEnabled) {
      logger.info("Metrics disabled (llm4s.metrics.enabled = false)")
      Right((MetricsCollector.noop, None))
    } else {
      val prometheusSection = metricsSection.prometheus.getOrElse(PrometheusSection(None, None))
      val prometheusEnabled = prometheusSection.enabled.getOrElse(true)

      if (!prometheusEnabled) {
        logger.info("Prometheus metrics disabled")
        Right((MetricsCollector.noop, None))
      } else {
        val port = prometheusSection.port.getOrElse(9090)

        logger.info(s"Initializing Prometheus metrics on port $port")

        val metrics = PrometheusMetrics.create()
        PrometheusEndpoint.start(port, metrics.registry).map(endpoint => (metrics, Some(endpoint)))
      }
    }
  }
}
