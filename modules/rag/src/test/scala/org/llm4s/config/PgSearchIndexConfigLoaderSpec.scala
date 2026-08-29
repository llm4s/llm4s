package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.EitherValues

/**
 * Unit tests for PgSearchIndexConfigLoader.
 *
 * Uses ConfigSource.string() to provide deterministic HOCON input
 * without relying on environment variables or external configuration files.
 */
class PgSearchIndexConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  "PgSearchIndexConfigLoader" should {

    "successfully load valid PgConfig" in {
      val hocon =
        """
          |llm4s {
          |  rag {
          |    permissions {
          |      pg {
          |        host = "db.example.com"
          |        port = 5433
          |        database = "vectors_db"
          |        user = "pguser"
          |        password = "pgpass123"
          |        vectorTableName = "my_vectors"
          |        keywordTableName = "my_keywords"
          |        maxPoolSize = 20
          |      }
          |    }
          |  }
          |}
          |""".stripMargin

      val result = PgSearchIndexConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val pg = result.value
      pg.host shouldBe "db.example.com"
      pg.port shouldBe 5433
      pg.database shouldBe "vectors_db"
      pg.user shouldBe "pguser"
      pg.password shouldBe "pgpass123"
      pg.vectorTableName shouldBe "my_vectors"
      pg.keywordTableName shouldBe "my_keywords"
      pg.maxPoolSize shouldBe 20
    }

    "fail when required fields are missing" in {
      val hocon =
        """
          |llm4s {
          |  rag {
          |    permissions {
          |      pg {
          |        port = 5432
          |      }
          |    }
          |  }
          |}
          |""".stripMargin

      val result = PgSearchIndexConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
    }

    "fail when pg section is entirely missing" in {
      val hocon =
        """
          |llm4s {
          |  rag {
          |    permissions { }
          |  }
          |}
          |""".stripMargin

      val result = PgSearchIndexConfigLoader.load(ConfigSource.string(hocon))

      result.isLeft shouldBe true
    }

    "use default values where applicable" in {
      val hocon =
        """
          |llm4s {
          |  rag {
          |    permissions {
          |      pg {
          |        host = "localhost"
          |        port = 5432
          |        database = "postgres"
          |        user = "postgres"
          |        password = ""
          |        vectorTableName = "vectors"
          |        keywordTableName = "documents"
          |        maxPoolSize = 10
          |      }
          |    }
          |  }
          |}
          |""".stripMargin

      val result = PgSearchIndexConfigLoader.load(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val pg = result.value
      pg.host shouldBe "localhost"
      pg.port shouldBe 5432
      pg.database shouldBe "postgres"
      pg.vectorTableName shouldBe "vectors"
      pg.keywordTableName shouldBe "documents"
      pg.maxPoolSize shouldBe 10
      pg.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/postgres"
    }
  }

  "PgSearchIndexConfigLoader.default" should {

    "load the defaults shipped in llm4s-rag's reference.conf" in {
      val pgKeys = Set(
        "llm4s.rag.permissions.pg.host",
        "llm4s.rag.permissions.pg.port",
        "llm4s.rag.permissions.pg.database",
        "llm4s.rag.permissions.pg.user",
        "llm4s.rag.permissions.pg.password",
        "llm4s.rag.permissions.pg.vectorTableName",
        "llm4s.rag.permissions.pg.keywordTableName",
        "llm4s.rag.permissions.pg.maxPoolSize"
      )
      withProps(Map.empty, pgKeys) {
        val pg = PgSearchIndexConfigLoader.default().value
        pg.host shouldBe "localhost"
        pg.port shouldBe 5432
        pg.database shouldBe "postgres"
        pg.vectorTableName shouldBe "vectors"
        pg.maxPoolSize shouldBe 10
      }
    }

    "apply overrides from the ambient configuration" in {
      val props = Map(
        "llm4s.rag.permissions.pg.host"             -> "localhost",
        "llm4s.rag.permissions.pg.port"             -> "5432",
        "llm4s.rag.permissions.pg.database"         -> "testdb",
        "llm4s.rag.permissions.pg.user"             -> "testuser",
        "llm4s.rag.permissions.pg.password"         -> "testpass",
        "llm4s.rag.permissions.pg.vectorTableName"  -> "test_vectors",
        "llm4s.rag.permissions.pg.keywordTableName" -> "test_keywords",
        "llm4s.rag.permissions.pg.maxPoolSize"      -> "5"
      )
      withProps(props) {
        val pg = PgSearchIndexConfigLoader.default().value
        pg.host shouldBe "localhost"
        pg.port shouldBe 5432
        pg.database shouldBe "testdb"
        pg.user shouldBe "testuser"
        pg.password shouldBe "testpass"
        pg.vectorTableName shouldBe "test_vectors"
        pg.keywordTableName shouldBe "test_keywords"
        pg.maxPoolSize shouldBe 5
      }
    }
  }

  /**
   * `default()` reads the ambient configuration, so these cases drive it through system
   * properties and restore whatever was there before.
   */
  private def withProps(props: Map[String, String], clearKeys: Set[String] = Set.empty)(
    f: => Unit
  ): Unit = {
    val allKeys   = props.keySet ++ clearKeys
    val originals = allKeys.map(k => k -> Option(System.getProperty(k))).toMap
    // scalafix:off DisableSyntax.NoTryCatch
    try {
      clearKeys.foreach(System.clearProperty)
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally {
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
      ConfigFactory.invalidateCaches()
    }
    // scalafix:on DisableSyntax.NoTryCatch
  }
}
