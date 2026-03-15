package org.llm4s.knowledgegraph.neo4j

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Pure unit tests for Neo4jGraphStore — no running Neo4j instance required.
 *
 * These tests always execute and provide a baseline coverage metric that is
 * meaningful even in environments without a Neo4j server.
 *
 * Full integration coverage (all GraphStore operations) is provided by
 * Neo4jGraphStoreSpec, which runs when Neo4j is available on localhost:7687.
 */
class Neo4jGraphStoreUnitSpec extends AnyFunSuite with Matchers {

  // ─── Config defaults ──────────────────────────────────────────────────────

  test("Config: default values are correct") {
    val config = Neo4jGraphStore.Config()
    config.uri shouldBe "bolt://localhost:7687"
    config.user shouldBe "neo4j"
    config.password shouldBe ""
    config.database shouldBe "neo4j"
    config.maxPoolSize shouldBe 10
  }

  test("Config: custom values are preserved") {
    val config = Neo4jGraphStore.Config("bolt://myhost:7687", "admin", "secret", "mydb", 20)
    config.uri shouldBe "bolt://myhost:7687"
    config.user shouldBe "admin"
    config.password shouldBe "secret"
    config.database shouldBe "mydb"
    config.maxPoolSize shouldBe 20
  }

  test("Config: copy preserves unchanged fields") {
    val base    = Neo4jGraphStore.Config()
    val updated = base.copy(user = "alice", password = "pw")
    updated.uri shouldBe base.uri
    updated.database shouldBe base.database
    updated.user shouldBe "alice"
    updated.password shouldBe "pw"
  }

  // ─── Factory error handling ───────────────────────────────────────────────

  test("apply(Config): returns Left for an invalid URI scheme") {
    // The Neo4j driver rejects unknown URI schemes immediately, exercising
    // the Try{}.toEither error path in the apply factory.
    val result = Neo4jGraphStore(Neo4jGraphStore.Config(uri = "invalid://localhost:7687"))
    result shouldBe a[Left[_, _]]
  }

  test("apply(uri, user, password, database): delegates to apply(Config)") {
    // Validates the multi-arg convenience overload; bad scheme → Left
    val result = Neo4jGraphStore("invalid://localhost:7687")
    result shouldBe a[Left[_, _]]
  }
}
