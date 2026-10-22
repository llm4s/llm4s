package org.llm4s.agent.graph.spi

import org.llm4s.agent.graph.InMemoryCheckpointer
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLClassLoader
import java.nio.file.Files
import scala.concurrent.duration.*

/** A backend `ServiceLoader` can instantiate: a top-level class with a public no-arg constructor. */
final class FixtureCheckpointerBackend extends CheckpointerBackend:
  val kind: String = "Fixture"
  def open(settings: CheckpointerSettings): Result[CheckpointStore] =
    Right(CheckpointStore.unmanaged(kind, new InMemoryCheckpointer()))

/** A backend whose `kind` throws: discovery records it rather than failing. */
final class BrokenKindCheckpointerBackend extends CheckpointerBackend:
  def kind: String                                                  = throw new IllegalStateException("no kind")
  def open(settings: CheckpointerSettings): Result[CheckpointStore] = Left(ConfigurationError("never"))

/** A backend from a half-present jar: its `kind` throws a `LinkageError`, which discovery records too. */
final class LinkageKindCheckpointerBackend extends CheckpointerBackend:
  def kind: String = throw new NoClassDefFoundError("com/example/MissingDriver")
  def open(settings: CheckpointerSettings): Result[CheckpointStore] = Left(ConfigurationError("never"))

/** Stage 5 slice S5 (#2133, design 4.54): the checkpoint store backend registry and its settings. */
class CheckpointerBackendsSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def loaderWith(entries: String*): ClassLoader = {
    val dir      = Files.createTempDirectory("checkpointer-backends")
    val services = dir.resolve("META-INF/services")
    Files.createDirectories(services)
    Files.writeString(services.resolve(classOf[CheckpointerBackend].getName), entries.mkString("\n"))
    new URLClassLoader(Array(dir.toUri.toURL), getClass.getClassLoader)
  }

  "CheckpointerBackends.empty" should "still serve the built-in memory kind" in {
    val store = CheckpointerBackends.empty.find("MEMORY").value.open(CheckpointerSettings("memory")).value
    store.kind shouldBe "memory"
    store.checkpointer shouldBe a[InMemoryCheckpointer]
    // the queue in the same store: what a submission through it records, the checkpointer's commit fence sees
    store.runQueue.value shouldBe theSameInstanceAs(store.checkpointer.asInstanceOf[InMemoryCheckpointer].runQueue)
    CheckpointerBackends.empty.kinds shouldBe Vector("memory")
    CheckpointerBackends.empty.find("sqlite") shouldBe None
  }

  "discover" should "find backends in META-INF/services and record the ones it cannot load" in {
    val backends = CheckpointerBackends.discover(
      loaderWith(
        classOf[FixtureCheckpointerBackend].getName,
        "org.llm4s.NoSuchBackend",
        classOf[BrokenKindCheckpointerBackend].getName
      )
    )
    backends.discovered shouldBe true
    backends.find("fixture").value shouldBe a[FixtureCheckpointerBackend]
    (backends.kinds should contain).allOf("memory", "fixture")
    backends.failures should have size 2
    backends.failures.mkString should (include("NoSuchBackend").and(include("BrokenKindCheckpointerBackend")))
  }

  it should "record a backend that throws a LinkageError rather than failing" in {
    val backends = CheckpointerBackends.discover(
      loaderWith(classOf[LinkageKindCheckpointerBackend].getName, classOf[FixtureCheckpointerBackend].getName)
    )
    backends.find("fixture").value shouldBe a[FixtureCheckpointerBackend]
    backends.failures should have size 1
    backends.failures.head should (include("NoClassDefFoundError").and(include("MissingDriver")))
    backends.toString shouldBe "CheckpointerBackends(memory, fixture)"
  }

  "withBackend" should "replace a backend for the same kind, the built-in memory one included" in {
    val replacement = new CheckpointerBackend:
      val kind                                                          = "memory"
      def open(settings: CheckpointerSettings): Result[CheckpointStore] = Left(ConfigurationError("replaced"))
    val backends = CheckpointerBackends.of(new FixtureCheckpointerBackend).withBackend(replacement)
    backends.find("memory").value shouldBe replacement
    backends.kinds shouldBe Vector("memory", "fixture")
    backends.discovered shouldBe false
  }

  "CheckpointStore" should "close what was opened once" in {
    var closes = 0
    val store  = CheckpointStore("x", new InMemoryCheckpointer(), () => closes += 1)
    store.close()
    store.close()
    closes shouldBe 1
    store.isClosed shouldBe true
    store.toString shouldBe "CheckpointStore(x)"
  }

  it should "answer a ConfigurationError for the run queue of a store given none, and share its close with the queue's" in {
    var closes = 0
    val memory = new InMemoryCheckpointer()
    val plain  = CheckpointStore("x", memory, () => closes += 1)
    val error  = plain.runQueue.left.value
    error shouldBe a[ConfigurationError]
    error.message should include("'x'")
    val queued = plain.withRunQueue(memory.runQueue)
    queued.runQueue.value shouldBe theSameInstanceAs(memory.runQueue)
    (queued.kind, queued.checkpointer) shouldBe (plain.kind, plain.checkpointer)
    queued.close()
    plain.close()
    closes shouldBe 1
    plain.isClosed shouldBe true
  }

  "CheckpointerSettings.redact" should "hide the values of every credential-like key and parameter" in {
    val s = CheckpointerSettings(
      "x",
      Map(
        "sslPassphrase" -> "phrase-1",
        "authHeader"    -> "Bearer tok-2",
        "pwd"           -> "pw-3",
        "dsn"           -> "host=db pass=pw-4",
        "schema"        -> "plain"
      )
    )
    val text = "phrase-1 Bearer tok-2 pw-3 host=db pass=pw-4 pw-4 plain"
    val out  = s.redact(text)
    Vector("phrase-1", "tok-2", "pw-3", "pw-4").foreach(secret => (out should not).include(secret))
    out should include("plain")
  }

  "CheckpointerSettings" should "read typed values and name the full key, never the value, when one is wrong" in {
    val s = CheckpointerSettings(
      " Fixture ",
      Map(
        "url"     -> "jdbc:x",
        "timeout" -> "250ms",
        "bare"    -> "1500",
        "size"    -> "4",
        "on"      -> "TRUE",
        "secret"  -> "hunter2"
      )
    )
    s.kind shouldBe "fixture"
    s.string("url").value shouldBe "jdbc:x"
    s.duration("timeout", 1.second).value shouldBe 250.millis
    s.duration("bare", 1.second).value shouldBe 1500.millis
    s.duration("missing", 1.second).value shouldBe 1.second
    s.int("size", 1).value shouldBe 4
    s.boolean("on", default = false).value shouldBe true

    val missing = s.string("user", Some("FIXTURE_USER")).left.value
    missing.message should (include("llm4s.agent.checkpointer.fixture.user").and(include("FIXTURE_USER")))

    val wrong = s.int("secret", 1).left.value
    wrong.message should include("llm4s.agent.checkpointer.fixture.secret")
    (wrong.message should not).include("hunter2")
    (s.duration("secret", 1.second).left.value.message should not).include("hunter2")
    (s.boolean("secret", default = false).left.value.message should not).include("hunter2")
    (s.toString should not).include("hunter2")
  }

  it should "treat a blank value as absent" in {
    val s = CheckpointerSettings("x", Map("path" -> "  "))
    s.optionalString("path") shouldBe None
    s.string("path").isLeft shouldBe true
  }
}
