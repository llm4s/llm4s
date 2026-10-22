package org.llm4s.config

import com.typesafe.config.ConfigFactory
import org.llm4s.error.ConfigurationError
import org.llm4s.vectorstore.opensearch.{ OpenSearchAuth, OpenSearchConfig, OpenSearchEngine, OpenSearchSpaceType }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*
import scala.io.Source
import scala.jdk.CollectionConverters.*
import scala.util.Using

class OpenSearchVectorStoreConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def load(hocon: String) = OpenSearchVectorStoreConfigLoader.load(ConfigSource.string(hocon))

  private def refused(hocon: String): String =
    load(hocon).left.value match {
      case c: ConfigurationError => c.message
      case other                 => fail(s"expected a ConfigurationError, got $other")
    }

  "The OpenSearch config loader" should "default everything when the block is absent" in {
    load("").value shouldBe OpenSearchConfig()
  }

  it should "read every setting" in {
    val config = load("""
      llm4s.vectorstore.opensearch {
        baseUrl        = "https://search.example.com:9200"
        index          = "docs"
        username       = llm4s
        password       = pw
        spaceType      = innerproduct
        engine         = faiss
        dimensions     = 384
        m              = 24
        efConstruction = 256
        numCandidates  = 64
        refreshOnWrite = false
        timeout        = 5 seconds
      }
    """).value

    config shouldBe OpenSearchConfig(
      baseUrl = "https://search.example.com:9200",
      index = "docs",
      auth = OpenSearchAuth.Basic("llm4s", "pw"),
      spaceType = OpenSearchSpaceType.InnerProduct,
      engine = OpenSearchEngine.Faiss,
      dimensions = Some(384),
      m = Some(24),
      efConstruction = Some(256),
      numCandidates = Some(64),
      refreshOnWrite = false,
      timeout = 5.seconds
    )
  }

  it should "read a token as bearer authentication" in {
    load("""
      llm4s.vectorstore.opensearch { baseUrl = "https://search.example.com", token = "eyJ.abc.def" }
    """).value.auth shouldBe OpenSearchAuth.BearerToken("eyJ.abc.def")
  }

  it should "treat blank values, as an unset variable binds, as unset" in {
    load("""
      llm4s.vectorstore.opensearch { baseUrl = "", index = " ", token = "", spaceType = "", engine = "" }
    """).value shouldBe OpenSearchConfig()
  }

  it should "refuse ambiguous or incomplete credentials" in {
    refused("llm4s.vectorstore.opensearch { token = t, username = u, password = p }") should include("both")
    refused("llm4s.vectorstore.opensearch { username = u }") should include("without a password")
    refused("llm4s.vectorstore.opensearch { password = p }") should include("without a username")
  }

  it should "refuse an unknown space type or engine, naming the ones there are" in {
    refused("llm4s.vectorstore.opensearch.spaceType = l1") should
      (include("'l1'").and(include("cosinesimil, innerproduct, l2")))
    refused("llm4s.vectorstore.opensearch.engine = jvector") should
      (include("jvector").and(include("lucene, faiss, nmslib")))
  }

  it should "refuse a setting the config refuses, naming the block, and never echo a password" in {
    val message = refused("""
      llm4s.vectorstore.opensearch { baseUrl = "http://search.example.com", username = u, password = sekrit }
    """)
    message should (include("llm4s.vectorstore.opensearch").and(include("https")))
    (message should not).include("sekrit")
  }

  it should "report an unreadable block with its path" in {
    refused("llm4s.vectorstore.opensearch.dimensions = many") should
      (include("llm4s.vectorstore.opensearch").and(include("dimensions")))
  }

  it should "load the ambient configuration" in {
    OpenSearchVectorStoreConfigLoader.default().isRight shouldBe true
  }

  "The module's reference.conf" should "bind the OPENSEARCH_* variables to the block the loader reads" in {
    val url = getClass.getClassLoader
      .getResources("reference.conf")
      .asScala
      .find(url => Using.resource(Source.fromURL(url))(_.mkString).contains("OPENSEARCH_URL"))
      .getOrElse(fail("no reference.conf binds OPENSEARCH_URL"))
    def resolve(vars: String) =
      OpenSearchVectorStoreConfigLoader
        .load(ConfigSource.fromConfig(ConfigFactory.parseURL(url).resolveWith(ConfigFactory.parseString(vars))))
        .value

    val basic = resolve(
      """OPENSEARCH_URL = "https://search.example.com"
        |OPENSEARCH_INDEX = docs
        |OPENSEARCH_USERNAME = admin
        |OPENSEARCH_PASSWORD = pw""".stripMargin
    )
    basic.baseUrl shouldBe "https://search.example.com"
    basic.index shouldBe "docs"
    basic.auth shouldBe OpenSearchAuth.Basic("admin", "pw")

    resolve("""OPENSEARCH_URL = "https://search.example.com"
              |OPENSEARCH_TOKEN = jwt""".stripMargin).auth shouldBe OpenSearchAuth.BearerToken("jwt")
  }
}
