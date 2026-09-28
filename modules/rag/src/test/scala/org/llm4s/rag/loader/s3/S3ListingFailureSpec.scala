package org.llm4s.rag.loader.s3

import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.rag.loader.{ LoadResult, SourceBackedLoader, SyncStats }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  ListObjectsV2Request,
  ListObjectsV2Response,
  NoSuchBucketException,
  S3Object
}

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Using

/**
 * An S3 listing that fails must surface as a failure, never as a sync that "completed
 * successfully" with 0 documents (#1231). No real AWS: every call goes to [[FakeS3Client]].
 */
class S3ListingFailureSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /**
   * An S3 client that answers ListObjectsV2 from a script of pages, one per call, and
   * GetObject from an in-memory map. A `Left` page is thrown, as the SDK does.
   */
  final private class FakeS3Client(pages: Seq[Either[Throwable, ListObjectsV2Response]], bodies: Map[String, String])
      extends S3Client {
    @volatile var listRequests: Vector[ListObjectsV2Request] = Vector.empty

    override def serviceName(): String = "s3"
    override def close(): Unit         = ()

    override def listObjectsV2(request: ListObjectsV2Request): ListObjectsV2Response = {
      val call = listRequests.size
      listRequests = listRequests :+ request
      pages.lift(call) match {
        case Some(Right(page)) => page
        case Some(Left(ex))    => throw ex
        case None              => throw new IllegalStateException(s"unexpected ListObjectsV2 call #${call + 1}")
      }
    }

    override def getObject(request: GetObjectRequest): ResponseInputStream[GetObjectResponse] = {
      val body = bodies.getOrElse(request.key(), throw new IllegalStateException(s"no such key ${request.key()}"))
      new ResponseInputStream(
        GetObjectResponse.builder().build(),
        AbortableInputStream.create(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
      )
    }
  }

  private def obj(key: String): S3Object = S3Object.builder().key(key).eTag(s"\"etag-$key\"").size(10L).build()

  private def page(keys: Seq[String], next: Option[String] = None): ListObjectsV2Response =
    ListObjectsV2Response
      .builder()
      .contents(keys.map(obj)*)
      .isTruncated(next.isDefined)
      .nextContinuationToken(next.orNull)
      .build()

  private val noCredentials: Throwable =
    SdkClientException.create("Unable to load credentials from any of the providers in the chain")

  private val noSuchBucket: Throwable =
    NoSuchBucketException.builder().message("The specified bucket does not exist").build()

  private def source(client: S3Client, bucket: String = "docs-bucket") =
    S3DocumentSource(bucket, prefix = "docs/").usingClient(client)

  private def loaderFor(client: S3Client) = SourceBackedLoader(source(client))

  private class MockEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(t => Seq(0.1, 0.2, (t.hashCode.abs % 100) / 100.0))))
  }

  private def withRag[A](f: RAG => A): A =
    Using.resource(RAG.buildWithClient(RAGConfig.default, new EmbeddingClient(new MockEmbeddingProvider)).toOption.get)(
      f
    )

  // ========== S3DocumentSource.listDocuments ==========

  "S3DocumentSource.listDocuments" should "return the listing error when the first page fails" in {
    val listed = source(new FakeS3Client(Seq(Left(noCredentials)), Map.empty)).listDocuments().toList

    listed should have size 1
    val err = listed.head.left.toOption.get
    err shouldBe a[NetworkError]
    err.message should include("s3://docs-bucket/docs/")
    err.message should include("Unable to load credentials")
  }

  it should "return nothing, and no error, for an empty bucket" in {
    val client = new FakeS3Client(Seq(Right(page(Nil))), Map.empty)

    source(client).listDocuments().toList shouldBe empty
    client.listRequests should have size 1
  }

  it should "return the first page, then the error, when a later page fails" in {
    val client = new FakeS3Client(
      Seq(Right(page(Seq("docs/a.txt", "docs/b.txt"), next = Some("token-2"))), Left(noSuchBucket)),
      Map.empty
    )

    val listed = source(client).listDocuments().toList

    listed.collect { case Right(ref) => ref.path } shouldBe List("docs/a.txt", "docs/b.txt")
    listed.last.isLeft shouldBe true
    listed.last.left.toOption.get.message should include("The specified bucket does not exist")
    client.listRequests.map(r => Option(r.continuationToken())) shouldBe Vector(None, Some("token-2"))
  }

  // ========== SourceBackedLoader over S3 ==========

  "SourceBackedLoader over S3" should "report a failed listing as a ListingFailure, not a document failure" in {
    val results = loaderFor(new FakeS3Client(Seq(Left(noCredentials)), Map.empty)).load().toList

    results should have size 1
    results.head shouldBe a[LoadResult.ListingFailure]
    val failure = results.head.asInstanceOf[LoadResult.ListingFailure]
    failure.source should include("s3://docs-bucket/docs/")
    failure.error.message should include("Unable to load credentials")
  }

  // ========== RAG.sync ==========

  "RAG.sync over S3" should "fail, not report success with 0 documents, when the listing fails" in
    withRag { rag =>
      val result = rag.sync(loaderFor(new FakeS3Client(Seq(Left(noCredentials)), Map.empty)))

      result.isLeft shouldBe true
      result.left.toOption.get.message should include("Unable to load credentials")
    }

  it should "succeed with 0 documents for an empty bucket" in
    withRag { rag =>
      rag.sync(loaderFor(new FakeS3Client(Seq(Right(page(Nil))), Map.empty))) shouldBe Right(SyncStats.empty)
    }

  it should "fail when a page after the first fails" in
    withRag { rag =>
      val client = new FakeS3Client(
        Seq(Right(page(Seq("docs/a.txt"), next = Some("token-2"))), Left(noSuchBucket)),
        Map("docs/a.txt" -> "Alpha document.")
      )

      val result = rag.sync(loaderFor(client))

      result.isLeft shouldBe true
      result.left.toOption.get.message should include("The specified bucket does not exist")
    }

  it should "not delete previously indexed documents when the listing fails" in
    withRag { rag =>
      val bodies = Map("docs/a.txt" -> "Alpha document.", "docs/b.txt" -> "Beta document.")
      val first  = rag.sync(loaderFor(new FakeS3Client(Seq(Right(page(bodies.keys.toSeq))), bodies)))
      first.map(_.added) shouldBe Right(2)

      rag.sync(loaderFor(new FakeS3Client(Seq(Left(noCredentials)), bodies))).isLeft shouldBe true

      // Had the failed sync treated both documents as gone, they would come back as "added".
      rag.sync(loaderFor(new FakeS3Client(Seq(Right(page(bodies.keys.toSeq))), bodies))) shouldBe Right(
        SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 2)
      )
    }

  it should "still succeed, skipping the object, when one object cannot be read" in
    withRag { rag =>
      // docs/b.txt is listed but GetObject fails: a per-object failure, not a listing failure.
      val client = new FakeS3Client(Seq(Right(page(Seq("docs/a.txt", "docs/b.txt")))), Map("docs/a.txt" -> "Alpha."))

      rag.sync(loaderFor(client)).map(_.added) shouldBe Right(1)
    }

  it should "keep a previously indexed object whose read fails, and still delete a removed one" in
    withRag { rag =>
      val bodies = Map("docs/a.txt" -> "Alpha.", "docs/b.txt" -> "Beta.", "docs/c.txt" -> "Gamma.")
      rag.sync(loaderFor(new FakeS3Client(Seq(Right(page(bodies.keys.toSeq))), bodies))).map(_.added) shouldBe Right(3)

      // b is still listed but GetObject fails (a transient read error); c is gone from the bucket.
      val flaky = new FakeS3Client(Seq(Right(page(Seq("docs/a.txt", "docs/b.txt")))), bodies - "docs/b.txt")
      rag.sync(loaderFor(flaky)) shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 1, unchanged = 1))

      // b survived the failed read: it comes back unchanged, not re-added
      val healthy = new FakeS3Client(Seq(Right(page(Seq("docs/a.txt", "docs/b.txt")))), bodies)
      rag.sync(loaderFor(healthy)) shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 2))
    }

  "SourceBackedLoader over S3" should "name the document a failed read is about" in {
    val client  = new FakeS3Client(Seq(Right(page(Seq("docs/b.txt")))), Map.empty)
    val results = loaderFor(client).load().toList

    results should have size 1
    val failure = results.head.asInstanceOf[LoadResult.Failure]
    failure.source shouldBe "docs/b.txt"
    failure.documentId shouldBe Some("s3://docs-bucket/docs/b.txt")
  }

  // ========== The other loader entry points ==========

  "RAG.ingest over S3" should "fail when the listing fails" in
    withRag(rag => rag.ingest(loaderFor(new FakeS3Client(Seq(Left(noCredentials)), Map.empty))).isLeft shouldBe true)

  "RAG.refresh over S3" should "fail when the listing fails" in
    withRag { rag =>
      rag.refresh(loaderFor(new FakeS3Client(Seq(Left(noCredentials)), Map.empty))).isLeft shouldBe true
    }

  "RAG.syncAsync over S3" should "fail when the listing fails" in
    withRag { rag =>
      val result =
        Await.result(rag.syncAsync(loaderFor(new FakeS3Client(Seq(Left(noCredentials)), Map.empty))), 10.seconds)
      result.isLeft shouldBe true
    }

  "RAG.ingestAsync over S3" should "fail when the listing fails" in
    withRag { rag =>
      val result =
        Await.result(rag.ingestAsync(loaderFor(new FakeS3Client(Seq(Left(noCredentials)), Map.empty))), 10.seconds)
      result.isLeft shouldBe true
    }
}
