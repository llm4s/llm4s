package org.llm4s.config

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
}
