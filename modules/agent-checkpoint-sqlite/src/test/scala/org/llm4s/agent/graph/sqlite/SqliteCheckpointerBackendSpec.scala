package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.graph.spi.{ CheckpointStore, CheckpointerBackends }
import org.llm4s.config.{ CheckpointerConfigLoader, ReferenceConfig }
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }

/**
 * Stage 5 slice S5 (#2133, design 4.54): `llm4s.agent.checkpointer.kind = sqlite` opens a [[SqliteCheckpointer]]
 * through the backend this module registers, from its `reference.conf` block.
 */
class SqliteCheckpointerBackendSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def open(hocon: String, env: Map[String, String] = Map.empty): Result[CheckpointStore] =
    CheckpointerConfigLoader.open(ReferenceConfig.withEnv(hocon, env), CheckpointerBackends.discover())

  private def freshFile(): Path = Files.createTempDirectory("sqlite-backend").resolve("threads.db")

  "The module" should "register its backend in META-INF/services" in {
    val backends = CheckpointerBackends.discover()
    backends.find("sqlite").value shouldBe a[SqliteCheckpointerBackend]
    backends.failures shouldBe empty
  }

  "kind = sqlite" should "open the file LLM4S_CHECKPOINTER_SQLITE_PATH names, and close it with the store" in {
    val file  = freshFile()
    val store = open("", Map("LLM4S_CHECKPOINTER" -> "sqlite", "LLM4S_CHECKPOINTER_SQLITE_PATH" -> file.toString)).value
    store.kind shouldBe "sqlite"
    val sqlite = store.checkpointer.asInstanceOf[SqliteCheckpointer]
    sqlite.path shouldBe file
    store.checkpointer.latest(ThreadId("t")).value shouldBe None
    // the file's queue, on the store's own connection
    store.runQueue.value shouldBe theSameInstanceAs(sqlite.runQueue)
    Files.exists(file) shouldBe true
    store.close()
    sqlite.isClosed shouldBe true
  }

  it should "take the path and busy timeout from application.conf" in {
    val file  = freshFile()
    val quote = file.toString.replace("\\", "\\\\")
    val store =
      open(s"""llm4s.agent.checkpointer { kind = sqlite, sqlite { path = "$quote", busyTimeout = 2 seconds } }""").value
    val path = store.checkpointer.asInstanceOf[SqliteCheckpointer].path
    store.close()
    path shouldBe file
  }

  "A missing path" should "fail the load naming the key and its variable" in {
    val error = open("llm4s.agent.checkpointer.kind = sqlite").left.value
    error shouldBe a[ConfigurationError]
    error.message should (include("llm4s.agent.checkpointer.sqlite.path").and(
      include("LLM4S_CHECKPOINTER_SQLITE_PATH")
    ))
  }

  "A busy timeout the store refuses" should "fail the load naming the key" in {
    val error = open(
      s"""llm4s.agent.checkpointer { kind = sqlite, sqlite { path = "x.db", busyTimeout = 0 seconds } }"""
    ).left.value
    error shouldBe a[ConfigurationError]
    error.message should include("llm4s.agent.checkpointer.sqlite.busyTimeout")
  }

  "A path the file system refuses" should "fail the load naming the key" in {
    val error =
      open("", Map("LLM4S_CHECKPOINTER" -> "sqlite", "LLM4S_CHECKPOINTER_SQLITE_PATH" -> "threads\u0000.db")).left.value
    error shouldBe a[ConfigurationError]
    error.message should include("llm4s.agent.checkpointer.sqlite.path is not a valid path")
  }

  "A path whose directory does not exist" should "fail the load" in {
    val missing = Files.createTempDirectory("sqlite-backend").resolve("no/such/dir/threads.db")
    open(
      s"""llm4s.agent.checkpointer { kind = sqlite, sqlite.path = "${missing.toString.replace("\\", "\\\\")}" }"""
    ).isLeft shouldBe true
  }
}
