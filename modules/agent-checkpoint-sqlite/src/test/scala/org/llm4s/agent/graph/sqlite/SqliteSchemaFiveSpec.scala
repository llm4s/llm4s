package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.{ Checkpoint, RunOutcome, ThreadId }
import org.llm4s.agent.graph.sqlite.TextOnlyThreadFixture.*
import org.llm4s.llmconnect.model.AssistantMessage
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, StandardCopyOption }
import java.sql.DriverManager
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.Using

/**
 * A SQLite file written by a schema-5, format-7 build (#2045), opened by this one: it migrates to schema 6, its
 * thread restores as a top-level thread with no outcome, lists no children, and continues.
 *
 * `fixtures/schema-5-before-children.db` was written by 7623bd442 (the Phase 4 spec on `main`, the last schema-5
 * build) with the program below, run as
 * `sbt "agentCheckpointSqlite/Test/runMain org.llm4s.agent.graph.sqlite.WriteSchemaFiveFixture <target>"`: thread
 * `before-children` holds one completed turn of [[TextOnlyThreadFixture]]'s agent, `lookup` called and answered.
 *
 * {{{
 * object WriteSchemaFiveFixture:
 *   def main(args: Array[String]): Unit =
 *     val target = Path.of(args(0))
 *     Files.deleteIfExists(target)
 *     val store  = SqliteFiles.opened(target, Clock.systemUTC())
 *     val thread = ThreadId("before-children")
 *     val client = Scripted(
 *       completion(AssistantMessage(None, Seq(call("c1", "lookup")))),
 *       completion(AssistantMessage("It is sunny."))
 *     )
 *     val first = agent(client, store, AtomicBoolean(false)).run(thread, "What is the weather?")
 *     require(first.exists(_.answer.contains("It is sunny.")), s"first turn: $first")
 *     val stored = store.latest(thread).fold(e => sys.error(e.message), identity).get
 *     require(stored.checkpoint.formatVersion == 7 && Checkpoint.CurrentFormat == 7, "not a format-7 build")
 *     require(SqliteSchema.Current == 5, "not a schema-5 build")
 *     store.close()
 * }}}
 */
class SqliteSchemaFiveSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val thread = ThreadId("before-children")

  private def schemaVersion(store: SqliteCheckpointer): Int =
    Using.resource(DriverManager.getConnection(SqliteCheckpointer.jdbcUrl(store.path))) { c =>
      Using.resource(c.createStatement())(s =>
        Using.resource(s.executeQuery("SELECT MAX(version) FROM llm4s_checkpoint_schema")) { r =>
          r.next(); r.getInt(1)
        }
      )
    }

  "A schema-5 file of format-7 threads" should "migrate, restore its thread as top-level and continue it" in {
    val db = SqliteFiles.fresh()
    Files.copy(
      getClass.getResourceAsStream("/fixtures/schema-5-before-children.db"),
      db,
      StandardCopyOption.REPLACE_EXISTING
    )
    val store = SqliteFiles.opened(db, Clock.systemUTC())
    schemaVersion(store) shouldBe SqliteSchema.Current
    SqliteSchema.Current shouldBe 6

    val stored = store.latest(thread).value.value.checkpoint
    stored.formatVersion shouldBe Checkpoint.CurrentFormat
    stored.origin shouldBe None
    stored.outcome shouldBe None
    store.children(thread, None, 10).value shouldBe empty

    val client = Scripted(completion(AssistantMessage("Still sunny.")))
    val agent  = TextOnlyThreadFixture.agent(client, store, AtomicBoolean(false))
    agent.children(thread).value shouldBe empty
    agent.run(thread, "And now?").value.answer shouldBe Some("Still sunny.")
    store.latest(thread).value.value.checkpoint.outcome shouldBe Some(RunOutcome.Answered)
    store.close()

    // a new process reads the continued thread back at schema 6
    val reopened = SqliteFiles.opened(db, Clock.systemUTC())
    reopened.latest(thread).value.value.checkpoint.outcome shouldBe Some(RunOutcome.Answered)
    reopened.children(thread, None, 10).value shouldBe empty
    reopened.close()
  }
}
