package org.llm4s.vectorstore.opensearch

import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class OpenSearchConfigSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def refused(config: OpenSearchConfig): String =
    config.validate.left.value match {
      case c: ConfigurationError => c.message
      case other                 => fail(s"expected a ConfigurationError, got $other")
    }

  "An OpenSearchConfig" should "default to a local cluster without credentials, cosinesimil on Lucene" in {
    val config = OpenSearchConfig()

    config.validate.value shouldBe config
    config.baseUrl shouldBe "http://localhost:9200"
    config.index shouldBe "llm4s-vectors"
    config.auth shouldBe OpenSearchAuth.NoAuth
    config.spaceType shouldBe OpenSearchSpaceType.CosineSimil
    config.engine shouldBe OpenSearchEngine.Lucene
    config.dimensions shouldBe None
    config.m shouldBe None
    config.efConstruction shouldBe None
    config.numCandidates shouldBe None
    config.refreshOnWrite shouldBe true
    config.timeout shouldBe 30.seconds
  }

  it should "set every field through its setter" in {
    val config = OpenSearchConfig()
      .withBaseUrl("https://search.example.com/proxy/")
      .withIndex("docs")
      .withAuth(OpenSearchAuth.Basic("admin", "pw"))
      .withSpaceType(OpenSearchSpaceType.L2)
      .withEngine(OpenSearchEngine.Faiss)
      .withDimensions(8)
      .withM(32)
      .withEfConstruction(200)
      .withNumCandidates(40)
      .withRefreshOnWrite(false)
      .withTimeout(5.seconds)

    val valid = config.validate.value
    valid.root shouldBe "https://search.example.com/proxy"
    valid.spaceType shouldBe OpenSearchSpaceType.L2
    valid.engine shouldBe OpenSearchEngine.Faiss
    valid.dimensions shouldBe Some(8)
    valid.m shouldBe Some(32)
    valid.efConstruction shouldBe Some(200)
    valid.numCandidates shouldBe Some(40)
    valid.refreshOnWrite shouldBe false
    valid.timeout shouldBe 5.seconds
    config.withDimensions(None).dimensions shouldBe None
    config.withM(None).m shouldBe None
    config.withEfConstruction(None).efConstruction shouldBe None
    config.withNumCandidates(None).numCandidates shouldBe None
    (config.toString should not).include("pw")
  }

  it should "refuse credentials over plain http to a remote host, and accept them for loopback or https" in {
    val basic = OpenSearchAuth.Basic("admin", "pw")
    refused(OpenSearchConfig("http://search.example.com:9200", auth = basic)) should
      (include("OpenSearch baseUrl").and(include("must be https")))
    OpenSearchConfig("http://localhost:9200", auth = basic).validate.isRight shouldBe true
    OpenSearchConfig("https://search.example.com", auth = OpenSearchAuth.BearerToken("t")).validate.isRight shouldBe
      true
    OpenSearchConfig("http://opensearch:9200").validate.isRight shouldBe true
    refused(OpenSearchConfig("https://user:pw@search.example.com")) should include("must not carry credentials")
  }

  it should "refuse an index name OpenSearch would refuse" in {
    refused(OpenSearchConfig(index = "Docs")) should (include("OpenSearch index name").and(include("lower case")))
    refused(OpenSearchConfig(index = "a*b")) should include("OpenSearch forbids")
  }

  it should "refuse unusable credentials and limits" in {
    refused(OpenSearchConfig(auth = OpenSearchAuth.BearerToken(" "))) should include("token")
    refused(OpenSearchConfig(auth = OpenSearchAuth.BearerToken("a b"))) should include("token")
    refused(OpenSearchConfig(auth = OpenSearchAuth.Basic("", "pw"))) should include("username")
    refused(OpenSearchConfig(auth = OpenSearchAuth.Basic("a:b", "pw"))) should include("username")
    refused(OpenSearchConfig(dimensions = Some(0))) should include("dimensions")
    refused(OpenSearchConfig(dimensions = Some(16001))) should include("dimensions")
    OpenSearchConfig(dimensions = Some(16000)).validate.isRight shouldBe true
    refused(OpenSearchConfig(numCandidates = Some(0))) should include("numCandidates")
    refused(OpenSearchConfig(numCandidates = Some(10001))) should include("numCandidates")
    refused(OpenSearchConfig(m = Some(0))) should include("m must be positive")
    refused(OpenSearchConfig(efConstruction = Some(-1))) should include("efConstruction")
    refused(OpenSearchConfig(timeout = Duration.Zero)) should include("timeout")
  }

  "OpenSearchAuth" should "never print a credential" in {
    OpenSearchAuth.BearerToken("secret-token").toString shouldBe "BearerToken(***)"
    OpenSearchAuth.Basic("admin", "secret-pw").toString shouldBe "Basic(admin, ***)"
    OpenSearchAuth.NoAuth.headers shouldBe empty
    OpenSearchAuth.NoAuth.secrets shouldBe empty
  }

  "OpenSearchSpaceType and OpenSearchEngine" should "be read from their mapping names, ignoring case" in {
    OpenSearchSpaceType.fromString("cosinesimil") shouldBe Some(OpenSearchSpaceType.CosineSimil)
    OpenSearchSpaceType.fromString(" InnerProduct ") shouldBe Some(OpenSearchSpaceType.InnerProduct)
    OpenSearchSpaceType.fromString("l2") shouldBe Some(OpenSearchSpaceType.L2)
    OpenSearchSpaceType.fromString("l1") shouldBe None
    OpenSearchEngine.fromString("LUCENE") shouldBe Some(OpenSearchEngine.Lucene)
    OpenSearchEngine.fromString("faiss") shouldBe Some(OpenSearchEngine.Faiss)
    OpenSearchEngine.fromString("nmslib") shouldBe Some(OpenSearchEngine.Nmslib)
    OpenSearchEngine.fromString("jvector") shouldBe None
    OpenSearchEngine.values.filter(_.filtersInsideKnn) shouldBe Array(OpenSearchEngine.Lucene, OpenSearchEngine.Faiss)
  }

  "Score normalisation" should "keep cosinesimil's (1 + cos) / 2, which every engine scores" in {
    OpenSearchSpaceType.CosineSimil.normalise(0.75) shouldBe 0.75
  }

  it should "map innerproduct onto (1 + dot) / 2 from either branch of OpenSearch's score" in {
    val ip = OpenSearchSpaceType.InnerProduct
    ip.normalise(2.0) shouldBe 1.0 +- 1e-9   // dot = 1: dot + 1
    ip.normalise(1.0) shouldBe 0.5 +- 1e-9   // dot = 0
    ip.normalise(0.5) shouldBe 0.0 +- 1e-9   // dot = -1: 1 / (1 - dot)
    ip.normalise(0.8) shouldBe 0.375 +- 1e-9 // dot = -0.25
    ip.normalise(0.0) shouldBe Double.NegativeInfinity
  }

  it should "keep l2's score, already in (0, 1]" in {
    OpenSearchSpaceType.L2.normalise(0.2) shouldBe 0.2
  }
}
