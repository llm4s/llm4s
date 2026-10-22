package org.llm4s.agent.graph.postgres

import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.graph.spi.CheckpointerBackends
import org.llm4s.config.{ CheckpointerConfigLoader, ReferenceConfig, RuntimeConfigLoader }
import org.llm4s.it.tags.Docker
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Stage 5 slice S5 (#2133, design 4.54): `llm4s.agent.checkpointer.kind = postgres` opens a working
 * `PostgresCheckpointer` on a real server, from the variables the module's `reference.conf` binds, in the schema the
 * configuration names; and a runtime is built over it with the configured policies.
 *
 * Tier 2 (`@Docker`): see [[PgCheckpointerDb]].
 */
@Docker
class PostgresCheckpointerBackendItSpec extends AnyFlatSpec with Matchers with EitherValues {

  "kind = postgres" should "open the store the configuration describes, and close its pool with it" in {
    PgCheckpointerDb.require()
    val schema = PgCheckpointerDb.freshSchema()
    val source = ReferenceConfig.withEnv(
      s"llm4s.agent.checkpointer.postgres { schema = $schema, maxPoolSize = 2 }",
      Map(
        "LLM4S_CHECKPOINTER"                   -> "postgres",
        "LLM4S_CHECKPOINTER_POSTGRES_URL"      -> PgCheckpointerDb.jdbcUrl,
        "LLM4S_CHECKPOINTER_POSTGRES_USER"     -> PgCheckpointerDb.user,
        "LLM4S_CHECKPOINTER_POSTGRES_PASSWORD" -> PgCheckpointerDb.password
      )
    )
    val store = CheckpointerConfigLoader.open(source, CheckpointerBackends.discover()).value
    val pg    = store.checkpointer.asInstanceOf[PostgresCheckpointer]
    pg.schema shouldBe schema
    pg.config.maxPoolSize shouldBe 2
    store.checkpointer.latest(ThreadId("absent")).value shouldBe None
    RuntimeConfigLoader.runtime(source, store.checkpointer).isRight shouldBe true
    // the schema's queue, on the store's own pool: no queue setting repeats the connection
    store.runQueue.value shouldBe theSameInstanceAs(pg.runQueue)
    store.runQueue.value.openEntries(None, ThreadId("absent")).value shouldBe empty
    store.close()
    pg.isClosed shouldBe true
    PgCheckpointerDb.dropSchema(schema)
  }
}
