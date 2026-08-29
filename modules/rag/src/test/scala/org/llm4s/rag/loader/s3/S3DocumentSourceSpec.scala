package org.llm4s.rag.loader.s3

import org.llm4s.rag.loader.DocumentVersion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class S3DocumentSourceSpec extends AnyFlatSpec with Matchers {

  // ========== Configuration Tests ==========

  "S3DocumentSource" should "use default extensions" in {
    val source = S3DocumentSource("my-bucket")

    source.extensions should contain("txt")
    source.extensions should contain("pdf")
    source.extensions should contain("docx")
    source.extensions should contain("md")
    source.extensions should contain("json")
  }

  it should "allow custom extensions" in {
    val source = S3DocumentSource("my-bucket", extensions = Set("pdf", "docx"))

    source.extensions shouldBe Set("pdf", "docx")
  }

  it should "use default region" in {
    val source = S3DocumentSource("my-bucket")

    source.region shouldBe "us-east-1"
  }

  it should "allow custom region" in {
    val source = S3DocumentSource("my-bucket", region = "eu-west-1")

    source.region shouldBe "eu-west-1"
  }

  it should "use empty prefix by default" in {
    val source = S3DocumentSource("my-bucket")

    source.prefix shouldBe ""
  }

  it should "allow custom prefix" in {
    val source = S3DocumentSource("my-bucket", prefix = "docs/2024/")

    source.prefix shouldBe "docs/2024/"
  }

  it should "allow endpoint override for LocalStack" in {
    val source = S3DocumentSource("my-bucket", endpointOverride = Some("http://localhost:4566"))

    source.endpointOverride shouldBe Some("http://localhost:4566")
  }

  // ========== Factory Method Tests ==========

  "S3DocumentSource.withCredentials" should "create source with explicit credentials" in {
    val source = S3DocumentSource.withCredentials(
      bucket = "private-bucket",
      accessKeyId = "AKIAIOSFODNN7EXAMPLE",
      secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
      region = "us-west-2",
      prefix = "data/"
    )

    source.bucket shouldBe "private-bucket"
    source.region shouldBe "us-west-2"
    source.prefix shouldBe "data/"
    source.credentials shouldBe defined
  }

  "S3DocumentSource.forLocalStack" should "create source configured for LocalStack" in {
    val source = S3DocumentSource.forLocalStack("test-bucket", "prefix/")

    source.bucket shouldBe "test-bucket"
    source.prefix shouldBe "prefix/"
    source.region shouldBe "us-east-1"
    source.endpointOverride shouldBe Some("http://localhost:4566")
    source.credentials shouldBe defined
  }

  it should "allow custom port" in {
    val source = S3DocumentSource.forLocalStack("test-bucket", port = 4510)

    source.endpointOverride shouldBe Some("http://localhost:4510")
  }

  // ========== Fluent API Tests ==========

  "S3DocumentSource.withPrefix" should "create copy with new prefix" in {
    val source1 = S3DocumentSource("bucket", prefix = "docs/")
    val source2 = source1.withPrefix("reports/")

    source1.prefix shouldBe "docs/"
    source2.prefix shouldBe "reports/"
    source2.bucket shouldBe "bucket"
  }

  "S3DocumentSource.withExtensions" should "create copy with new extensions" in {
    val source1 = S3DocumentSource("bucket")
    val source2 = source1.withExtensions(Set("pdf"))

    source1.extensions should contain("txt")
    source2.extensions shouldBe Set("pdf")
  }

  "S3DocumentSource.withMetadata" should "create copy with additional metadata" in {
    val source1 = S3DocumentSource("bucket", metadata = Map("env" -> "prod"))
    val source2 = source1.withMetadata(Map("team" -> "data"))

    source1.metadata shouldBe Map("env" -> "prod")
    source2.metadata shouldBe Map("env" -> "prod", "team" -> "data")
  }

  "S3DocumentSource.withEndpoint" should "create copy with endpoint override" in {
    val source1 = S3DocumentSource("bucket")
    val source2 = source1.withEndpoint("http://minio:9000")

    source1.endpointOverride shouldBe None
    source2.endpointOverride shouldBe Some("http://minio:9000")
  }

  // ========== Description Tests ==========

  "S3DocumentSource.description" should "include bucket and prefix" in {
    val source = S3DocumentSource("my-bucket", prefix = "docs/")

    source.description should include("my-bucket")
    source.description should include("docs/")
    source.description should include("S3")
  }

  it should "handle empty prefix" in {
    val source = S3DocumentSource("my-bucket")

    source.description should include("my-bucket")
  }

  // ========== Estimated Count ==========

  "S3DocumentSource.estimatedCount" should "return None (S3 doesn't provide cheap count)" in {
    val source = S3DocumentSource("bucket")

    source.estimatedCount shouldBe None
  }

  // ========== Default Extensions ==========

  "S3DocumentSource.defaultExtensions" should "include common document types" in {
    val exts = S3DocumentSource.defaultExtensions

    exts should contain("txt")
    exts should contain("md")
    exts should contain("markdown")
    exts should contain("pdf")
    exts should contain("docx")
    exts should contain("doc")
    exts should contain("json")
    exts should contain("xml")
    exts should contain("html")
    exts should contain("htm")
    exts should contain("csv")
    exts should contain("rst")
  }
}

class S3LoaderSpec extends AnyFlatSpec with Matchers {

  "S3Loader.apply" should "create a loader with default settings" in {
    val loader = S3Loader("my-bucket")

    loader.description should include("my-bucket")
  }

  it should "accept custom settings" in {
    val loader = S3Loader(
      bucket = "my-bucket",
      prefix = "docs/",
      region = "eu-west-1",
      extensions = Set("pdf")
    )

    loader.description should include("my-bucket")
    loader.description should include("docs/")
  }

  "S3Loader.forLocalStack" should "create loader for LocalStack testing" in {
    val loader = S3Loader.forLocalStack("test-bucket", "prefix/")

    loader.description should include("test-bucket")
    loader.description should include("prefix/")
  }

  "S3Loader.withCredentials" should "create loader with explicit credentials" in {
    val loader = S3Loader.withCredentials(
      bucket = "bucket",
      accessKeyId = "key",
      secretAccessKey = "secret"
    )

    loader.description should include("bucket")
  }

  "S3Loader.++" should "combine two loaders" in {
    val loader1  = S3Loader("bucket1", prefix = "docs/")
    val loader2  = S3Loader("bucket2", prefix = "reports/")
    val combined = loader1 ++ loader2

    combined.description should include("docs/")
    combined.description should include("reports/")
  }
}

class DocumentVersionSpec extends AnyFlatSpec with Matchers {

  "DocumentVersion" should "create version with content hash" in {
    val version = DocumentVersion(contentHash = "abc123")

    version.contentHash shouldBe "abc123"
    version.timestamp shouldBe None
    version.etag shouldBe None
  }

  it should "create version with all fields" in {
    val version = DocumentVersion(
      contentHash = "abc123",
      timestamp = Some(1234567890L),
      etag = Some("\"abc123\"")
    )

    version.contentHash shouldBe "abc123"
    version.timestamp shouldBe Some(1234567890L)
    version.etag shouldBe Some("\"abc123\"")
  }

  it should "be equal when content hash matches" in {
    val v1 = DocumentVersion("hash1")
    val v2 = DocumentVersion("hash1")
    val v3 = DocumentVersion("hash2")

    v1 shouldBe v2
    v1 should not be v3
  }
}
