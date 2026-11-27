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

  // ─── Property encoding (#2969) ────────────────────────────────────────────

  private val everyKind: Map[String, ujson.Value] = Map(
    "str"     -> ujson.Str("hello"),
    "jsonish" -> ujson.Str("""{"a":1}"""),
    "bool"    -> ujson.Bool(false),
    "int"     -> ujson.Num(42),
    "big"     -> ujson.Num(9007199254740993.0),
    "real"    -> ujson.Num(42.5),
    "null"    -> ujson.Null,
    "strs"    -> ujson.Arr("a", "b"),
    "nums"    -> ujson.Arr(1, 2.5),
    "ints"    -> ujson.Arr(1, 2),
    "bools"   -> ujson.Arr(true, false),
    "empty"   -> ujson.Arr(),
    "mixed"   -> ujson.Arr("a", 1, ujson.Null),
    "nested"  -> ujson.Arr(ujson.Arr(1), ujson.Arr(2)),
    "obj"     -> ujson.Obj("k" -> ujson.Arr(1, ujson.Obj("x" -> ujson.Null)))
  )

  test("properties of every JSON kind read back as they were written") {
    Neo4jProperties.encode(everyKind, "Node(1)").map(Neo4jProperties.decode) shouldBe Right(everyKind)
  }

  test("primitives and primitive arrays are stored natively, whole numbers as integers, the rest as JSON") {
    val stored = Neo4jProperties.encode(everyKind, "Node(1)").toOption.get
    stored.get("int") shouldBe java.lang.Long.valueOf(42L)
    stored.get("real") shouldBe java.lang.Double.valueOf(42.5)
    stored.get("strs") shouldBe java.util.List.of("a", "b")
    stored.get("ints") shouldBe java.util.List.of(java.lang.Long.valueOf(1L), java.lang.Long.valueOf(2L))
    Seq("null", "mixed", "nested", "obj").foreach(k => stored.containsKey(k) shouldBe false)
    // What a property filter compares, as the in-memory store does: the value's toString
    Seq("null", "mixed", "nested", "obj", "strs", "empty").foreach { k =>
      stored.get(Neo4jProperties.textKey(k)) shouldBe everyKind(k).toString
    }
    Seq("str", "bool", "int", "real").foreach(k => stored.containsKey(Neo4jProperties.textKey(k)) shouldBe false)
    ujson.read(stored.get(Neo4jProperties.JsonKey).asInstanceOf[String]).obj.keySet shouldBe
      Set("null", "mixed", "nested", "obj")
  }

  test("a property key the store reserves is refused, so it can never replace a node's identity") {
    Seq("llm4s_id", "llm4s_label", "llm4s_json", "llm4s_other").foreach { key =>
      val result = Neo4jProperties.encode(Map(key -> ujson.Str("b")), "Node(a)")
      result match {
        case Left(e)  => e.message should include(key)
        case Right(_) => fail(s"$key was accepted")
      }
    }
  }

  test("decode leaves out the store's own keys and tolerates a corrupt JSON property") {
    val stored = new java.util.HashMap[String, AnyRef]()
    stored.put("llm4s_id", "a")
    stored.put("llm4s_label", "Person")
    stored.put("llm4s_json", "not json")
    stored.put("llm4s_text_tags", "[\"a\"]")
    stored.put("name", "Alice")
    Neo4jProperties.decode(stored) shouldBe Map("name" -> ujson.Str("Alice"))
  }

  test("driver values convert to ujson: null, numbers, lists and maps") {
    Neo4jProperties.fromDriverValue(null) shouldBe ujson.Null
    Neo4jProperties.fromDriverValue(java.lang.Long.valueOf(5L)) shouldBe ujson.Num(5)
    Neo4jProperties.fromDriverValue(java.util.List.of("a")) shouldBe ujson.Arr("a")
    Neo4jProperties.fromDriverValue(java.util.Map.of("k", java.lang.Boolean.TRUE)) shouldBe ujson.Obj("k" -> true)
    Neo4jProperties.fromDriverValue(java.time.LocalDate.of(2026, 1, 2)) shouldBe ujson.Str("2026-01-02")
  }
}
