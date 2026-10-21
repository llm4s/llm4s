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
