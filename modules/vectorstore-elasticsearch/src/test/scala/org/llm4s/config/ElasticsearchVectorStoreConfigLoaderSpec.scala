package org.llm4s.config

import com.typesafe.config.ConfigFactory
import org.llm4s.error.ConfigurationError
import org.llm4s.vectorstore.elasticsearch.{ ElasticsearchAuth, ElasticsearchConfig, ElasticsearchSimilarity }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

class ElasticsearchVectorStoreConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def load(hocon: String) = ElasticsearchVectorStoreConfigLoader.load(ConfigSource.string(hocon))

  private def refused(hocon: String): String =
    load(hocon).left.value match {
      case c: ConfigurationError => c.message
      case other                 => fail(s"expected a ConfigurationError, got $other")
    }

  "The Elasticsearch config loader" should "default everything when the block is absent" in {
    load("").value shouldBe ElasticsearchConfig()
  }

  it should "read every setting" in {
    val config = load("""
      llm4s.vectorstore.elasticsearch {
        baseUrl        = "https://es.example.com:9243"
        index          = "docs"
        apiKey         = "ZW5jb2RlZA=="
        similarity     = dot_product
        dimensions     = 384
        numCandidates  = 64
        refreshOnWrite = false
        timeout        = 5 seconds
      }
    """).value

    config shouldBe ElasticsearchConfig(
      baseUrl = "https://es.example.com:9243",
      index = "docs",
      auth = ElasticsearchAuth.ApiKey("ZW5jb2RlZA=="),
      similarity = ElasticsearchSimilarity.DotProduct,
      dimensions = Some(384),
      numCandidates = Some(64),
      refreshOnWrite = false,
      timeout = 5.seconds
    )
  }

  it should "read a username and password as basic authentication" in {
    load("""
      llm4s.vectorstore.elasticsearch { username = elastic, password = changeme }
    """).value.auth shouldBe ElasticsearchAuth.Basic("elastic", "changeme")
  }

  it should "treat blank values, as an unset variable binds, as unset" in {
    load("""
      llm4s.vectorstore.elasticsearch { baseUrl = "", index = " ", apiKey = "", similarity = "" }
    """).value shouldBe ElasticsearchConfig()
  }

  it should "refuse ambiguous or incomplete credentials" in {
    refused("llm4s.vectorstore.elasticsearch { apiKey = k, username = u, password = p }") should include("both")
    refused("llm4s.vectorstore.elasticsearch { username = u }") should include("without a password")
    refused("llm4s.vectorstore.elasticsearch { password = p }") should include("without a username")
  }

  it should "refuse an unknown similarity, naming the ones there are" in {
    refused("llm4s.vectorstore.elasticsearch.similarity = euclid") should
      (include("euclid").and(include("cosine, dot_product, l2_norm, max_inner_product")))
  }

  it should "refuse a setting the config refuses, naming the block, and never echo a password" in {
    val message = refused("""
      llm4s.vectorstore.elasticsearch { baseUrl = "http://es.example.com", username = u, password = sekrit }
    """)
    message should (include("llm4s.vectorstore.elasticsearch").and(include("https")))
    (message should not).include("sekrit")
  }

  it should "report an unreadable block with its path" in {
    refused("llm4s.vectorstore.elasticsearch.dimensions = many") should
      (include("llm4s.vectorstore.elasticsearch").and(include("dimensions")))
  }

  it should "load the ambient configuration" in {
    ElasticsearchVectorStoreConfigLoader.default().isRight shouldBe true
  }

  "The module's reference.conf" should "bind the ELASTICSEARCH_* variables to the block the loader reads" in {
    val url = getClass.getClassLoader
      .getResources("reference.conf")
      .asScala
      .find(url => Using.resource(Source.fromURL(url))(_.mkString).contains("ELASTICSEARCH_URL"))
      .getOrElse(fail("no reference.conf binds ELASTICSEARCH_URL"))
    val resolved = ConfigFactory
      .parseURL(url)
      .resolveWith(
        ConfigFactory.parseString(
          """ELASTICSEARCH_URL = "https://es.example.com"
            |ELASTICSEARCH_INDEX = docs
            |ELASTICSEARCH_USERNAME = elastic
            |ELASTICSEARCH_PASSWORD = pw""".stripMargin
        )
      )

    val config = ElasticsearchVectorStoreConfigLoader.load(ConfigSource.fromConfig(resolved)).value
    config.baseUrl shouldBe "https://es.example.com"
    config.index shouldBe "docs"
    config.auth shouldBe ElasticsearchAuth.Basic("elastic", "pw")
  }
}
