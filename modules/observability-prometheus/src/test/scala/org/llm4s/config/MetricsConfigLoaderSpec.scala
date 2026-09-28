package org.llm4s.config

import org.llm4s.metrics.{ MetricsCollector, PrometheusMetrics }
import org.llm4s.testutil.ReferenceConfig
import org.scalatest.funsuite.AnyFunSuite
import pureconfig.ConfigSource
import com.typesafe.config.ConfigFactory

class MetricsConfigLoaderSpec extends AnyFunSuite {

  test("MetricsConfigLoader creates noop collector when metrics disabled") {
    val config = ConfigFactory.parseString("""
      llm4s.metrics {
        enabled = false
      }
    """)

    val result = MetricsConfigLoader.load(ConfigSource.fromConfig(config))

    assert(result.isRight)
    result.foreach { case (collector, endpoint) =>
      // Noop collector should be returned
      assert(collector != null)
      assert(endpoint.isEmpty)
    }
  }

  test("MetricsConfigLoader creates prometheus collector when enabled") {
    val config = ConfigFactory.parseString("""
      llm4s.metrics {
        enabled = true
        prometheus {
          enabled = true
          port = 0
        }
      }
    """)

    val result = MetricsConfigLoader.load(ConfigSource.fromConfig(config))

    assert(result.isRight)
    result.foreach { case (collector, endpoint) =>
      assert(collector != null)
      assert(endpoint.isDefined)
      // Clean up endpoint to release port
      endpoint.foreach(_.stop())
    }
  }

  test("MetricsConfigLoader handles missing config gracefully") {
    val config = ConfigFactory.empty()

    val result = MetricsConfigLoader.load(ConfigSource.fromConfig(config))

    // Should fail when config is missing (no llm4s.metrics section)
    assert(result.isLeft)
  }

  // The llm4s.metrics block moved into this module's reference.conf with the loader (#1133);
  // before, core had no such block and these defaults were hard-coded in the loader.

  test("reference.conf leaves metrics off, so an application that sets nothing gets noop") {
    val result = MetricsConfigLoader.load(ReferenceConfig.withEnv("", Map.empty))

    assert(result == Right((MetricsCollector.noop, None)))
  }

  test("reference.conf defaults the Prometheus backend on once metrics are enabled") {
    val result = MetricsConfigLoader.load(
      ReferenceConfig.withEnv("llm4s.metrics { enabled = true, prometheus.port = 0 }", Map.empty)
    )

    assert(result.isRight)
    result.foreach { case (collector, endpoint) =>
      assert(collector.isInstanceOf[PrometheusMetrics])
      assert(endpoint.isDefined)
      endpoint.foreach(_.stop())
    }
  }
}
