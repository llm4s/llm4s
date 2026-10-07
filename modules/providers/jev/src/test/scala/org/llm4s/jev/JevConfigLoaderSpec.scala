package org.llm4s.jev

import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

class JevConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def load(hocon: String) = JevConfigLoader.load(ConfigSource.string(hocon))

  private def refused(hocon: String): String =
    load(hocon).left.value match {
      case c: ConfigurationError => c.message
      case other                 => fail(s"expected a ConfigurationError, got $other")
    }

  "The Jev config loader" should "read the key from the shared credentials and default everything else" in {
    val config = load("llm4s.credentials.jev.apiKey = tsk-shared").value

    config shouldBe JevConfig("tsk-shared")
  }

  it should "read the key from llm4s.jev itself" in {
    load("llm4s.jev.apiKey = tsk-own").value.apiKey shouldBe "tsk-own"
  }

  it should "prefer the client's own key to the shared one" in {
    load("""
      llm4s.credentials.jev.apiKey = tsk-shared
      llm4s.jev.apiKey = tsk-own
    """).value.apiKey shouldBe "tsk-own"
  }

  it should "treat a blank own key as unset and fall back to the shared one" in {
    load("""
      llm4s.credentials.jev.apiKey = tsk-shared
      llm4s.jev.apiKey = "  "
    """).value.apiKey shouldBe "tsk-shared"
  }

  it should "say which variable and which setting provide the key, when there is none" in {
    val message = refused("llm4s.jev.model = jev-latest")

    message should (include("Jev apiKey").and(include("TYPESAFE_API_KEY")).and(include("apiKey under llm4s.jev")))
  }

  it should "read every setting" in {
    val config = load("""
      llm4s.credentials.jev.apiKey = tsk
      llm4s.jev {
        baseUrl = "https://gw.example/jev"
        model   = "jev-1.13.0"
        timeout = 12 seconds
        headers { X-Team = platform }
        retry {
          maxRetries     = 4
          backoffInitial = 100 milliseconds
          backoffMax     = 2 seconds
          jitter         = 0.5
          budget         = 45 seconds
        }
      }
    """).value

    config shouldBe JevConfig(
      apiKey = "tsk",
      baseUrl = "https://gw.example/jev",
      model = "jev-1.13.0",
      timeout = 12.seconds,
      retry = JevRetryPolicy(4, 100.millis, 2.seconds, 0.5, 45.seconds),
      headers = Map("X-Team" -> "platform")
    )
  }

  it should "keep the default of a retry setting that is not set" in {
    val retry = load("""
      llm4s.credentials.jev.apiKey = tsk
      llm4s.jev.retry.maxRetries = 0
    """).value.retry

    retry shouldBe JevRetryPolicy.default.withMaxRetries(0)
  }

  it should "treat a blank base URL or model, as an unset variable binds, as the default" in {
    val config = load("""
      llm4s.credentials.jev.apiKey = tsk
      llm4s.jev { baseUrl = "", model = "  " }
    """).value

    config.baseUrl shouldBe JevConfig.DefaultBaseUrl
    config.model shouldBe JevConfig.DefaultModel
  }

  it should "refuse a plain-http base URL for a remote host, so the key cannot go out in the clear" in {
    refused("""
      llm4s.credentials.jev.apiKey = tsk
      llm4s.jev.baseUrl = "http://evil.example"
    """) should include("must be https")
  }

  it should "accept a loopback http base URL, as a local test server needs" in {
    load("""
      llm4s.credentials.jev.apiKey = tsk
      llm4s.jev.baseUrl = "http://localhost:8080"
    """).value.baseUrl shouldBe "http://localhost:8080"
  }

  it should "refuse a setting that cannot work" in {
    refused("""
      llm4s.credentials.jev.apiKey = tsk
      llm4s.jev.retry.jitter = 3
    """) should include("llm4s.jev.retry.jitter")
    refused("""
      llm4s.credentials.jev.apiKey = tsk
      llm4s.jev.timeout = 0 seconds
    """) should include("llm4s.jev.timeout")
  }

  it should "report an unreadable block with its path, not a stack trace" in {
    refused("""
      llm4s.credentials.jev.apiKey = tsk
      llm4s.jev.timeout = "soon"
    """) should (include("llm4s.jev").and(include("timeout")))
  }

  it should "build a client from the same source, and fail like the loader without a key" in {
    JevClient.fromConfig(ConfigSource.string("llm4s.credentials.jev.apiKey = tsk")).isRight shouldBe true
    JevClient.fromConfig(ConfigSource.string("llm4s.jev.model = m")).left.value shouldBe a[ConfigurationError]
  }

  it should "never put the key in an error about another setting" in {
    (refused("""
      llm4s.credentials.jev.apiKey = tsk-very-secret
      llm4s.jev.baseUrl = "http://evil.example"
    """) should not).include("tsk-very-secret")
  }

  "The module's reference.conf" should "bind the variables TypeSafe's own SDKs read" in {
    // Every reference.conf on the classpath (core's and each module's), since HOCON merges them.
    val text = getClass.getClassLoader
      .getResources("reference.conf")
      .asScala
      .map(url => Using.resource(Source.fromURL(url))(_.mkString))
      .mkString("\n")

    text should include("apiKey = ${?TYPESAFE_API_KEY}")
    text should include("baseUrl = ${?TYPESAFE_BASE_URL}")
    text should include("model   = ${?TYPESAFE_DEFAULT_MODEL}")
  }
}
