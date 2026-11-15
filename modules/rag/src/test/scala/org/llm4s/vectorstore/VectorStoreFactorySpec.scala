package org.llm4s.vectorstore

import org.llm4s.error.NotFoundError
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import scala.util.Using

/**
 * Offline coverage for the factory paths other specs never reach.
 *
 * pgvector is left to `PgVectorStoreFactoryIntegrationSpec`: those paths open a database connection.
 */
class VectorStoreFactorySpec extends AnyWordSpec with Matchers {

  "VectorStoreFactory.Backend" should {

    "resolve each backend name in any case and reject an unknown name" in {
      val names = Seq(
        "SQLite"   -> VectorStoreFactory.Backend.SQLite,
        "sqlite"   -> VectorStoreFactory.Backend.SQLite,
        "PGVECTOR" -> VectorStoreFactory.Backend.PgVector,
        "pgvector" -> VectorStoreFactory.Backend.PgVector,
        "Qdrant"   -> VectorStoreFactory.Backend.Qdrant,
        "qdrant"   -> VectorStoreFactory.Backend.Qdrant
      )

      names.foreach { case (name, backend) =>
        withClue(s"fromString($name)") {
          VectorStoreFactory.Backend.fromString(name) shouldBe Some(backend)
        }
      }

      VectorStoreFactory.Backend.fromString("nope") shouldBe None
    }

    "list sqlite, pgvector and qdrant" in {
      VectorStoreFactory.Backend.all shouldBe Seq(
        VectorStoreFactory.Backend.SQLite,
        VectorStoreFactory.Backend.PgVector,
        VectorStoreFactory.Backend.Qdrant
      )
    }
  }

  "VectorStoreFactory.create" should {

    "return NotFoundError listing the supported backends for an unknown provider" in {
      VectorStoreFactory.create("nope") match {
        case Left(error: NotFoundError) =>
          error.key shouldBe "nope"
          error.message should include("sqlite")
          error.message should include("pgvector")
          error.message should include("qdrant")
        case other =>
          fail(s"Expected NotFoundError, got $other")
      }
    }

    "round-trip a record through a file-backed SQLite config" in {
      withTempDb("factory-config-") { path =>
        val record = VectorRecord("persist-config", Array(0.1f, 0.2f, 0.3f), Some("file backed config"))
        val config = VectorStoreFactory.Config(VectorStoreFactory.Backend.SQLite, path = Some(path.toString))

        closing(VectorStoreFactory.create(config))(_.close()) { store =>
          store.upsert(record) shouldBe Right(())
          store.get("persist-config").toOption.flatten.map(_.id) shouldBe Some("persist-config")
        }

        closing(VectorStoreFactory.create(config))(_.close()) { store =>
          store.get("persist-config").toOption.flatten.map(_.content) shouldBe Some(Some("file backed config"))
        }
      }
    }

    "return a local Qdrant store when the config has no connection string" in {
      expectQdrant(
        VectorStoreFactory.create(VectorStoreFactory.Config(VectorStoreFactory.Backend.Qdrant)),
        "http://localhost:6333",
        "vectors"
      )
    }

    "return a Qdrant store for an explicit URL" in {
      val config = VectorStoreFactory.Config(
        VectorStoreFactory.Backend.Qdrant,
        connectionString = Some("http://qdrant.example:6333"),
        options = Map("collectionName" -> "docs")
      )

      expectQdrant(VectorStoreFactory.create(config), "http://qdrant.example:6333", "docs")
    }

    "return a Qdrant store for a URL and an apiKey secret" in {
      // apiKey is a credential, so it is a secret. Putting it in options is rejected before the Qdrant branch.
      val config = VectorStoreFactory
        .Config(
          VectorStoreFactory.Backend.Qdrant,
          connectionString = Some("https://example.qdrant.io"),
          options = Map("collectionName" -> "cloud-docs")
        )
        .withSecret("apiKey", Secret("test-key"))

      expectQdrant(VectorStoreFactory.create(config), "https://example.qdrant.io", "cloud-docs")
    }
  }

  "VectorStoreFactory.sqlite" should {

    "round-trip a record through a temporary file" in {
      withTempDb("factory-sqlite-") { path =>
        val record = VectorRecord("persist-sqlite", Array(1.0f, 0.0f), Some("file backed sqlite"))

        closing(VectorStoreFactory.sqlite(path.toString))(_.close()) { store =>
          store.upsert(record) shouldBe Right(())
          store.get("persist-sqlite").toOption.flatten.map(_.content) shouldBe Some(Some("file backed sqlite"))
        }

        closing(VectorStoreFactory.sqlite(path.toString))(_.close()) { store =>
          store.get("persist-sqlite").toOption.flatten.map(_.id) shouldBe Some("persist-sqlite")
        }
      }
    }
  }

  "VectorStoreFactory qdrant shortcuts" should {

    "build an offline store from qdrant(collection)" in {
      expectQdrant(VectorStoreFactory.qdrant("docs"), "http://localhost:6333", "docs")
    }

    "build an offline store from qdrant(url, collection)" in {
      expectQdrant(
        VectorStoreFactory.qdrant("http://qdrant.example:6334", "remote-docs"),
        "http://qdrant.example:6334",
        "remote-docs"
      )
    }

    "build an offline store from qdrantCloud(url, key)" in {
      expectQdrant(
        VectorStoreFactory.qdrantCloud("https://cloud.example.qdrant.io", Secret("cloud-key")),
        "https://cloud.example.qdrant.io",
        "vectors"
      )
    }
  }

  "KeywordIndex.sqlite" should {

    "index a document and find it by a word in it" in {
      withTempDb("keyword-sqlite-") { path =>
        closing(KeywordIndex.sqlite(path.toString))(_.close()) { index =>
          index.index(KeywordDocument("doc-1", "The factory stores vectors")) shouldBe Right(())

          val found = index.search("factory", topK = 5)
          found.isRight shouldBe true
          found.toOption.get.map(_.id) should contain("doc-1")
        }
      }
    }
  }

  "HybridSearcher.sqlite" should {

    "return a searcher that can be closed" in {
      withTempDbs("hybrid-vector-", "hybrid-keyword-") { (vectorPath, keywordPath) =>
        closing(HybridSearcher.sqlite(vectorPath.toString, keywordPath.toString))(_.close()) { searcher =>
          searcher.vectorStore should not be null
          searcher.keywordIndex should not be null
          searcher.close()
        }
      }
    }
  }

  private def expectQdrant(opened: Result[VectorStore], baseUrl: String, collectionName: String): Unit =
    opened match {
      case Right(store: QdrantVectorStore) =>
        store.baseUrl shouldBe baseUrl
        store.collectionName shouldBe collectionName
        store.close()
      case Right(other) =>
        fail(s"Expected QdrantVectorStore, got ${other.getClass.getName}")
      case Left(error) =>
        fail(s"Expected QdrantVectorStore, got ${error.formatted}")
    }

  private def closing[A, B](opened: Result[A])(release: A => Unit)(body: A => B): B =
    opened match {
      case Left(error) => fail(s"Failed to create resource: ${error.formatted}")
      case Right(resource) =>
        Using.resource(new AutoCloseable {
          override def close(): Unit = release(resource)
        })(_ => body(resource))
    }

  private def withTempDb[A](prefix: String)(body: Path => A): A =
    Using.resource(new TempDb(prefix))(tmp => body(tmp.path))

  private def withTempDbs[A](vectorPrefix: String, keywordPrefix: String)(body: (Path, Path) => A): A =
    Using.Manager { use =>
      val vector  = use(new TempDb(vectorPrefix))
      val keyword = use(new TempDb(keywordPrefix))
      body(vector.path, keyword.path)
    }.get

  final private class TempDb(prefix: String) extends AutoCloseable {
    val path: Path = Files.createTempFile(prefix, ".db")

    override def close(): Unit = {
      val name = path.getFileName.toString
      List(name, s"$name-wal", s"$name-shm", s"$name-journal").foreach { fileName =>
        Files.deleteIfExists(path.resolveSibling(fileName))
        ()
      }
    }
  }
}
