package org.llm4s.vectorstore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PgVectorTableSchemaSpec extends AnyFlatSpec with Matchers {

  private val statements = PgVectorTableSchema.statements("my_vectors")

  private def indexOf(fragment: String): Int = {
    val i = statements.indexWhere(_.contains(fragment))
    withClue(s"no statement contains '$fragment': ") {
      i should be >= 0
    }
    i
  }

  "PgVectorTableSchema.statements" should "enable pgvector before creating the table" in {
    statements.head shouldBe "CREATE EXTENSION IF NOT EXISTS vector"
    indexOf("CREATE TABLE IF NOT EXISTS my_vectors") shouldBe 1
  }

  it should "define created_at in the table so a fresh table can be indexed on it" in {
    val create = PgVectorTableSchema.createTable("my_vectors")
    create should include("created_at TIMESTAMPTZ DEFAULT NOW()")
    create should include("embedding_dim INTEGER NOT NULL")
    create should include("content TEXT,")
    (create should not).include("content TEXT NOT NULL")
  }

  it should "add created_at to an existing table before indexing it" in {
    val add   = indexOf("ADD COLUMN created_at TIMESTAMPTZ DEFAULT NOW()")
    val index = indexOf("idx_my_vectors_created ON my_vectors(created_at)")
    add should be < index
  }

  it should "only alter the table when the column is missing or constrained" in {
    val add = PgVectorTableSchema.addCreatedAt("my_vectors")
    add should include("to_regclass('my_vectors')")
    add should include("a.attname = 'created_at'")
    add should include("IF NOT FOUND THEN")

    val relax = PgVectorTableSchema.dropContentNotNull("my_vectors")
    relax should include("a.attname = 'content'")
    relax should include("IF FOUND AND is_not_null THEN")
    relax should include("ALTER TABLE my_vectors ALTER COLUMN content DROP NOT NULL")
  }

  it should "emit PL/pgSQL blocks with single dollar-quote delimiters" in {
    val add = PgVectorTableSchema.addCreatedAt("my_vectors")
    add should startWith("DO $$\n")
    add should endWith("END $$")
    (add should not).include("$$$$")
  }

  it should "be idempotent: every statement is guarded" in {
    statements.foreach { sql =>
      withClue(sql) {
        (sql.contains("IF NOT EXISTS") || sql.startsWith("DO $$")) shouldBe true
      }
    }
  }

  it should "name every index after the table" in {
    statements.filter(_.startsWith("CREATE INDEX")).map(_.split(" ")(5)) shouldBe Seq(
      "idx_my_vectors_dim",
      "idx_my_vectors_created",
      "idx_my_vectors_metadata"
    )
  }
}
