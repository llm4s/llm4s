package org.llm4s.vectorstore

import org.llm4s.rag.permissions.{ CollectionConfig, CollectionPath, CollectionPattern, UserAuthorization }
import org.llm4s.rag.permissions.pg.{ PgCollectionStore, PgSearchIndex }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, PreparedStatement, ResultSet, Statement }
import javax.sql.DataSource
import scala.collection.mutable

/**
 * Every Postgres store writes its metadata with [[PgMetadataJson.write]] and reads it with [[PgMetadataJson.read]]
 * (#2993), checked over a scripted JDBC connection; the `@Docker` suites in `modules/it` run the same against
 * Postgres.
 */
class PgStoresMetadataJsonSpec extends AnyWordSpec with Matchers {

  private val metadata = Map("quote" -> "say \"hi\"", "newline" -> "a\nb", "title" -> """x","tenant":"other""")

  /** `metadata` as the `jsonb` text Postgres returns for it. */
  private val stored = """{"quote": "say \"hi\"", "title": "x\",\"tenant\":\"other", "newline": "a\nb"}"""

  /**
   * A connection whose queries return `rows(sql)` and which records the parameters of every statement it runs
   * (one entry per `executeUpdate`, `executeQuery` or batch row).
   */
  final private class ScriptedDb(rows: String => Seq[Map[String, Any]]) {
    val writes: mutable.ListBuffer[(String, Map[Int, Any])] = mutable.ListBuffer.empty

    private def proxy[T](cls: Class[T])(handler: (String, Array[AnyRef]) => Any): T =
      Proxy
        .newProxyInstance(
          getClass.getClassLoader,
          Array[Class[?]](cls),
          new InvocationHandler {
            def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef = {
              val result = handler(m.getName, Option(args).getOrElse(Array.empty))
              (result, m.getReturnType) match {
                case (null, t) if t == java.lang.Boolean.TYPE => java.lang.Boolean.FALSE
                case (null, t) if t == java.lang.Integer.TYPE => Int.box(0)
                case (null, t) if t == java.lang.Long.TYPE    => Long.box(0L)
                case (null, t) if t == java.lang.Double.TYPE  => Double.box(0.0)
                case (r, _)                                   => r.asInstanceOf[AnyRef]
              }
            }
          }
        )
        .asInstanceOf[T]

    private def resultSet(data: Seq[Map[String, Any]]): ResultSet = {
      var at = -1
      proxy(classOf[ResultSet]) {
        case ("next", _)                       => at += 1; at < data.size
        case (_, Array(column: String))        => data(at).getOrElse(column, null)
        case (_, Array(_: Integer)) if at >= 0 => data(at).values.headOption.orNull
        case _                                 => null
      }
    }

    private def statement(sql: String): PreparedStatement = {
      val params         = mutable.Map.empty[Int, Any]
      def record(): Unit = { writes += ((sql, params.toMap)); params.clear() }
      proxy(classOf[PreparedStatement]) {
        case (name, Array(i: Integer, v)) if name.startsWith("set") => params(i.intValue) = v
        case ("addBatch", _)                                        => record()
        case ("executeBatch", _)                                    => Array(1)
        case ("executeUpdate", _)                                   => record(); 1
        case ("executeQuery", _)                                    => record(); resultSet(rows(sql))
        case _                                                      => null
      }
    }

    val dataSource: DataSource = {
      val connection = proxy(classOf[Connection]) {
        case ("getAutoCommit", _)                     => true
        case ("prepareStatement", Array(sql: String)) => statement(sql)
        case ("createStatement", _)                   => proxy(classOf[Statement])((_, _) => null)
        case _                                        => null
      }
      proxy(classOf[DataSource]) {
        case ("getConnection", _) => connection
        case _                    => null
      }
    }

    /** The metadata parameter each recorded statement wrote, as a map. */
    def writtenMetadata(index: Int): Seq[Map[String, String]] =
      writes.toSeq.collect { case (_, p) if p.contains(index) => PgMetadataJson.read(p(index).toString) }
  }

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
