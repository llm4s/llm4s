package org.llm4s.vectorstore

import org.llm4s.rag.permissions.{ CollectionConfig, CollectionPath, CollectionPattern, UserAuthorization }
import org.llm4s.rag.permissions.pg.{ PgCollectionStore, PgSearchIndex }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Every Postgres store writes its metadata with [[PgMetadataJson.write]] and reads it with [[PgMetadataJson.read]]
 * (#2993), checked over a scripted JDBC connection; the `@Docker` suites in `modules/it` run the same against
 * Postgres.
 */
class PgStoresMetadataJsonSpec extends AnyWordSpec with Matchers {

  private val metadata = Map("quote" -> "say \"hi\"", "newline" -> "a\nb", "title" -> """x","tenant":"other""")

  /** `metadata` as the `jsonb` text Postgres returns for it. */
  private val stored = """{"quote": "say \"hi\"", "title": "x\",\"tenant\":\"other", "newline": "a\nb"}"""

  private val noRows: String => Seq[Map[String, Any]] = _ => Seq.empty

  "PgVectorStore" should {
    "write metadata with PgMetadataJson in upsert and upsertBatch" in {
      val db    = new ScriptedDb(noRows)
      val store = PgVectorStore(db.dataSource, "vectors").toOption.get
      store.upsert(VectorRecord("a", Array(0.1f), metadata = metadata)) shouldBe Right(())
      store.upsertBatch(Seq(VectorRecord("b", Array(0.1f), metadata = metadata))) shouldBe Right(())
      db.writtenMetadata(5) shouldBe Seq(metadata, metadata)
    }

    "read stored metadata decoded in get" in {
      val db =
        new ScriptedDb(_ => Seq(Map("id" -> "a", "embedding" -> "[0.1,0.2]", "content" -> "c", "metadata" -> stored)))
      val store = PgVectorStore(db.dataSource, "vectors").toOption.get
      store.get("a").map(_.map(_.metadata)) shouldBe Right(Some(metadata))
    }
  }

  "PgKeywordIndex" should {
    "write metadata with PgMetadataJson in indexBatch" in {
      val db    = new ScriptedDb(noRows)
      val index = PgKeywordIndex(db.dataSource, "documents").toOption.get
      index.indexBatch(Seq(KeywordDocument("a", "content", metadata))) shouldBe Right(())
      db.writtenMetadata(3) shouldBe Seq(metadata)
    }

    "read stored metadata decoded in get and search" in {
      val db    = new ScriptedDb(_ => Seq(Map("id" -> "a", "content" -> "c", "score" -> 0.5, "metadata" -> stored)))
      val index = PgKeywordIndex(db.dataSource, "documents").toOption.get
      index.get("a").map(_.map(_.metadata)) shouldBe Right(Some(metadata))
      index.search("content", 5).map(_.map(_.metadata)) shouldBe Right(Seq(metadata))
    }
  }

  "PgSearchIndex" should {
    val collectionRow =
      Map("id" -> 7, "path" -> "docs", "parent_path" -> null, "is_leaf" -> true, "metadata" -> stored)
    val chunkRow = Map(
      "id"            -> "c1",
      "embedding"     -> "[0.1,0.2]",
      "embedding_dim" -> 2,
      "content"       -> "c",
      "metadata"      -> stored,
      "similarity"    -> 0.9,
      "score"         -> 0.5
    )
    def rows(sql: String): Seq[Map[String, Any]] =
      if (sql.contains("llm4s_collections")) Seq(collectionRow) else Seq(chunkRow)

    "read stored chunk metadata decoded in query and keywordQuery" in {
      val db    = new ScriptedDb(rows)
      val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get
      val docs  = CollectionPattern.Exact(CollectionPath.unsafe("docs"))
      index.query(UserAuthorization.Admin, docs, Array(0.1f, 0.2f), 5).map(_.map(_.record.metadata)) shouldBe
        Right(Seq(metadata))
      index.keywordQuery(UserAuthorization.Admin, docs, "c", 5, None).map(_.map(_.metadata)) shouldBe
        Right(Seq(metadata))
    }
  }

  "PgCollectionStore" should {
    val row = Map("id" -> 7, "path" -> "docs", "parent_path" -> null, "is_leaf" -> true, "metadata" -> stored)

    "write metadata with PgMetadataJson in create and updateMetadata, and read it back decoded" in {
      val db    = new ScriptedDb(_ => Seq(row))
      val store = new PgCollectionStore(() => db.dataSource.getConnection)
      val path  = CollectionPath.unsafe("docs")
      store.create(CollectionConfig(path, metadata = metadata)).map(_.metadata) shouldBe Right(metadata)
      store.updateMetadata(path, metadata).map(_.metadata) shouldBe Right(metadata)
      db.writtenMetadata(5) shouldBe Seq(metadata)
      db.writtenMetadata(1).filter(_.nonEmpty) shouldBe Seq(metadata)
    }
  }
}
