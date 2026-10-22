package org.llm4s.agent.graph.postgres

import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import org.llm4s.it.Tier

import java.sql.{ Connection, DriverManager }
import java.time.{ Instant, OffsetDateTime, ZoneOffset }
import java.util.UUID
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * The database the PostgreSQL checkpointer's integration suites run against: CI's `Integration Tests` job's pgvector
 * service (`sbt testIntegration`). Locally a suite is cancelled unless `POSTGRES_TEST_ENABLED=true`, with the
 * connection in `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD`:
 *
 * {{{
 * docker run -d -e POSTGRES_PASSWORD=postgres -p 5432:5432 pgvector/pgvector:pg16
 * POSTGRES_TEST_ENABLED=true POSTGRES_PASSWORD=postgres sbt "it/testOnly org.llm4s.agent.graph.postgres.*"
 * }}}
 *
 * Every suite works in schemas of its own (`llm4s_it_<random>`), dropped when it ends.
 */
object PgCheckpointerDb {

  def enabled: Boolean = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  def require(): Unit = Tier.require(enabled, "POSTGRES_TEST_ENABLED=true not set")

  val jdbcUrl: String = {
    val host     = sys.env.getOrElse("POSTGRES_HOST", "localhost")
    val port     = sys.env.getOrElse("POSTGRES_PORT", "5432")
    val database = sys.env.getOrElse("POSTGRES_DB", "postgres")
    s"jdbc:postgresql://$host:$port/$database"
  }
  val user: String     = sys.env.getOrElse("POSTGRES_USER", "postgres")
  val password: String = sys.env.getOrElse("POSTGRES_PASSWORD", "password")

  def freshSchema(): String = s"llm4s_it_${UUID.randomUUID().toString.replace("-", "").take(16)}"

  def connect(): Connection = DriverManager.getConnection(jdbcUrl, user, password)

  def withConnection[A](body: Connection => A): A = Using.resource(connect())(body)

  def execute(sql: String): Unit = withConnection(c => Using.resource(c.createStatement())(_.execute(sql): Unit))

  def scalar[A](sql: String)(read: java.sql.ResultSet => A): A =
    withConnection { c =>
      Using.resource(c.createStatement()) { st =>
        Using.resource(st.executeQuery(sql)) { rows =>
          rows.next()
          read(rows)
        }
      }
    }

  def dropSchema(schema: String): Unit = Try(execute(s"""DROP SCHEMA IF EXISTS "$schema" CASCADE""")): Unit

  /** A small HikariCP pool that opens connections only on demand, so many stores fit in the server's limit. */
  def pool(maxSize: Int = 4, connectionTimeout: FiniteDuration = 5.seconds): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(jdbcUrl)
    config.setUsername(user)
    config.setPassword(password)
    config.setMaximumPoolSize(maxSize)
    config.setMinimumIdle(0)
    config.setConnectionTimeout(connectionTimeout.toMillis)
    config.addDataSourceProperty("socketTimeout", "30")
    new HikariDataSource(config)
  }

  /** A store over a pool of its own, judging time by `clockSql`; it fails the test if it cannot open. */
  def store(
    config: PostgresCheckpointerConfig,
    clockSql: String = PostgresCheckpointer.ServerClock,
    maxPoolSize: Int = 4
  ): PostgresCheckpointer =
    PostgresCheckpointer
      .createWith(pool(maxPoolSize, config.connectionTimeout), ownsDataSource = true, config, clockSql)
      .fold(e => throw new IllegalStateException(e.message), identity)

  /**
   * Clocks a test moves, kept in a table of the schema `schema`, one row per clock: a store whose `clockSql` is
   * [[sql]] reads its clock in the database, as it reads `clock_timestamp()` outside tests.
   */
  final class Clocks(schema: String) {
    private val table = s""""$schema".it_clocks"""
    execute(s"""CREATE SCHEMA IF NOT EXISTS "$schema"""")
    execute(s"CREATE TABLE IF NOT EXISTS $table (id text PRIMARY KEY, at timestamptz NOT NULL)")

    /** A new clock reading `at`; its id. */
    def create(at: Instant): String = {
      val id = UUID.randomUUID().toString
      set(id, at, insert = true)
      id
    }

    def set(id: String, at: Instant, insert: Boolean = false): Unit =
      withConnection { c =>
        val sql =
          if (insert) s"INSERT INTO $table (at, id) VALUES (?, ?)" else s"UPDATE $table SET at = ? WHERE id = ?"
        Using.resource(c.prepareStatement(sql)) { st =>
          st.setObject(1, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
          st.setString(2, id)
          st.executeUpdate(): Unit
        }
      }

    /** The SQL expression a store reads clock `id` with. */
    def sql(id: String): String = s"(SELECT at FROM $table WHERE id = '$id')"
  }
}
