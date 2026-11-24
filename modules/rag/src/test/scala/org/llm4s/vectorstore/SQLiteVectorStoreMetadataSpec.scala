package org.llm4s.vectorstore

import org.llm4s.testutil.LinearTime
import org.scalatest.Inside.inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.sql.{ Connection, DriverManager }
import scala.util.Using

/** #2759: any metadata survives a write and a read, filters compare it exactly, and earlier versions' rows still read. */
class SQLiteVectorStoreMetadataSpec extends AnyWordSpec with Matchers {

  private val awkward: Seq[(String, String)] = Seq(
    "a quote"              -> "say \"hi\"",
    "only a quote"         -> "\"",
    "a backslash"          -> "C:\\new\\table",
    "a trailing backslash" -> "dir\\",
    "a JSON separator"     -> "a\",\"b\":\"c",
    "a line break and tab" -> "one\ntwo\tthree\r",
    "a control character"  -> "bell\u0007",
    "accents and CJK"      -> "caf\u00e9 \u65e5\u672c\u8a9e",
    "an astral character"  -> "\ud83d\ude00",
    "an escape as text"    -> "\\u0041",
    "wildcards"            -> "50%_*?[a]",
    "a path-like string"   -> "$.a[0].b",
    "an empty string"      -> ""
  )

  private given Using.Releasable[SQLiteVectorStore] = _.close()

  private given Using.Releasable[Path] = file => Files.deleteIfExists(file): Unit

  private def withStore[A](f: SQLiteVectorStore => A): A =
    inside(SQLiteVectorStore.inMemory()) { case Right(store) => Using.resource(store)(f) }

  private def withFile[A](f: String => A): A =
    Using.resource(Files.createTempFile("llm4s-vector-metadata", ".db"))(file => f(file.toString))

  private def withFileStore[A](path: String)(f: SQLiteVectorStore => A): A =
    inside(SQLiteVectorStore(path)) { case Right(store) => Using.resource(store)(f) }

  private def withJdbc[A](path: String)(f: Connection => A): A =
    Using.resource(DriverManager.getConnection(s"jdbc:sqlite:$path"))(f)

  private def record(id: String, metadata: (String, String)*): VectorRecord =
    VectorRecord(id, Array(1.0f, 0.0f), Some("text"), metadata.toMap)

  private def add(store: VectorStore, records: VectorRecord*): Unit =
    store.upsertBatch(records) shouldBe Right(())

  private def matching(store: VectorStore, filter: MetadataFilter): Set[String] =
    inside(store.list(filter = Some(filter))) { case Right(records) => records.map(_.id).toSet }

  private def metadataOf(store: VectorStore, id: String): Map[String, String] =
    inside(store.get(id)) { case Right(Some(found)) => found.metadata }

  private def storedText(path: String): Map[String, String] = withJdbc(path) { connection =>
    Using.resource(connection.createStatement()) { stmt =>
      Using.resource(stmt.executeQuery("SELECT id, metadata FROM vectors")) { rs =>
        Iterator.continually(rs).takeWhile(_.next()).map(r => r.getString("id") -> r.getString("metadata")).toMap
      }
    }
  }

  /** Inserts a row with the statement and the unescaped metadata text of 0.6.0 and earlier. */
  private def insertOldRow(connection: Connection, id: String, metadata: String): Unit =
    Using.resource(
      connection.prepareStatement(
        "INSERT OR REPLACE INTO vectors (id, embedding, embedding_dim, content, metadata, created_at) VALUES (?, ?, ?, ?, ?, ?)"
      )
    ) { stmt =>
      stmt.setString(1, id)
      stmt.setBytes(2, java.nio.ByteBuffer.allocate(8).putFloat(1.0f).putFloat(0.0f).array())
      stmt.setInt(3, 2)
      stmt.setString(4, "text")
      stmt.setString(5, metadata)
      stmt.setLong(6, 1L)
      stmt.executeUpdate(): Unit
    }

  /** The tables are those of 0.6.0: the store's schema has not changed. */
  private def withOldFile[A](rows: (String, String)*)(f: String => A): A = withFile { path =>
    withFileStore(path)(_ => ())
    withJdbc(path)(connection => rows.foreach { case (id, metadata) => insertOldRow(connection, id, metadata) })
    f(path)
  }

  "SQLiteVectorStore metadata" should {

    awkward.foreach { case (name, text) =>
      s"read back $name in a value" in withStore { store =>
        store.upsert(record("one", "key" -> text, "other" -> "plain")) shouldBe Right(())
        add(store, record("batch", "key" -> text, "other" -> "plain"))
        metadataOf(store, "one") shouldBe Map("key" -> text, "other" -> "plain")
        metadataOf(store, "batch") shouldBe Map("key" -> text, "other" -> "plain")
      }

      s"read back $name in a key" in withStore { store =>
        add(store, record("one", text -> "value", "other" -> "plain"))
        metadataOf(store, "one") shouldBe Map(text -> "value", "other" -> "plain")
      }

      s"filter on $name in a value" in withStore { store =>
        add(store, record("hit", "key" -> text), record("miss", "key" -> s"x${text}x"), record("none", "k" -> text))
        matching(store, MetadataFilter.Equals("key", text)) shouldBe Set("hit")
        matching(store, MetadataFilter.Contains("key", text)) shouldBe Set("hit", "miss")
        matching(store, MetadataFilter.In("key", Set(text, "absent"))) shouldBe Set("hit")
        matching(store, MetadataFilter.Not(MetadataFilter.Equals("key", text))) shouldBe Set("miss", "none")
      }

      s"filter on $name in a key" in withStore { store =>
        add(store, record("hit", text -> "value"), record("miss", s"x${text}x" -> "value"))
        matching(store, MetadataFilter.HasKey(text)) shouldBe Set("hit")
        matching(store, MetadataFilter.Equals(text, "value")) shouldBe Set("hit")
        matching(store, MetadataFilter.Contains(text, "alu")) shouldBe Set("hit")
        matching(store, MetadataFilter.In(text, Set("value"))) shouldBe Set("hit")
      }
    }
  }

  "SQLiteVectorStore filters" should {

    "agree with a read on a null value, which is stored as text" in withStore { store =>
      add(store, record("r", "k" -> null))
      metadataOf(store, "r") shouldBe Map("k" -> "null")
      matching(store, MetadataFilter.Equals("k", "null")) shouldBe Set("r")
    }

    "match a value literally, not as a LIKE pattern" in withStore { store =>
      add(store, record("literal", "user" -> "user_1"), record("other", "user" -> "userX1"))
      matching(store, MetadataFilter.Equals("user", "user_1")) shouldBe Set("literal")
      matching(store, MetadataFilter.Contains("user", "r_")) shouldBe Set("literal")
      matching(store, MetadataFilter.In("user", Set("user_1"))) shouldBe Set("literal")
      store.deleteByFilter(MetadataFilter.Equals("user", "user_1")) shouldBe Right(1L)
      matching(store, MetadataFilter.All) shouldBe Set("other")
    }

    "look only at the named key" in withStore { store =>
      add(store, record("r", "a" -> "x", "b" -> "needle"))
      matching(store, MetadataFilter.Contains("a", "needle")) shouldBe Set.empty
    }

    "not find a key in another key's value" in withStore { store =>
      add(store, record("r", "note" -> "the \"author\": unknown"))
      matching(store, MetadataFilter.HasKey("author")) shouldBe Set.empty
    }

    "compare case, as the filters' Scala equivalents do" in withStore { store =>
      add(store, record("lower", "lang" -> "en"), record("upper", "lang" -> "EN"))
      matching(store, MetadataFilter.Equals("lang", "EN")) shouldBe Set("upper")
      matching(store, MetadataFilter.Contains("lang", "e")) shouldBe Set("lower")
      matching(store, MetadataFilter.HasKey("LANG")) shouldBe Set.empty
    }

    "match nothing for In with no values" in withStore { store =>
      add(store, record("r", "k" -> "v"))
      matching(store, MetadataFilter.In("k", Set.empty)) shouldBe Set.empty
      matching(store, MetadataFilter.Not(MetadataFilter.In("k", Set.empty))) shouldBe Set("r")
    }

    "tell a value that looks like JSON from JSON" in withStore { store =>
      add(store, record("number", "k" -> "1"), record("bool", "k" -> "true"), record("object", "k" -> "{\"a\":1}"))
      matching(store, MetadataFilter.Equals("k", "1")) shouldBe Set("number")
      matching(store, MetadataFilter.Equals("k", "true")) shouldBe Set("bool")
      matching(store, MetadataFilter.Equals("k", "{\"a\":1}")) shouldBe Set("object")
    }

    "apply to search and count too" in withStore { store =>
      add(store, record("hit", "k" -> "a\"b"), record("miss", "k" -> "ab"))
      val filter = Some(MetadataFilter.Equals("k", "a\"b"))
      store.count(filter) shouldBe Right(1L)
      inside(store.search(Array(1.0f, 0.0f), 5, filter)) { case Right(found) =>
        found.map(_.record.id) shouldBe Seq("hit")
      }
    }
  }

  "SQLiteVectorStore on a file an earlier version wrote" should {

    "read and filter plain rows" in withOldFile("r" -> """{"type":"doc","lang":"en"}""") { path =>
      withFileStore(path) { store =>
        metadataOf(store, "r") shouldBe Map("type" -> "doc", "lang" -> "en")
        matching(store, MetadataFilter.Equals("lang", "en")) shouldBe Set("r")
      }
    }

    "read a backslash as text, though the row happens to be valid JSON" in withOldFile(
      "r" -> """{"path":"C:\new\table"}"""
    ) { path =>
      withFileStore(path) { store =>
        metadataOf(store, "r") shouldBe Map("path" -> "C:\\new\\table")
        matching(store, MetadataFilter.Equals("path", "C:\\new\\table")) shouldBe Set("r")
      }
    }

    "recover a quote in a value" in withOldFile("r" -> """{"title":"say "hi" now","lang":"en"}""") { path =>
      withFileStore(path) { store =>
        metadataOf(store, "r") shouldBe Map("title" -> "say \"hi\" now", "lang" -> "en")
        matching(store, MetadataFilter.Equals("title", "say \"hi\" now")) shouldBe Set("r")
      }
    }

    "read a comma, colon, line break and empty value" in withOldFile(
      "r" -> "{\"a\":\"x, y: z\",\"b\":\"one\ntwo\",\"c\":\"\"}"
    ) { path =>
      withFileStore(path) { store =>
        metadataOf(store, "r") shouldBe Map("a" -> "x, y: z", "b" -> "one\ntwo", "c" -> "")
      }
    }

    "read no metadata, NULL metadata and text it did not write" in withOldFile(
      "empty" -> "{}",
      "null"  -> null,
      "other" -> "not json",
      "short" -> "{\"}"
    ) { path =>
      withFileStore(path) { store =>
        val ids = Set("empty", "null", "other", "short")
        ids.foreach(id => metadataOf(store, id) shouldBe Map.empty)
        matching(store, MetadataFilter.Not(MetadataFilter.HasKey("k"))) shouldBe ids
      }
    }

    "change no stored text by opening, reading or filtering" in {
      val rows = Seq(
        "quote"     -> """{"k":"a "b""}""",
        "backslash" -> """{"p":"C:\new"}""",
        "other"     -> "not json",
        "spaced"    -> """{"a": "b"}""",
        "marked"    -> " not json",
        "none"      -> null
      )
      withOldFile(rows*) { path =>
        withFileStore(path) { store =>
          rows.foreach { case (id, _) => metadataOf(store, id) }
          matching(store, MetadataFilter.Not(MetadataFilter.HasKey("x"))) shouldBe rows.map(_._1).toSet
          store.search(Array(1.0f, 0.0f), 9, Some(MetadataFilter.HasKey("k"))).map(_.size) shouldBe Right(1)
        }
        storedText(path) shouldBe rows.toMap
      }
    }

    "replace a row's text when it is upserted" in withOldFile("r" -> """{"p":"C:\new"}""") { path =>
      withFileStore(path) { store =>
        add(store, record("r", metadataOf(store, "r").toSeq*))
        metadataOf(store, "r") shouldBe Map("p" -> "C:\\new")
        matching(store, MetadataFilter.Equals("p", "C:\\new")) shouldBe Set("r")
      }
    }

    "not repeat a key to find more entries" in withOldFile(
      "tenant" -> """{"tenant":"acme","raw":"{"q":"1","tenant":"evil"}"}"""
    ) { path =>
      withFileStore(path) { store =>
        metadataOf(store, "tenant") shouldBe Map("tenant" -> "acme", "raw" -> """{"q":"1","tenant":"evil"}""")
        matching(store, MetadataFilter.Equals("tenant", "acme")) shouldBe Set("tenant")
        matching(store, MetadataFilter.Equals("tenant", "evil")) shouldBe Set.empty
      }
    }

    "not repeat a key when the row happens to be valid JSON" in withOldFile(
      "tenant" -> """{"tenant":"acme","raw":"x","tenant":"evil"}"""
    ) { path =>
      withFileStore(path) { store =>
        metadataOf(store, "tenant") shouldBe Map("tenant" -> "acme", "raw" -> "x\",\"tenant\":\"evil")
        matching(store, MetadataFilter.Equals("tenant", "acme")) shouldBe Set("tenant")
        matching(store, MetadataFilter.Equals("tenant", "evil")) shouldBe Set.empty
      }
    }

    "find only the entries a read returns in JSON it did not write" in withOldFile(
      "r" -> """ {"n":1,"o":{"a":"x"},"b":null,"k":"v"}"""
    ) { path =>
      withFileStore(path) { store =>
        metadataOf(store, "r") shouldBe Map("k" -> "v")
        Seq("n", "o", "b").foreach(key => matching(store, MetadataFilter.HasKey(key)) shouldBe Set.empty)
        matching(store, MetadataFilter.Contains("n", "1")) shouldBe Set.empty
        matching(store, MetadataFilter.HasKey("k")) shouldBe Set("r")
      }
    }

    "read a long value of separators without a quadratic scan" in withOldFile(
      "r" -> ("{\"k\":\"" + "a\",\"" * 250000 + "\",\"z\":\"w\"}")
    ) { path =>
      withFileStore(path) { store =>
        LinearTime.timed("1 MB of separators", hangGuard = LinearTime.MegabyteHangGuard) {
          val read = metadataOf(store, "r")
          read("k").length shouldBe 1000000
          read("z") shouldBe "w"
        }
      }
    }

    "read a value that is a comma, in any position" in withOldFile(
      "first"     -> """{"sep":",","b":"c"}""",
      "middle"    -> """{"a":"x","sep":",","b":"c"}""",
      "last"      -> """{"a":"x","sep":","}""",
      "colon"     -> """{"a":":","b":"c"}""",
      "quoted"    -> """{"sep":",","q":"say "hi"","b":"c"}""",
      "backslash" -> """{"sep":",","p":"C:\new","b":"c"}"""
    ) { path =>
      withFileStore(path) { store =>
        metadataOf(store, "first") shouldBe Map("sep" -> ",", "b" -> "c")
        metadataOf(store, "middle") shouldBe Map("a" -> "x", "sep" -> ",", "b" -> "c")
        metadataOf(store, "last") shouldBe Map("a" -> "x", "sep" -> ",")
        metadataOf(store, "colon") shouldBe Map("a" -> ":", "b" -> "c")
        metadataOf(store, "quoted") shouldBe Map("sep" -> ",", "q" -> "say \"hi\"", "b" -> "c")
        metadataOf(store, "backslash") shouldBe Map("sep" -> ",", "p" -> "C:\\new", "b" -> "c")
      }
    }

    "keep a comma, a quote and text in a quoted list in one value" in withOldFile(
      "r" -> """{"list":"a","b","c","lang":"en"}"""
    ) { path =>
      withFileStore(path)(store => metadataOf(store, "r") shouldBe Map("list" -> "a\",\"b\",\"c", "lang" -> "en"))
    }

    "read JSON it did not write as JSON" in withOldFile(
      "number" -> """{"n":1,"a":"b"}""",
      "mixed"  -> """{"a": "b", "c":"d"}"""
    ) { path =>
      withFileStore(path) { store =>
        metadataOf(store, "number") shouldBe Map("a" -> "b")
        metadataOf(store, "mixed") shouldBe Map("a" -> "b", "c" -> "d")
        matching(store, MetadataFilter.Equals("a", "b")) shouldBe Set("number", "mixed")
      }
    }

    "read a row that is JSON but has two readings as JSON" in withOldFile("r" -> """{"k":"x","y":"z"}""") { path =>
      // one entry whose value was x","y":"z was written as the same text; the text does not say which
      withFileStore(path)(metadataOf(_, "r")) shouldBe Map("k" -> "x", "y" -> "z")
    }

    "open twice at once" in withOldFile("r" -> """{"k":"a "b""}""") { path =>
      withFileStore(path) { first =>
        withFileStore(path) { second =>
          add(second, record("new", "k" -> "c\\d"))
          metadataOf(first, "new") shouldBe Map("k" -> "c\\d")
          metadataOf(first, "r") shouldBe metadataOf(second, "r")
        }
      }
    }

    "open and filter a read-only file" in withOldFile("r" -> """{"k":"a"b"}""") { path =>
      val file                         = new java.io.File(path)
      val writableAgain: AutoCloseable = () => file.setWritable(true): Unit
      Using.resource(writableAgain) { _ =>
        assume(file.setWritable(false) && !Files.isWritable(file.toPath))
        withFileStore(path)(store => matching(store, MetadataFilter.Equals("k", "a\"b")) shouldBe Set("r"))
      }
    }

    "filter rows an earlier version adds while the store is open as it reads them" in withOldFile(
      "old" -> """{"tenant":"other"}"""
    ) { path =>
      withFileStore(path) { store =>
        withJdbc(path) { connection =>
          insertOldRow(connection, "late", """{"tenant":"acme","title":"say "hi""}""")
          insertOldRow(connection, "path", """{"p":"C:\new"}""")
        }
        matching(store, MetadataFilter.Equals("tenant", "acme")) shouldBe Set("late")
        matching(store, MetadataFilter.Equals("p", "C:\\new")) shouldBe Set("path")
        withJdbc(path)(insertOldRow(_, "later", """{"tenant":"acme","t":"a "b""}"""))
        store.deleteByFilter(MetadataFilter.Not(MetadataFilter.Equals("tenant", "acme"))) shouldBe Right(2L)
        matching(store, MetadataFilter.All) shouldBe Set("late", "later")
      }
    }

    "write plain rows so that an earlier version's pattern still reads and filters them" in withFile { path =>
      withFileStore(path)(store => add(store, record("r", "k" -> "v", "l" -> "w")))
      val text = storedText(path)("r")
      """"([^"]+)":"([^"]*)"""".r.findAllMatchIn(text).map(m => m.group(1) -> m.group(2)).toMap shouldBe
        Map("k" -> "v", "l" -> "w")
      text should include("\"k\":\"v\"")
    }

    "take a backslash before a quote as text" in withOldFile("r" -> """{"p":"C:\\new \"q\""}""") { path =>
      withFileStore(path)(metadataOf(_, "r")) shouldBe Map("p" -> """C:\\new \"q\"""")
    }

    "read old rows added after new ones" in withOldFile("old" -> """{"k":"v"}""") { path =>
      withFileStore(path)(store => add(store, record("new", "k" -> "C:\\new")))
      withJdbc(path) { connection =>
        insertOldRow(connection, "late", """{"k":"C:\new"}""")
        insertOldRow(connection, "bad", """{"k":"a "b""}""")
      }
      withFileStore(path) { store =>
        metadataOf(store, "new") shouldBe Map("k" -> "C:\\new")
        matching(store, MetadataFilter.Equals("k", "C:\\new")) shouldBe Set("new", "late")
        matching(store, MetadataFilter.Equals("k", "a \"b\"")) shouldBe Set("bad")
      }
    }

    "read a row an earlier version adds while the store is open" in withOldFile("old" -> """{"k":"v"}""") { path =>
      withFileStore(path) { store =>
        withJdbc(path)(connection => insertOldRow(connection, "late", """{"k":"a "b""}"""))
        metadataOf(store, "late") shouldBe Map("k" -> "a \"b\"")
        matching(store, MetadataFilter.Equals("k", "v")) shouldBe Set("old")
      }
    }
  }
}
