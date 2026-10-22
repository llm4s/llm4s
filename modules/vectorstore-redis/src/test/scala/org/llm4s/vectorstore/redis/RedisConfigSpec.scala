package org.llm4s.vectorstore.redis

import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class RedisConfigSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def refused(config: RedisConfig): String =
    config.validate.left.value match {
      case c: ConfigurationError => c.message
      case other                 => fail(s"expected a ConfigurationError, got $other")
    }

  "RedisConfig" should "default to a local server without authentication" in {
    val config = RedisConfig()
    config.host shouldBe "localhost"
    config.port shouldBe 6379
    config.tls shouldBe false
    config.database shouldBe 0
    config.username shouldBe None
    config.password shouldBe None
    config.index shouldBe "llm4s-vectors"
    config.effectivePrefix shouldBe "llm4s-vectors:"
    config.algorithm shouldBe RedisVectorAlgorithm.Hnsw
    config.distanceMetric shouldBe RedisDistanceMetric.Cosine
    config.dimensions shouldBe None
    config.timeout shouldBe 10.seconds
    config.validate.value shouldBe config
  }

  it should "set every field with its with* setter" in {
    val config = RedisConfig()
      .withHost("redis.example.com")
      .withPort(6380)
      .withTls(true)
      .withDatabase(2)
      .withUsername("app")
      .withPassword("secret")
      .withIndex("docs")
      .withKeyPrefix("d:")
      .withAlgorithm(RedisVectorAlgorithm.Flat)
      .withDistanceMetric(RedisDistanceMetric.InnerProduct)
      .withDimensions(8)
      .withTimeout(2.seconds)

    config shouldBe RedisConfig(
      "redis.example.com",
      6380,
      true,
      2,
      Some("app"),
      Some("secret"),
      "docs",
      Some("d:"),
      RedisVectorAlgorithm.Flat,
      RedisDistanceMetric.InnerProduct,
      Some(8),
      2.seconds
    )
    config.effectivePrefix shouldBe "d:"
    config.withUsername(None).withPassword(None).withKeyPrefix(None).withDimensions(None) shouldBe
      RedisConfig(
        "redis.example.com",
        6380,
        true,
        2,
        None,
        None,
        "docs",
        None,
        RedisVectorAlgorithm.Flat,
        RedisDistanceMetric.InnerProduct,
        None,
        2.seconds
      )
    config.validate.isRight shouldBe true
  }

  it should "never print the password" in {
    val shown = RedisConfig(username = Some("app"), password = Some("sekrit")).toString
    (shown should not).include("sekrit")
    shown should (include("***").and(include("app")))
    RedisConfig().toString should (include("password=none").and(include("dimensions=auto")))
    RedisConfig(dimensions = Some(3)).toString should include("dimensions=3")
  }

  it should "refuse each invalid setting, naming it" in {
    refused(RedisConfig(host = "")) should include("host")
    refused(RedisConfig(host = "a b")) should include("host")
    refused(RedisConfig(port = 0)) should include("port")
    refused(RedisConfig(port = 70000)) should include("port")
    refused(RedisConfig(database = -1)) should include("database")
    refused(RedisConfig(username = Some(""), password = Some("p"))) should include("username")
    refused(RedisConfig(username = Some("u"))) should include("without a password")
    refused(RedisConfig(password = Some(""))) should include("password")
    refused(RedisConfig(index = "")) should include("index")
    refused(RedisConfig(index = "a\tb")) should include("index")
    refused(RedisConfig(keyPrefix = Some(""))) should include("keyPrefix")
    refused(RedisConfig(dimensions = Some(0))) should include("dimensions")
    refused(RedisConfig(dimensions = Some(RedisConfig.MaxDimensions + 1))) should include("dimensions")
    refused(RedisConfig(timeout = Duration.Zero)) should include("timeout")
    refused(RedisConfig(timeout = 30.days)) should include("timeout")
  }

  it should "accept a password without a username" in {
    RedisConfig(password = Some("p")).validate.isRight shouldBe true
  }
}
