package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.spi.{ CheckpointerBackends, CheckpointerSettings }
import org.llm4s.config.{ CheckpointerConfigLoader, ReferenceConfig }
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * Stage 5 slice S5 (#2133, design 4.54): `llm4s.agent.checkpointer.kind = postgres` through the backend this module
 * registers, from its `reference.conf` block - the parts that need no server. Opening against a real server is
 * `modules/it`'s `PostgresCheckpointerBackendItSpec` (`@Docker`).
 */
class PostgresCheckpointerBackendSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val Env = Map(
    "LLM4S_CHECKPOINTER"                   -> "postgres",
    "LLM4S_CHECKPOINTER_POSTGRES_URL"      -> "jdbc:postgresql://db:5432/app",
    "LLM4S_CHECKPOINTER_POSTGRES_USER"     -> "agents",
    "LLM4S_CHECKPOINTER_POSTGRES_PASSWORD" -> "hunter2"
  )

  private def settings(hocon: String, env: Map[String, String] = Env): CheckpointerSettings =
    CheckpointerConfigLoader.settings(ReferenceConfig.withEnv(hocon, env)).value

  private def connection(
    hocon: String,
    env: Map[String, String] = Env
  ): Result[PostgresCheckpointerBackend.Connection] =
    PostgresCheckpointerBackend.connection(settings(hocon, env))

  "The module" should "register its backend in META-INF/services" in {
    val backends = CheckpointerBackends.discover()
    backends.find("postgres").value shouldBe a[PostgresCheckpointerBackend]
    backends.failures shouldBe empty
  }

  "The reference.conf block" should "bind the connection to its variables and default to PostgresCheckpointerConfig.default" in {
    val c = connection("").value
    (c.url, c.user, c.password) shouldBe ("jdbc:postgresql://db:5432/app", "agents", "hunter2")
    c.config shouldBe PostgresCheckpointerConfig.default
    (c.toString should not).include("hunter2")
    (settings("").toString should not).include("hunter2")
  }

  it should "take the schema, timeouts and pool size from application.conf" in {
    val c = connection(
      """llm4s.agent.checkpointer.postgres {
        |  schema = agents, callTimeout = 2 seconds, connectionTimeout = 500ms, maxPoolSize = 3
        |}""".stripMargin
    ).value
    c.config shouldBe PostgresCheckpointerConfig("agents", 2.seconds, 500.millis, 3)
  }

  "A connection with no password" should "use an empty one" in {
    connection("", Env - "LLM4S_CHECKPOINTER_POSTGRES_PASSWORD").value.password shouldBe ""
  }

  "A missing url or user" should "fail the load naming the key and its variable" in {
    val url = connection("", Env - "LLM4S_CHECKPOINTER_POSTGRES_URL").left.value
    url shouldBe a[ConfigurationError]
    url.message should (include("llm4s.agent.checkpointer.postgres.url").and(
      include("LLM4S_CHECKPOINTER_POSTGRES_URL")
    ))
    connection("", Env - "LLM4S_CHECKPOINTER_POSTGRES_USER").left.value.message should
      include("llm4s.agent.checkpointer.postgres.user")
  }

  "A url that is not a PostgreSQL JDBC URL" should "be refused before anything is opened" in {
    val error = connection("", Env + ("LLM4S_CHECKPOINTER_POSTGRES_URL" -> "postgres://db/app")).left.value
    error.message should include("jdbc:postgresql://")
  }

  "A url that carries a credential" should "be refused, without showing it" in {
    Vector(
      "jdbc:postgresql://db:5432/app?password=urlpw",
      "jdbc:postgresql://db:5432/app?ssl=true&sslpassword=urlpw",
      "jdbc:postgresql://agents:urlpw@db:5432/app"
    ).foreach { url =>
      val error = connection("", Env + ("LLM4S_CHECKPOINTER_POSTGRES_URL" -> url)).left.value
      withClue(url) {
        error.message should include("LLM4S_CHECKPOINTER_POSTGRES_PASSWORD")
        (error.message should not).include("urlpw")
      }
    }
    connection("", Env + ("LLM4S_CHECKPOINTER_POSTGRES_URL" -> "jdbc:postgresql://db/app?ssl=true")).isRight shouldBe
      true
  }

  "Settings the store refuses" should "fail the load naming the block" in {
    connection("llm4s.agent.checkpointer.postgres.schema = Agents").left.value.message should
      (include("llm4s.agent.checkpointer.postgres").and(include("schema")))
    connection("llm4s.agent.checkpointer.postgres.connectionTimeout = 10ms").left.value.message should
      include("connectionTimeout")
    connection("llm4s.agent.checkpointer.postgres.maxPoolSize = lots").left.value.message should
      include("llm4s.agent.checkpointer.postgres.maxPoolSize")
  }

  "A server that cannot be reached" should "fail the load within the connection timeout, never showing the password" in {
    val started = System.nanoTime()
    val error = CheckpointerConfigLoader
      .open(
        ReferenceConfig.withEnv(
          "llm4s.agent.checkpointer.postgres.connectionTimeout = 250ms",
          Env + ("LLM4S_CHECKPOINTER_POSTGRES_URL" -> "jdbc:postgresql://127.0.0.1:1/app")
        ),
        CheckpointerBackends.discover()
      )
      .left
      .value
    (System.nanoTime() - started).nanos should be < 30.seconds
    (error.message should not).include("hunter2")
  }
}
