package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import scala.util.Using

/**
 * The [[DocumentRegistry]] contract, run against every registry `llm4s-rag` ships that needs no server. The
 * PostgreSQL registry runs the same cases in `modules/it` (`PgDocumentRegistrySpec`).
 */
abstract class DocumentRegistryContract(name: String) extends AnyFlatSpec with Matchers {

  /** A new, empty registry, and how to release it. */
  protected def withRegistry[A](f: DocumentRegistry => A): A

  private val v1 = DocumentVersion("hash-1")
  private val v2 = DocumentVersion("hash-2", timestamp = Some(1700000000000L), etag = Some("\"etag\""))

  name should "return no version for an unknown document" in withRegistry { r =>
    r.getVersion("missing") shouldBe Right(None)
    r.contains("missing") shouldBe Right(false)
  }

  it should "round-trip a version, timestamp and etag included" in withRegistry { r =>
    r.register("a", v1) shouldBe Right(())
    r.register("b", v2, "s3://bucket") shouldBe Right(())
    r.getVersion("a") shouldBe Right(Some(v1))
    r.getVersion("b") shouldBe Right(Some(v2))
    r.count() shouldBe Right(2)
  }

  it should "replace a version, and move the document to the source that registered it last" in withRegistry { r =>
    r.register("a", v1, "one") shouldBe Right(())
    r.register("a", v2, "two") shouldBe Right(())
    r.getVersion("a") shouldBe Right(Some(v2))
    r.documentIds("one") shouldBe Right(Set.empty)
    r.documentIds("two") shouldBe Right(Set("a"))
  }

  it should "list documents by source, the two-argument register using the default source" in withRegistry { r =>
    r.register("a", v1) shouldBe Right(())
    r.register("b", v1, "docs") shouldBe Right(())
    r.register("c", v1, "docs") shouldBe Right(())
    r.documentIds(DocumentRegistry.DefaultSourceId) shouldBe Right(Set("a"))
    r.documentIds("docs") shouldBe Right(Set("b", "c"))
    r.documentIds("other") shouldBe Right(Set.empty)
    r.allDocumentIds() shouldBe Right(Set("a", "b", "c"))
  }

  it should "unregister and clear" in withRegistry { r =>
    r.register("a", v1) shouldBe Right(())
    r.register("b", v1, "docs") shouldBe Right(())
    r.unregister("a") shouldBe Right(())
    r.unregister("missing") shouldBe Right(())
    r.allDocumentIds() shouldBe Right(Set("b"))
    r.clear() shouldBe Right(())
    r.allDocumentIds() shouldBe Right(Set.empty)
    r.count() shouldBe Right(0)
  }
}

class InMemoryDocumentRegistrySpec extends DocumentRegistryContract("InMemoryDocumentRegistry") {
  override protected def withRegistry[A](f: DocumentRegistry => A): A = f(InMemoryDocumentRegistry())
}

class SQLiteDocumentRegistrySpec extends DocumentRegistryContract("SQLiteDocumentRegistry") {

  override protected def withRegistry[A](f: DocumentRegistry => A): A =
    Using.resource(SQLiteDocumentRegistry.inMemory().fold(e => fail(e.formatted), identity))(f)

  "A SQLiteDocumentRegistry file" should "keep its registrations when it is reopened" in {
    val dir  = Files.createTempDirectory("llm4s-registry")
    val path = dir.resolve("registry.db").toString
    try {
      Using.resource(SQLiteDocumentRegistry(path).fold(e => fail(e.formatted), identity)) { r =>
        r.register("a", DocumentVersion("hash-1"), "docs") shouldBe Right(())
      }
      Using.resource(SQLiteDocumentRegistry(path).fold(e => fail(e.formatted), identity)) { r =>
        r.getVersion("a") shouldBe Right(Some(DocumentVersion("hash-1")))
        r.documentIds("docs") shouldBe Right(Set("a"))
      }
    } finally {
      Files.deleteIfExists(dir.resolve("registry.db"))
      Files.deleteIfExists(dir)
    }
  }

  it should "fail to open in a directory that does not exist, as a Left" in {
    SQLiteDocumentRegistry("/does/not/exist/registry.db").isLeft shouldBe true
  }
}
