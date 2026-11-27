package org.llm4s.rag.loader

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.attribute.{
  AclEntry,
  AclEntryPermission,
  AclEntryType,
  AclFileAttributeView,
  PosixFileAttributeView,
  PosixFilePermission
}
import java.nio.file.{ FileSystems, Files, Path }
import java.util.Comparator
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

/**
 * A directory `DirectoryLoader` cannot list is a [[LoadResult.ListingFailure]], never an empty directory, so
 * `RAG.sync` deletes nothing it could not see (#2998). Permissions are platform-sensitive: POSIX permissions where
 * the file system has them, otherwise (Windows) an ACL entry denying the current user the listing. A test cancels
 * where the denial does not take (running as root).
 */
class DirectoryLoaderListingFailureSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private var root: Path                 = _
  private var sub: Path                  = _
  private var restores: List[() => Unit] = Nil

  override def beforeEach(): Unit = {
    root = Files.createTempDirectory("dirloader-listing")
    Files.writeString(root.resolve("a.txt"), "Alpha document.")
    Files.writeString(root.resolve("b.txt"), "Beta document.")
    sub = Files.createDirectory(root.resolve("sub"))
    Files.writeString(sub.resolve("c.txt"), "Gamma document.")
    restores = Nil
  }

  override def afterEach(): Unit = {
    restores.foreach(restore => Try(restore()))
    Files.walk(root).sorted(Comparator.reverseOrder()).iterator().asScala.foreach(p => Try(Files.delete(p)))
  }

  /** Make `dir` unlistable for this process, undone in afterEach; cancels the test where that is not possible. */
  private def denyListing(dir: Path): Unit = {
    val posix = Files.getFileAttributeView(dir, classOf[PosixFileAttributeView])
    if (posix != null) {
      val before = posix.readAttributes().permissions()
      posix.setPermissions(java.util.EnumSet.noneOf(classOf[PosixFilePermission]))
      restores = (() => posix.setPermissions(before)) :: restores
    } else {
      val acl = Files.getFileAttributeView(dir, classOf[AclFileAttributeView])
      assume(acl != null, "neither POSIX permissions nor ACLs are available here")
      val before = acl.getAcl
      val user = FileSystems.getDefault.getUserPrincipalLookupService
        .lookupPrincipalByName(System.getProperty("user.name"))
      val deny = AclEntry
        .newBuilder()
        .setType(AclEntryType.DENY)
        .setPrincipal(user)
        .setPermissions(AclEntryPermission.LIST_DIRECTORY)
        .build()
      acl.setAcl((deny +: before.asScala.toSeq).asJava)
      restores = (() => acl.setAcl(before)) :: restores
    }
    assume(Try(Files.list(dir).close()).isFailure, s"$dir is still listable (running as root?)")
  }

  private def successes(results: Seq[LoadResult]): Seq[String] =
    results.collect { case LoadResult.Success(doc) => doc.content.trim }.sorted

  private def listingFailures(results: Seq[LoadResult]): Seq[LoadResult.ListingFailure] =
    results.collect { case f: LoadResult.ListingFailure => f }

  "DirectoryLoader" should "report a subdirectory it cannot list as a ListingFailure" in {
    denyListing(sub)
    val results = DirectoryLoader(root).load().toSeq

    successes(results) shouldBe Seq("Alpha document.", "Beta document.")
    val failures = listingFailures(results)
    failures should have size 1
    failures.head.error.message should include("sub")
    // The documents that were listed come first, the listing failure last
    results.last shouldBe a[LoadResult.ListingFailure]
    DirectoryLoader(root).estimatedCount shouldBe None
  }

  it should "report a root it cannot list as a ListingFailure" in {
    denyListing(root)
    val results = DirectoryLoader(root).load().toSeq

    successes(results) shouldBe empty
    listingFailures(results) should have size 1
    DirectoryLoader(root).estimatedCount shouldBe None
  }

  it should "report an entry whose real path cannot be resolved as a ListingFailure" in {
    val loopA = root.resolve("loop-a")
    val made  = Try(Files.createSymbolicLink(loopA, root.resolve("loop-b")))
    assume(made.isSuccess, s"symbolic links are not available here: ${made.failed.toOption.map(_.getMessage)}")
    Files.createSymbolicLink(root.resolve("loop-b"), loopA)
    assume(Try(loopA.toRealPath()).failed.toOption.exists(!_.isInstanceOf[java.nio.file.NoSuchFileException]))

    val results = DirectoryLoader(root).load().toSeq
    successes(results) shouldBe Seq("Alpha document.", "Beta document.", "Gamma document.")
    listingFailures(results).map(_.error.message).mkString should include("loop-a")
  }

  it should "skip a dangling link, as it would a file that is gone" in {
    val made = Try(Files.createSymbolicLink(root.resolve("gone.txt"), root.resolve("missing.txt")))
    assume(made.isSuccess, s"symbolic links are not available here: ${made.failed.toOption.map(_.getMessage)}")

    val results = DirectoryLoader(root).load().toSeq
    successes(results) shouldBe Seq("Alpha document.", "Beta document.", "Gamma document.")
    listingFailures(results) shouldBe empty
  }

  // ========== RAG.sync ==========

  private class MockEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map(t => Seq(0.1, 0.2, (t.hashCode.abs % 100) / 100.0))))
  }

  private def withRag[A](f: RAG => A): A =
    Using.resource(RAG.buildWithClient(RAGConfig.default, EmbeddingClient(new MockEmbeddingProvider)).toOption.get)(f)

  "RAG.sync over a DirectoryLoader" should "delete nothing, and return the error, when a subdirectory cannot be listed" in
    withRag { rag =>
      rag.sync(DirectoryLoader(root)) shouldBe Right(SyncStats(added = 3, updated = 0, deleted = 0, unchanged = 0))

      denyListing(sub)
      rag.sync(DirectoryLoader(root)) shouldBe a[Left[?, ?]]

      restores.foreach(_())
      restores = Nil
      rag.sync(DirectoryLoader(root)) shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 3))
    }

  it should "delete nothing, and return the error, when the root cannot be listed" in
    withRag { rag =>
      rag.sync(DirectoryLoader(root)).map(_.added) shouldBe Right(3)

      denyListing(root)
      rag.sync(DirectoryLoader(root)) shouldBe a[Left[?, ?]]

      restores.foreach(_())
      restores = Nil
      rag.sync(DirectoryLoader(root)) shouldBe Right(SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 3))
    }
}
