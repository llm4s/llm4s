package org.llm4s.rag.loader.s3

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.rag.loader.{ SourceBackedLoader, SyncStats, SyncableSource }
import org.llm4s.types.Result
import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

import java.io.ByteArrayInputStream
import scala.util.Using

/** A page of a listing whose every object is filtered out is not the end of the listing. */
class S3FilteredPageSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /** Pages by continuation token; a token with no page fails the request. */
  final private class FakeS3(pages: Map[Option[String], ListObjectsV2Response]) extends S3Client {
    override def serviceName(): String = "s3"
    override def close(): Unit         = ()
    override def listObjectsV2(request: ListObjectsV2Request): ListObjectsV2Response =
      pages.getOrElse(Option(request.continuationToken()), throw S3Exception.builder().message("no such page").build())
    override def getObject(request: GetObjectRequest): ResponseInputStream[GetObjectResponse] =
      new ResponseInputStream(
        GetObjectResponse.builder().build(),
        AbortableInputStream.create(new ByteArrayInputStream(s"Body of ${request.key()}.".getBytes("UTF-8")))
      )
  }

  private def page(next: Option[String])(keys: String*): ListObjectsV2Response =
    ListObjectsV2Response
      .builder()
      .contents(keys.map(key => S3Object.builder().key(key).eTag(s"\"e-$key\"").size(10L).build())*)
      .isTruncated(next.isDefined)
      .nextContinuationToken(next.orNull)
      .build()

  private def source(pages: (Option[String], ListObjectsV2Response)*): SyncableSource =
    S3DocumentSource("bucket").usingClient(new FakeS3(pages.toMap))

  private def listed(source: SyncableSource): List[String] =
    source.listDocuments().toList.map(_.fold(error => s"Left: ${error.message}", _.path))

  private val imagesThenReport =
    Seq(None -> page(Some("t2"))("img/1.png", "img/2.png"), Some("t2") -> page(None)("report.txt"))

  "A listing" should "go on past a first page with no matching object" in {
    listed(source(imagesThenReport*)) shouldBe List("report.txt")
  }

  it should "go on past several, in the middle and one after another" in {
    val pages = Seq(
      None       -> page(Some("t2"))("a.txt"),
      Some("t2") -> page(Some("t3"))("b.png"),
      Some("t3") -> page(Some("t4"))("dir/"),
      Some("t4") -> page(Some("t5"))(),
      Some("t5") -> page(None)("c.txt")
    )
    listed(source(pages*)) shouldBe List("a.txt", "c.txt")
  }

  it should "end when the last page has no matching object" in {
    listed(source(None -> page(Some("t2"))("a.txt"), Some("t2") -> page(None)("b.png"))) shouldBe List("a.txt")
  }

  it should "fail, not end, when the page after a filtered one cannot be listed" in {
    listed(source(None -> page(Some("gone"))("a.png"))) shouldBe
      List("Left: Failed to list S3 objects from s3://bucket/: no such page")
  }

  it should "fail, not loop or end, when a truncated page has no new continuation token" in {
    def truncated(token: String)(keys: String*) =
      page(None)(keys*).toBuilder.isTruncated(true).nextContinuationToken(token).build()
    val error = "Left: Failed to list S3 objects from s3://bucket/: the listing is truncated but has no next page"

    listed(source(None -> truncated(null)("a.png"))) shouldBe List(error)
    listed(source(None -> truncated(null)("a.txt"))) shouldBe List("a.txt", error)
    listed(source(None -> page(Some("t2"))("a.txt"), Some("t2") -> truncated("t2")("b.png"))) shouldBe
      List("a.txt", error)
  }

  "A sync" should "not delete a document that a later page still lists" in {
    val embedding = new EmbeddingProvider {
      override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
        Right(EmbeddingResponse(embeddings = request.input.map(_ => Seq(0.1, 0.2, 0.3))))
    }
    inside(RAG.buildWithClient(RAGConfig.default, EmbeddingClient(embedding))) { case Right(built) =>
      Using.resource(built) { rag =>
        rag.sync(SourceBackedLoader(source(None -> page(None)("report.txt")))).map(_.added) shouldBe Right(1)
        rag.sync(SourceBackedLoader(source(imagesThenReport*))) shouldBe Right(SyncStats(0, 0, 0, 1))
      }
    }
  }
}
