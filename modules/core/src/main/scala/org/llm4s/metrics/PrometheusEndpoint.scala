package org.llm4s.metrics

import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.exporter.httpserver.HTTPServer
import org.llm4s.types.Result
import org.llm4s.error.ConfigurationError
import org.slf4j.LoggerFactory

/**
 * HTTP endpoint for exposing Prometheus metrics.
 *
 * Wraps the Prometheus HTTPServer and provides lifecycle management.
 * Use this to expose metrics at /metrics for Prometheus scraping.
 *
 * Example:
 * {{{
 * val registry = new PrometheusRegistry()
 * val endpointResult = PrometheusEndpoint.start(9090, registry)
 *
 * endpointResult match {
 *   case Right(endpoint) =>
 *     println(s"Metrics at: \${endpoint.url}")
 *     // ... application runs ...
 *     endpoint.stop()
 *   case Left(error) =>
 *     println(s"Failed to start: \${error.message}")
 * }
 * }}}
 *
 * @param server Underlying HTTP server
 * @param port Port the server is listening on
 */
final class PrometheusEndpoint private (
  private val server: HTTPServer,
  val port: Int
) {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Get the metrics endpoint URL.
   */
  def url: String = s"http://localhost:$port/metrics"

  /**
   * Stop the HTTP server.
   * Safe to call multiple times.
   */
  def stop(): Unit =
    try {
      logger.info(s"Stopping Prometheus metrics endpoint on port $port")
      server.close()
    } catch {
      case e: Exception =>
        logger.warn(s"Error stopping Prometheus endpoint: ${e.getMessage}")
    }
}

object PrometheusEndpoint {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Start Prometheus HTTP endpoint.
   *
   * Creates an HTTP server that exposes the metrics from the given registry
   * at the /metrics endpoint on the specified port.
   *
   * @param port Port to listen on (default: 9090)
   * @param registry Prometheus collector registry containing metrics
   * @return Right(endpoint) on success, Left(error) if port unavailable or other failure
   */
  def start(port: Int, registry: PrometheusRegistry): Result[PrometheusEndpoint] =
    try {
      val server = HTTPServer
        .builder()
        .port(port)
        .registry(registry)
        .buildAndStart()
      // HTTPServer binds immediately, so we can get actual port
      val actualPort = if (port == 0) {
        // When port is 0, OS assigns it - need to get it from the bound server
        try
          server.getPort
        catch {
          case _: Exception => port // fallback to requested port if fails
        }
      } else port

      val endpoint = new PrometheusEndpoint(server, actualPort)

      logger.info(s"Prometheus metrics endpoint started: ${endpoint.url}")

      Right(endpoint)
    } catch {
      case _: java.net.BindException =>
        Left(
          ConfigurationError(
            s"Failed to start Prometheus endpoint on port $port: Port already in use. " +
              s"Make sure no other service is using this port."
          )
        )
      case e: Exception =>
        Left(
          ConfigurationError(
            s"Failed to start Prometheus endpoint on port $port: ${e.getMessage}"
          )
        )
    }
}
