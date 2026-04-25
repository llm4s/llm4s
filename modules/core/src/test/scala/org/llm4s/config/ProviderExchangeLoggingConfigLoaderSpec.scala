package org.llm4s.config

import com.typesafe.config.ConfigFactory
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.scalatest.funsuite.AnyFunSuite
import pureconfig.ConfigSource

class ProviderExchangeLoggingConfigLoaderSpec extends AnyFunSuite:

  test("ProviderExchangeLoggingConfigLoader disables exchange logging when config is absent") {
    val result = ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(ConfigFactory.empty()))

    assert(result == Right(ProviderExchangeLogging.Disabled))
  }

  test("ProviderExchangeLoggingConfigLoader disables exchange logging when explicitly disabled") {
    val config = ConfigFactory.parseString("""
      llm4s.exchangeLogging {
        enabled = false
      }
    """)

    val result = ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(config))

    assert(result == Right(ProviderExchangeLogging.Disabled))
  }

  test("ProviderExchangeLoggingConfigLoader enables exchange logging when configured with a dir") {
    val config = ConfigFactory.parseString("""
      llm4s.exchangeLogging {
        enabled = true
        dir = "/tmp/provider-exchanges"
      }
    """)

    val result = ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(config))

    result match
      case Right(ProviderExchangeLogging.Enabled(_)) => ()
      case other                                     => fail(s"Expected enabled JSONL sink, got $other")
  }

  test("ProviderExchangeLoggingConfigLoader fails when enabled without a dir") {
    val config = ConfigFactory.parseString("""
      llm4s.exchangeLogging {
        enabled = true
      }
    """)

    val result = ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(config))

    assert(result.isLeft)
    assert(
      result.left.toOption.exists(
        _.message == "Provider exchange logging is enabled but llm4s.exchangeLogging.dir is missing"
      )
    )
  }

  test("ProviderExchangeLoggingConfigLoader fails when enabled with a blank dir") {
    val config = ConfigFactory.parseString("""
      llm4s.exchangeLogging {
        enabled = true
        dir = "   "
      }
    """)

    val result = ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(config))

    assert(result.isLeft)
    assert(
      result.left.toOption.exists(
        _.message == "Provider exchange logging is enabled but llm4s.exchangeLogging.dir is missing"
      )
    )
  }
