package org.llm4s.rag.permissions.pg

import org.llm4s.rag.permissions.{ ChunkWithEmbedding, CollectionPath, PrincipalId }
import org.llm4s.vectorstore.{ ScriptedDb => RecordingDb }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, Method, Proxy }
import java.sql.{ Connection, PreparedStatement, ResultSet }
import javax.sql.DataSource
import scala.collection.mutable

/**
 * `PgSearchIndex.ingest` over a scripted JDBC connection (#2732): the ids it returns are the ids it writes, and each
 * chunk's own metadata is stored next to the document's. The same against a real database is `PgSearchIndexSpec`
 * (`modules/it`, `@Docker`).
 */
class PgSearchIndexIngestSpec extends AnyFlatSpec with Matchers {

  /** A connection whose one collection is the leaf `docs`, id 7, and which records every batched INSERT row. */
  final private class ScriptedDb(
    isLeaf: Boolean = true,
    failInsert: Boolean = false,
    lockedIsLeaf: Option[Boolean] = None
  ) {
    val rows: mutable.ListBuffer[Map[Int, Any]] = mutable.ListBuffer.empty
    var commits: Int                            = 0
    var rollbacks: Int                          = 0

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
                case (r, _)                                   => r.asInstanceOf[AnyRef]
              }
            }
          }
        )
        .asInstanceOf[T]

    private def collectionRow(leaf: Boolean): ResultSet = {
      var served = false
      proxy(classOf[ResultSet]) {
        case ("next", _) =>
          val more = !served; served = true; more
        case ("getInt", Array("id"))          => 7
        case ("getString", Array("path"))     => "docs"
        case ("getString", Array("metadata")) => "{}"
        case ("getBoolean", Array("is_leaf")) => leaf
        case _                                => null
      }
    }

    private def statement(sql: String): PreparedStatement = {
      val current = mutable.Map.empty[Int, Any]
      proxy(classOf[PreparedStatement]) {
        case ("setString" | "setInt" | "setArray", Array(i: Integer, v)) => current(i.intValue) = v
        case ("addBatch", _)                                             => rows += current.toMap; current.clear()
        case ("executeBatch", _) =>
          if (failInsert) throw new java.sql.SQLException("insert failed") else Array.fill(rows.size)(1)
        case ("executeQuery", _) if sql.contains("FOR SHARE")         => collectionRow(lockedIsLeaf.getOrElse(isLeaf))
        case ("executeQuery", _) if sql.contains("llm4s_collections") => collectionRow(isLeaf)
        case ("executeQuery", _)                                      => proxy(classOf[ResultSet])((_, _) => null)
        case _                                                        => null
      }
    }

    val dataSource: DataSource = {
      val connection = proxy(classOf[Connection]) {
        case ("getAutoCommit", _)                     => true
        case ("prepareStatement", Array(sql: String)) => statement(sql)
        case ("commit", _)                            => commits += 1
        case ("rollback", _)                          => rollbacks += 1
        case _                                        => null
      }
      proxy(classOf[DataSource]) {
        case ("getConnection", _) => connection
        case _                    => null
      }
    }
  }

  private val docs = CollectionPath.unsafe("docs")

  private def chunk(index: Int, content: String, metadata: Map[String, String] = Map.empty) =
    ChunkWithEmbedding(content, Array(0.1f, 0.2f), index, metadata)

  "PgSearchIndex.ingest" should "return the ids it writes, one per chunk in order" in {
    val db    = new ScriptedDb()
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get

    val ids = index.ingest(docs, "doc-1", Seq(chunk(0, "a"), chunk(1, "b")), Map.empty, Set.empty)

    ids shouldBe Right(Seq("coll-7-doc-1-chunk-0", "coll-7-doc-1-chunk-1"))
    db.rows.map(_(1)) shouldBe Seq("coll-7-doc-1-chunk-0", "coll-7-doc-1-chunk-1")
    db.rows.map(_(4)) shouldBe Seq("a", "b")
    db.commits shouldBe 1
  }

  it should "store each chunk's metadata next to the document's" in {
    val db    = new ScriptedDb()
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get

    index.ingest(
      docs,
      "doc-1",
      Seq(chunk(0, "a", Map("chunk.language" -> "scala")), chunk(1, "b")),
      Map("source" -> "wiki"),
      Set(PrincipalId.user(3))
    ) shouldBe Right(Seq("coll-7-doc-1-chunk-0", "coll-7-doc-1-chunk-1"))

    val first = db.rows.head(5).toString
    first should include("\"chunk.language\":\"scala\"")
    first should include("\"source\":\"wiki\"")
    first should include("\"docId\":\"doc-1\"")
    (db.rows(1)(5).toString should not).include("chunk.language")
    db.rows.map(_(6)) shouldBe Seq(7, 7)
  }

  it should "refuse a collection that is not a leaf, writing nothing" in {
    val db    = new ScriptedDb(isLeaf = false)
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get

    index.ingest(docs, "doc-1", Seq(chunk(0, "a")), Map.empty, Set.empty).left.toOption.get.message should include(
      "non-leaf"
    )
    db.rows shouldBe empty
  }

  it should "refuse, writing nothing, a collection made a non-leaf after it was read (#2991)" in {
    val db    = new ScriptedDb(lockedIsLeaf = Some(false))
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get

    index.ingest(docs, "doc-1", Seq(chunk(0, "a")), Map.empty, Set.empty).left.toOption.get.message should include(
      "non-leaf"
    )
    db.rows shouldBe empty
    db.rollbacks shouldBe 1
    db.commits shouldBe 0
  }

  it should "roll back and report a failed insert" in {
    val db    = new ScriptedDb(failInsert = true)
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get

    index.ingest(docs, "doc-1", Seq(chunk(0, "a")), Map.empty, Set.empty).left.toOption.get.message should include(
      "insert failed"
    )
    db.rollbacks shouldBe 1
    db.commits shouldBe 0
  }

  /** A recording connection whose one collection is the leaf `docs`, id 7; `failInsert` fails the chunk INSERT. */
  private def recording(failInsert: Boolean = false): RecordingDb =
    new RecordingDb(
      sql =>
        if (sql.contains("FOR SHARE")) Seq(Map("is_leaf" -> true))
        else if (sql.contains("llm4s_collections"))
          Seq(Map("id" -> 7, "path" -> "docs", "parent_path" -> null, "is_leaf" -> true, "metadata" -> "{}"))
        else Seq.empty,
      fails = sql => failInsert && sql.contains("INSERT INTO")
    )

  "A re-ingest" should "delete the document's chunks in the collection before inserting, in one transaction (#2988)" in {
    val db    = recording()
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get

    index.ingest(docs, "doc-1", Seq(chunk(0, "a")), Map.empty, Set(PrincipalId.group(2))) shouldBe Right(
      Seq("coll-7-doc-1-chunk-0")
    )

    val sql      = db.statements
    val leafLock = sql.indexWhere(_.contains("FOR SHARE"))
    val docLock  = sql.indexWhere(_.contains("pg_advisory_xact_lock"))
    val delete   = sql.indexWhere(_.contains("DELETE FROM vectors"))
    val insert   = sql.indexWhere(_.contains("INSERT INTO vectors"))
    leafLock should be >= 0
    docLock should be > leafLock
    delete should be > docLock
    insert should be > delete
    sql(delete) should include("collection_id = ?")
    sql(delete) should include(PgSearchIndex.DocIdMatch)
    db.writes(delete)._2 shouldBe Map(1 -> 7, 2 -> "doc-1")
    db.writes(docLock)._2 shouldBe Map(1 -> PgSearchIndex.DocumentLockClass, 2 -> "doc-1")
    db.commits shouldBe 1
    db.rollbacks shouldBe 0
  }

  it should "keep the old chunks when the insert fails: the delete is rolled back with it" in {
    val db    = recording(failInsert = true)
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get

    index.ingest(docs, "doc-1", Seq(chunk(0, "a")), Map.empty, Set.empty).isLeft shouldBe true

    db.statements.exists(_.contains("DELETE FROM vectors")) shouldBe true
    db.rollbacks shouldBe 1
    db.commits shouldBe 0
  }

  "PgSearchIndex.moveDocument" should "delete the document from every collection, then insert, in one transaction" in {
    val db    = recording()
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get

    index.moveDocument(docs, "doc-1", Seq(chunk(0, "a")), Map.empty, Set.empty) shouldBe Right(
      Seq("coll-7-doc-1-chunk-0")
    )

    val sql    = db.statements
    val delete = sql.indexWhere(_.contains("DELETE FROM vectors"))
    sql(delete) should include("collection_id IS NOT NULL")
    sql(delete) should include(PgSearchIndex.DocIdMatch)
    db.writes(delete)._2 shouldBe Map(1 -> "doc-1")
    sql.indexWhere(_.contains("INSERT INTO vectors")) should be > delete
    db.commits shouldBe 1
  }

  "PgSearchIndex.chunkId" should "include the collection id, so two collections never share an id" in {
    PgSearchIndex.chunkId(1, "doc", 0) should not be PgSearchIndex.chunkId(2, "doc", 0)
    PgSearchIndex.chunkId(3, "doc", 4) shouldBe "coll-3-doc-chunk-4"
  }
}
