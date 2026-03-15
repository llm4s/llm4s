package org.llm4s.config

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
}
