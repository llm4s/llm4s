package org.llm4s.rag.permissions.pg

import org.llm4s.error.ValidationError
import org.llm4s.rag.permissions._
import org.llm4s.vectorstore.ScriptedDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.{ InvocationHandler, Method, Proxy }

/**
 * `PgCollectionStore`'s hierarchy rules over a scripted JDBC connection: creating a child under a leaf (#2991) and
 * the effective access of a collection (#2990). The same against Postgres is `PgPermissionHierarchySpec`
 * (`modules/it`, `@Docker`).
 */
class PgCollectionHierarchySpec extends AnyFlatSpec with Matchers {

  /** A `java.sql.Array` of integers, as the driver returns `queryable_by`. */
  private def intArray(values: Int*): java.sql.Array =
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array[Class[?]](classOf[java.sql.Array]),
        new InvocationHandler {
          def invoke(p: Any, m: Method, args: Array[AnyRef]): AnyRef =
            if (m.getName == "getArray") values.map(Int.box).toArray[java.lang.Integer] else null
        }
      )
      .asInstanceOf[java.sql.Array]

  private def row(id: Int, path: String, isLeaf: Boolean, queryableBy: Int*): Map[String, Any] = Map(
    "id"           -> id,
    "path"         -> path,
    "parent_path"  -> CollectionPath.unsafe(path).parent.map(_.value).orNull,
    "queryable_by" -> intArray(queryableBy*),
    "is_leaf"      -> isLeaf,
    "metadata"     -> "{}"
  )

  private val docs    = CollectionPath.unsafe("docs")
  private val archive = CollectionPath.unsafe("docs/archive")

  /** A store whose parent `docs` is a leaf that does or does not hold chunks; `docs/archive` is what gets inserted. */
  private def scripted(parentHoldsChunks: Boolean, failInsert: Boolean = false): ScriptedDb =
    new ScriptedDb(
      sql =>
        if (sql.contains("FOR UPDATE")) Seq(row(7, "docs", isLeaf = true))
        else if (sql.contains("SELECT EXISTS")) Seq(Map("exists" -> parentHoldsChunks))
        else if (sql.contains("INSERT INTO llm4s_collections")) Seq(row(8, "docs/archive", isLeaf = true))
        else Seq.empty,
      fails = sql => failInsert && sql.contains("INSERT INTO llm4s_collections")
    )

  "PgCollectionStore.create" should "refuse a child under a leaf that holds documents, changing nothing (#2991)" in {
    val db    = scripted(parentHoldsChunks = true)
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    val result = store.create(CollectionConfig.publicLeaf(archive))

    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.message should include("leaf holding documents")
    db.statements.exists(_.contains("UPDATE llm4s_collections")) shouldBe false
    db.statements.exists(_.contains("INSERT INTO llm4s_collections")) shouldBe false
    db.rollbacks shouldBe 1
    db.commits shouldBe 0
  }

  it should "lock the parent, demote it and insert the child in one transaction (#2991)" in {
    val db    = scripted(parentHoldsChunks = false)
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    store.create(CollectionConfig.publicLeaf(archive)).map(_.path) shouldBe Right(archive)

    val sql = db.statements
    sql.indexWhere(_.contains("FOR UPDATE")) shouldBe 0
    sql.indexWhere(_.contains("SET is_leaf = FALSE")) should be < sql.indexWhere(_.contains("INSERT INTO"))
    db.commits shouldBe 1
    db.rollbacks shouldBe 0
  }

  it should "roll back the parent's demotion when the child's insert fails (#2991)" in {
    val db    = scripted(parentHoldsChunks = false, failInsert = true)
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    store.create(CollectionConfig.publicLeaf(archive)).isLeft shouldBe true
    db.statements.exists(_.contains("SET is_leaf = FALSE")) shouldBe true
    db.rollbacks shouldBe 1
    db.commits shouldBe 0
  }

  it should "accept a child naming principals its restricted parent does not (#2990)" in {
    val db = new ScriptedDb(sql =>
      if (sql.contains("FOR UPDATE")) Seq(row(7, "company", isLeaf = false, -1))
      else if (sql.contains("INSERT INTO llm4s_collections")) Seq(row(8, "company/executive", isLeaf = true, -2))
      else Seq.empty
    )
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    store
      .create(CollectionConfig.restrictedLeaf(CollectionPath.unsafe("company/executive"), Set(PrincipalId(-2))))
      .map(_.path.value) shouldBe Right("company/executive")
  }

  it should "still refuse a public child of a restricted parent" in {
    val db =
      new ScriptedDb(sql => if (sql.contains("FOR UPDATE")) Seq(row(7, "company", isLeaf = false, -1)) else Seq.empty)
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    store.create(CollectionConfig.publicLeaf(CollectionPath.unsafe("company/open"))).left.toOption.get.message should
      include("Cannot make collection public when parent is restricted")
  }

  "PgCollectionStore.effectiveAccess" should "read the collection and its ancestors in one query (#2990)" in {
    val db = new ScriptedDb(sql =>
      if (sql.contains("ANY(?)")) Seq(row(8, "hr/salaries", isLeaf = true), row(7, "hr", isLeaf = false, -5))
      else Seq.empty
    )
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    store.effectiveAccess(CollectionPath.unsafe("hr/salaries")) match {
      case Right(EffectiveAccess.Restricted(levels)) =>
        levels.map(l => (l.path.value, l.anyOf)) shouldBe Seq("hr" -> Set(PrincipalId(-5)))
      case other => fail(s"expected Restricted, got $other")
    }
    db.statements should have size 1
  }

  it should "fail when an ancestor is missing" in {
    val db = new ScriptedDb(sql => if (sql.contains("ANY(?)")) Seq(row(8, "hr/salaries", isLeaf = true)) else Seq.empty)
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    store.effectiveAccess(CollectionPath.unsafe("hr/salaries")).left.toOption.get.message should include("hr")
  }

  "PgCollectionStore.canQuery" should "ask the recursive query findAccessible uses, not the collection alone (#2990)" in {
    // hr/salaries is public, but the recursive query (its restricted parent) returns nothing for an anonymous user.
    val db = new ScriptedDb(sql =>
      if (sql.contains("WITH RECURSIVE")) Seq.empty
      else if (sql.contains("llm4s_collections")) Seq(row(8, "hr/salaries", isLeaf = true))
      else Seq.empty
    )
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    store.canQuery(CollectionPath.unsafe("hr/salaries"), UserAuthorization.Anonymous) shouldBe Right(false)
    db.statements.exists(_.contains("WITH RECURSIVE")) shouldBe true
  }

  it should "report a collection that does not exist" in {
    val db    = new ScriptedDb(_ => Seq.empty)
    val store = new PgCollectionStore(() => db.dataSource.getConnection)

    store.canQuery(docs, UserAuthorization.Admin).isLeft shouldBe true
  }

  "PgSearchIndex.query and keywordQuery" should "search a non-leaf collection's documents (#2991)" in {
    val collectionRow = Map("id" -> 7, "path" -> "docs", "parent_path" -> null, "is_leaf" -> false, "metadata" -> "{}")
    val chunkRow = Map(
      "id"            -> "c1",
      "embedding"     -> "[0.1,0.2]",
      "embedding_dim" -> 2,
      "content"       -> "kept",
      "metadata"      -> "{}",
      "similarity"    -> 0.9,
      "score"         -> 0.5
    )
    val db    = new ScriptedDb(sql => if (sql.contains("llm4s_collections")) Seq(collectionRow) else Seq(chunkRow))
    val index = PgSearchIndex.fromDataSource(db.dataSource).toOption.get
    val all   = CollectionPattern.Exact(docs)

    index.query(UserAuthorization.Admin, all, Array(0.1f, 0.2f), 5).map(_.map(_.record.id)) shouldBe Right(Seq("c1"))
    index.keywordQuery(UserAuthorization.Admin, all, "kept", 5, None).map(_.map(_.id)) shouldBe Right(Seq("c1"))
  }
}
