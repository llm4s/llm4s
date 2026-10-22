package org.llm4s.config

import com.typesafe.config.ConfigFactory
import org.llm4s.error.ConfigurationError
import org.llm4s.vectorstore.redis.{ RedisConfig, RedisDistanceMetric, RedisVectorAlgorithm }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

class RedisVectorStoreConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def load(hocon: String) = RedisVectorStoreConfigLoader.load(ConfigSource.string(hocon))

  private def refused(hocon: String): String =
    load(hocon).left.value match {
      case c: ConfigurationError => c.message
      case other                 => fail(s"expected a ConfigurationError, got $other")
    }

  "The Redis config loader" should "default everything when the block is absent" in {
    load("").value shouldBe RedisConfig()
  }

  it should "read every setting" in {
    val config = load("""
      llm4s.vectorstore.redis {
        host           = "redis.example.com"
        port           = 6380
        tls            = true
        database       = 1
        username       = app
        password       = secret
        index          = docs
        keyPrefix      = "d:"
        algorithm      = flat
        distanceMetric = IP
        dimensions     = 384
        timeout        = 5 seconds
      }
    """).value

    config shouldBe RedisConfig(
      host = "redis.example.com",
      port = 6380,
      tls = true,
      database = 1,
      username = Some("app"),
      password = Some("secret"),
      index = "docs",
      keyPrefix = Some("d:"),
      algorithm = RedisVectorAlgorithm.Flat,
      distanceMetric = RedisDistanceMetric.InnerProduct,
      dimensions = Some(384),
      timeout = 5.seconds
    )
  }

  it should "treat blank values, as an unset variable binds, as unset" in {
    load("""
      llm4s.vectorstore.redis { host = "", index = " ", username = "", password = "", algorithm = "", distanceMetric = "" }
    """).value shouldBe RedisConfig()
  }

  it should "refuse an unknown algorithm or distance metric, naming the ones there are" in {
    refused("llm4s.vectorstore.redis.algorithm = ivf") should (include("ivf").and(include("HNSW, FLAT")))
    refused("llm4s.vectorstore.redis.distanceMetric = manhattan") should
      (include("manhattan").and(include("COSINE, IP, L2")))
  }

  it should "refuse a setting the config refuses, naming the block, and never echo a password" in {
    val message = refused("llm4s.vectorstore.redis { username = u, port = 0, password = sekrit }")
    message should (include("llm4s.vectorstore.redis").and(include("port")))
    (message should not).include("sekrit")
  }

  it should "report an unreadable block with its path" in {
    refused("llm4s.vectorstore.redis.port = many") should (include("llm4s.vectorstore.redis").and(include("port")))
  }

  it should "load the ambient configuration" in {
    RedisVectorStoreConfigLoader.default().isRight shouldBe true
  }

  "The module's reference.conf" should "bind the REDIS_* variables to the block the loader reads" in {
    val url = getClass.getClassLoader
      .getResources("reference.conf")
      .asScala
      .find(url => Using.resource(Source.fromURL(url))(_.mkString).contains("REDIS_VECTOR_INDEX"))
      .getOrElse(fail("no reference.conf binds REDIS_VECTOR_INDEX"))
    val resolved = ConfigFactory
      .parseURL(url)
      .resolveWith(
        ConfigFactory.parseString(
          """REDIS_HOST = redis.internal
            |REDIS_PORT = "6390"
            |REDIS_TLS = "true"
            |REDIS_USERNAME = app
            |REDIS_PASSWORD = pw
            |REDIS_VECTOR_INDEX = docs""".stripMargin
        )
      )

    val config = RedisVectorStoreConfigLoader.load(ConfigSource.fromConfig(resolved)).value
    config.host shouldBe "redis.internal"
    config.port shouldBe 6390
    config.tls shouldBe true
    config.username shouldBe Some("app")
    config.password shouldBe Some("pw")
    config.index shouldBe "docs"
  }
}
