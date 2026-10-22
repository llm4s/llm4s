package org.llm4s.vectorstore.elasticsearch

import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class ElasticsearchConfigSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def refused(config: ElasticsearchConfig): String =
    config.validate.left.value match {
      case c: ConfigurationError => c.message
      case other                 => fail(s"expected a ConfigurationError, got $other")
    }

  "An ElasticsearchConfig" should "default to a local cluster without credentials" in {
    val config = ElasticsearchConfig()

    config.validate.value shouldBe config
    config.baseUrl shouldBe "http://localhost:9200"
    config.index shouldBe "llm4s-vectors"
    config.auth shouldBe ElasticsearchAuth.NoAuth
    config.similarity shouldBe ElasticsearchSimilarity.Cosine
    config.dimensions shouldBe None
    config.numCandidates shouldBe None
    config.refreshOnWrite shouldBe true
    config.timeout shouldBe 30.seconds
  }

  it should "set every field through its setter" in {
    val config = ElasticsearchConfig()
      .withBaseUrl("https://es.example.com/proxy/")
      .withIndex("docs")
      .withAuth(ElasticsearchAuth.Basic("elastic", "pw"))
      .withSimilarity(ElasticsearchSimilarity.L2Norm)
      .withDimensions(8)
      .withNumCandidates(40)
      .withRefreshOnWrite(false)
      .withTimeout(5.seconds)

    config.validate.value.root shouldBe "https://es.example.com/proxy"
    config.withDimensions(None).dimensions shouldBe None
    config.withNumCandidates(None).numCandidates shouldBe None
    (config.toString should not).include("pw")
  }

  it should "refuse credentials over plain http to a remote host, and accept them for loopback or https" in {
    val apiKey = ElasticsearchAuth.ApiKey("key")
    refused(ElasticsearchConfig("http://es.example.com:9200", auth = apiKey)) should include("must be https")
    ElasticsearchConfig("http://127.0.0.1:9200", auth = apiKey).validate.isRight shouldBe true
    ElasticsearchConfig("http://[::1]:9200", auth = apiKey).validate.isRight shouldBe true
    ElasticsearchConfig("https://es.example.com", auth = apiKey).validate.isRight shouldBe true
    ElasticsearchConfig("http://elasticsearch:9200").validate.isRight shouldBe true
  }

  it should "refuse a base URL that cannot be one" in {
    refused(ElasticsearchConfig("http://exa mple")) should include("whitespace")
    refused(ElasticsearchConfig("http://[bad")) should include("not a valid URL")
    refused(ElasticsearchConfig("localhost:9200")) should include("absolute URL")
    refused(ElasticsearchConfig("ftp://localhost")) should include("http or https")
    refused(ElasticsearchConfig("https://user:pw@es.example.com")) should include("must not carry credentials")
    refused(ElasticsearchConfig("https://es.example.com?x=1")) should include("query")
  }

  it should "refuse an index name Elasticsearch would refuse" in {
    refused(ElasticsearchConfig(index = "")) should include("empty")
    refused(ElasticsearchConfig(index = "..")) should include("reserved")
    refused(ElasticsearchConfig(index = "Docs")) should include("lower case")
    refused(ElasticsearchConfig(index = "a/b")) should include("forbids")
    refused(ElasticsearchConfig(index = "a b")) should include("forbids")
    refused(ElasticsearchConfig(index = "_docs")) should include("must not start")
    refused(ElasticsearchConfig(index = "a" * 256)) should include("255 bytes")
  }

  it should "refuse unusable credentials and limits" in {
    refused(ElasticsearchConfig(auth = ElasticsearchAuth.ApiKey(" "))) should include("apiKey")
    refused(ElasticsearchConfig(auth = ElasticsearchAuth.Basic("a:b", "pw"))) should include("username")
    refused(ElasticsearchConfig(dimensions = Some(0))) should include("dimensions")
    refused(ElasticsearchConfig(dimensions = Some(5000))) should include("dimensions")
    refused(ElasticsearchConfig(numCandidates = Some(0))) should include("numCandidates")
    refused(ElasticsearchConfig(numCandidates = Some(10001))) should include("numCandidates")
    refused(ElasticsearchConfig(timeout = Duration.Zero)) should include("timeout")
  }

  "ElasticsearchAuth" should "never print a credential" in {
    ElasticsearchAuth.ApiKey("secret-key").toString shouldBe "ApiKey(***)"
    ElasticsearchAuth.Basic("elastic", "secret-pw").toString shouldBe "Basic(elastic, ***)"
    ElasticsearchAuth.NoAuth.headers shouldBe empty
    ElasticsearchAuth.NoAuth.secrets shouldBe empty
  }

  "ElasticsearchSimilarity" should "be read from its mapping name, ignoring case" in {
    ElasticsearchSimilarity.fromString("cosine") shouldBe Some(ElasticsearchSimilarity.Cosine)
    ElasticsearchSimilarity.fromString(" DOT_PRODUCT ") shouldBe Some(ElasticsearchSimilarity.DotProduct)
    ElasticsearchSimilarity.fromString("l2_norm") shouldBe Some(ElasticsearchSimilarity.L2Norm)
    ElasticsearchSimilarity.fromString("max_inner_product") shouldBe Some(ElasticsearchSimilarity.MaxInnerProduct)
    ElasticsearchSimilarity.fromString("euclid") shouldBe None
  }
}
