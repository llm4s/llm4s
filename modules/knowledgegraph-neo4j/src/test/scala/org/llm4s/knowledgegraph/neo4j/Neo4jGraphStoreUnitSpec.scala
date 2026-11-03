package org.llm4s.knowledgegraph.neo4j

import org.llm4s.security.{ KnownSecrets, Secret }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Pure unit tests for Neo4jGraphStore — no running Neo4j instance required.
 *
 * These tests always execute and provide a baseline coverage metric that is
 * meaningful even in environments without a Neo4j server.
 *
 * Full integration coverage (all GraphStore operations) is provided by the
 * `it` module, which runs against a live Neo4j instance.
 */
class Neo4jGraphStoreUnitSpec extends AnyFunSuite with Matchers {

  // ─── Config defaults ──────────────────────────────────────────────────────

  test("Config: default values are correct") {
    val config = Neo4jGraphStore.Config()
    config.uri shouldBe "bolt://localhost:7687"
    config.user shouldBe "neo4j"
    config.password.reveal shouldBe ""
    config.database shouldBe "neo4j"
    config.maxPoolSize shouldBe 10
  }

  test("Config: custom values are preserved") {
    val config = Neo4jGraphStore.Config("bolt://myhost:7687", "admin", Secret("secret-pw-1"), "mydb", 20)
    config.uri shouldBe "bolt://myhost:7687"
    config.user shouldBe "admin"
    config.password.reveal shouldBe "secret-pw-1"
    config.database shouldBe "mydb"
    config.maxPoolSize shouldBe 20
  }

  test("Config: copy preserves unchanged fields") {
    val base    = Neo4jGraphStore.Config()
    val updated = base.copy(user = "alice", password = Secret("alice-pw-1"))
    updated.uri shouldBe base.uri
    updated.database shouldBe base.database
    updated.user shouldBe "alice"
    updated.password.reveal shouldBe "alice-pw-1"
  }

  test("Config: toString masks the password") {
    KnownSecrets.isolated {
      (Neo4jGraphStore.Config(password = Secret("neo4j-secret-xyz")).toString should not).include("neo4j-secret-xyz")
    }
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

  test("a registered password in a driver exception never reaches the error (#1932)") {
    KnownSecrets.isolated {
      Secret("neo4j-password-0001")
      val driver = java.lang.reflect.Proxy
        .newProxyInstance(
          getClass.getClassLoader,
          Array(classOf[org.neo4j.driver.Driver]),
          (_, method, _) =>
            if (method.getName == "session")
              throw new IllegalStateException("authentication failed for neo4j/neo4j-password-0001")
            else null
        )
        .asInstanceOf[org.neo4j.driver.Driver]
      val result = Neo4jGraphStore(driver, "neo4j").flatMap(_.stats())
      val error  = result.left.getOrElse(fail(s"expected a failure, got $result"))
      error.message should include("authentication failed")
      (error.message should not).include("neo4j-password-0001")
    }
  }
}
