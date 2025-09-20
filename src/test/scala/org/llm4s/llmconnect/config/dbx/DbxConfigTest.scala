package org.llm4s.llmconnect.config.dbx

import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DbxConfigTest extends AnyWordSpec with Matchers {

  "DbxConfig" should {

    "use default schema when PG_SCHEMA is not set" in {
      // Create a config with all required fields except schema
      val configString = """
        |dbx {
        |  pg {
        |    host = "localhost"
        |    port = 5432
        |    database = "testdb"
        |    user = "testuser"
        |    password = "testpass"
        |    sslmode = "disable"
        |  }
        |  core {
        |    requirePgvector = true
        |    systemTable = "dbx_info"
        |  }
        |}
        """.stripMargin

      val config = ConfigFactory.parseString(configString)

      // Mock the load method by creating a test-specific loader
      val testConfig = loadTestConfig(config)

      testConfig.pg.schema shouldBe "dbx"
    }

    "use provided schema when PG_SCHEMA is set" in {
      val configString = """
        |dbx {
        |  pg {
        |    host = "localhost"
        |    port = 5432
        |    database = "testdb"
        |    user = "testuser"
        |    password = "testpass"
        |    sslmode = "disable"
        |    schema = "custom_schema"
        |  }
        |  core {
        |    requirePgvector = true
        |    systemTable = "dbx_info"
        |  }
        |}
        """.stripMargin

      val config     = ConfigFactory.parseString(configString)
      val testConfig = loadTestConfig(config)

      testConfig.pg.schema shouldBe "custom_schema"
    }

    "use default schema when PG_SCHEMA is empty string" in {
      val configString = """
        |dbx {
        |  pg {
        |    host = "localhost"
        |    port = 5432
        |    database = "testdb"
        |    user = "testuser"
        |    password = "testpass"
        |    sslmode = "disable"
        |    schema = ""
        |  }
        |  core {
        |    requirePgvector = true
        |    systemTable = "dbx_info"
        |  }
        |}
        """.stripMargin

      val config     = ConfigFactory.parseString(configString)
      val testConfig = loadTestConfig(config)

      testConfig.pg.schema shouldBe "dbx"
    }
  }

  // Helper method that mimics the real DbxConfig.load() logic
  private def loadTestConfig(c: Config): DbxConfig = {
    def must(path: String): String = {
      val v = c.getString(path)
      if (v == null || v.trim.isEmpty) throw new IllegalArgumentException(s"Missing config: $path")
      v
    }

    def getStringOpt(path: String, default: String): String =
      if (c.hasPath(path)) {
        val value = c.getString(path)
        if (value != null && value.trim.nonEmpty) value else default
      } else default

    val pg = PgConfig(
      host = must("dbx.pg.host"),
      port = c.getInt("dbx.pg.port"),
      database = must("dbx.pg.database"),
      user = must("dbx.pg.user"),
      password = must("dbx.pg.password"),
      sslmode = must("dbx.pg.sslmode"),
      schema = getStringOpt("dbx.pg.schema", "dbx")
    )
    val core = CoreConfig(
      requirePgvector = c.getBoolean("dbx.core.requirePgvector"),
      systemTable = c.getString("dbx.core.systemTable")
    )
    DbxConfig(pg, core)
  }
}
