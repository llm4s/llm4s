package org.llm4s.rag.permissions

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SearchIndexConfigSpec extends AnyFlatSpec with Matchers {

  "SearchIndex.PgConfig" should "have sensible defaults" in {
    val config = SearchIndex.PgConfig()
    config.host shouldBe "localhost"
    config.port shouldBe 5432
    config.database shouldBe "postgres"
    config.user shouldBe "postgres"
    config.password shouldBe ""
    config.vectorTableName shouldBe "vectors"
    config.keywordTableName shouldBe "documents"
    config.maxPoolSize shouldBe 10
  }

  it should "generate correct JDBC URL with defaults" in {
    val config = SearchIndex.PgConfig()
    config.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/postgres"
  }

  it should "generate correct JDBC URL with custom host and port" in {
    val config = SearchIndex.PgConfig(host = "db.example.com", port = 5433, database = "mydb")
    config.jdbcUrl shouldBe "jdbc:postgresql://db.example.com:5433/mydb"
  }

  it should "allow customization of all fields" in {
    val config = SearchIndex.PgConfig(
      host = "remote-host",
      port = 5555,
      database = "ragdb",
      user = "admin",
      password = "secret",
      vectorTableName = "my_vectors",
      keywordTableName = "my_docs",
      maxPoolSize = 20
    )
    config.host shouldBe "remote-host"
    config.port shouldBe 5555
    config.database shouldBe "ragdb"
    config.user shouldBe "admin"
    config.password shouldBe "secret"
    config.vectorTableName shouldBe "my_vectors"
    config.keywordTableName shouldBe "my_docs"
    config.maxPoolSize shouldBe 20
    config.jdbcUrl shouldBe "jdbc:postgresql://remote-host:5555/ragdb"
  }

  it should "support copy for partial changes" in {
    val base    = SearchIndex.PgConfig()
    val changed = base.copy(database = "testdb", maxPoolSize = 5)
    changed.database shouldBe "testdb"
    changed.maxPoolSize shouldBe 5
    changed.host shouldBe "localhost" // unchanged
  }

  it should "support equality" in {
    val a = SearchIndex.PgConfig(host = "h1", port = 1234, database = "db1")
    val b = SearchIndex.PgConfig(host = "h1", port = 1234, database = "db1")
    a shouldBe b
  }

  it should "distinguish different configs" in {
    val a = SearchIndex.PgConfig(host = "h1")
    val b = SearchIndex.PgConfig(host = "h2")
    a should not be b
  }
}
