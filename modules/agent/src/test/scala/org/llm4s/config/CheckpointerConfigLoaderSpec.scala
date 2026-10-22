package org.llm4s.config

import org.llm4s.agent.graph.InMemoryCheckpointer
import org.llm4s.agent.graph.spi.{
  CheckpointStore,
  CheckpointerBackend,
  CheckpointerBackends,
  CheckpointerSettings,
  FixtureCheckpointerBackend
}
import org.llm4s.error.{ ConfigurationError, ProcessingError }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLClassLoader
import java.nio.file.Files

/** Stage 5 slice S5 (#2133, design 4.54): choosing the checkpoint store in `application.conf`. */
class CheckpointerConfigLoaderSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private def source(hocon: String, env: Map[String, String] = Map.empty) = ReferenceConfig.withEnv(hocon, env)

  private def open(
    hocon: String,
    backends: CheckpointerBackends = CheckpointerBackends.empty
  ): Result[CheckpointStore] =
    CheckpointerConfigLoader.open(source(hocon), backends)

  /** Records the settings it was handed. */
  final private class Recording(val kind: String) extends CheckpointerBackend:
    @volatile var seen: Option[CheckpointerSettings] = None
    def open(settings: CheckpointerSettings): Result[CheckpointStore] =
      seen = Some(settings)
      Right(CheckpointStore.unmanaged(kind, new InMemoryCheckpointer()))

  "The reference.conf default" should "be the in-memory store" in {
    val store = open("").value
    store.kind shouldBe "memory"
    store.checkpointer shouldBe a[InMemoryCheckpointer]
  }

  "kind" should "be bound to LLM4S_CHECKPOINTER and matched case-insensitively" in {
    CheckpointerConfigLoader.settings(source("", Map("LLM4S_CHECKPOINTER" -> "Fixture"))).value.kind shouldBe "fixture"
    open(
      "llm4s.agent.checkpointer.kind = FIXTURE",
      CheckpointerBackends.of(new FixtureCheckpointerBackend)
    ).value.kind shouldBe "Fixture"
  }

  "A backend" should "get only its own block, flattened to strings" in {
    val recording = new Recording("custom")
    open(
      """llm4s.agent.checkpointer {
        |  kind = custom
        |  custom { url = "jdbc:x", pool { size = 4 }, timeout = 5 seconds }
        |  other = "not an object, and not read"
        |}""".stripMargin,
      CheckpointerBackends.of(recording)
    ).value
    recording.seen.value.extras shouldBe Map("url" -> "jdbc:x", "pool.size" -> "4", "timeout" -> "5 seconds")
  }

  it should "get an empty block when the configuration has none" in {
    val recording = new Recording("custom")
    open("llm4s.agent.checkpointer.kind = custom", CheckpointerBackends.of(recording)).value
    recording.seen.value.extras shouldBe empty
  }

  "A kind whose module is absent" should "fail the load with the module to add" in {
    val error = open("llm4s.agent.checkpointer.kind = postgres").left.value
    error shouldBe a[ConfigurationError]
    error.message should include("llm4s-agent-checkpoint-postgres")
    open("llm4s.agent.checkpointer.kind = sqlite").left.value.message should include("llm4s-agent-checkpoint-sqlite")
  }

  "An unknown kind" should "fail the load naming the available kinds" in {
    val error =
      open("llm4s.agent.checkpointer.kind = redis", CheckpointerBackends.of(new FixtureCheckpointerBackend)).left.value
    error.message should (include("'redis'").and(include("memory, fixture")))
  }

  it should "say which service entries could not be loaded" in {
    val dir      = Files.createTempDirectory("checkpointer-loader")
    val services = dir.resolve("META-INF/services")
    Files.createDirectories(services)
    Files.writeString(services.resolve(classOf[CheckpointerBackend].getName), "org.llm4s.NoSuchBackend")
    val backends = CheckpointerBackends.discover(new URLClassLoader(Array(dir.toUri.toURL), getClass.getClassLoader))
    val error    = open("llm4s.agent.checkpointer.kind = redis", backends).left.value
    error.message should (include("could not be loaded").and(include("NoSuchBackend")))
  }

  "The no-argument entry points" should "read the classpath configuration, whose default is the in-memory store" in {
    CheckpointerConfigLoader.settings().value.kind shouldBe "memory"
    CheckpointerConfigLoader.open().value.kind shouldBe "memory"
    CheckpointerConfigLoader.open(CheckpointerBackends.empty).value.checkpointer shouldBe a[InMemoryCheckpointer]
  }

  "A kind that is not a string" should "be refused naming the key" in {
    val error = open("llm4s.agent.checkpointer.kind { nested = true }").left.value
    error shouldBe a[ConfigurationError]
    error.message should include("Failed to read llm4s.agent.checkpointer.kind")
  }

  "A null block" should "be read as an empty one" in {
    val recording = new Recording("custom")
    open("llm4s.agent.checkpointer { kind = custom, custom = null }", CheckpointerBackends.of(recording)).value
    recording.seen.value.extras shouldBe empty
  }

  "A blank kind" should "be refused" in {
    open("""llm4s.agent.checkpointer.kind = " """").left.value.message should include("blank")
  }

  "The selected block" should "be refused, without its value, when it is not an object" in {
    val error =
      open(
        """llm4s.agent.checkpointer { kind = custom, custom = "s3cr3t" }""",
        CheckpointerBackends.of(new Recording("custom"))
      ).left.value
    error.message should include("llm4s.agent.checkpointer.custom must be an object")
    (error.message should not).include("s3cr3t")
  }

  "A backend that throws" should "be reported as a ConfigurationError" in {
    val throwing = new CheckpointerBackend:
      val kind                                                          = "boom"
      def open(settings: CheckpointerSettings): Result[CheckpointStore] = throw new IllegalStateException("bad")
    open("llm4s.agent.checkpointer.kind = boom", CheckpointerBackends.of(throwing)).left.value.message should
      include("IllegalStateException: bad")
  }

  it should "be reported, not thrown, when it throws a LinkageError (a stale or half-present jar)" in {
    val stale = new CheckpointerBackend:
      val kind = "stale"
      def open(settings: CheckpointerSettings): Result[CheckpointStore] =
        throw new NoClassDefFoundError("com/zaxxer/hikari/HikariConfig")
    open("llm4s.agent.checkpointer.kind = stale", CheckpointerBackends.of(stale)).left.value.message should
      include("NoClassDefFoundError")
  }

  /** A block with a password and a URL carrying another; what a driver might echo back. */
  private def SecretBlock: String =
    """llm4s.agent.checkpointer { kind = leaky, leaky { url = "jdbc:x://db/app?password=urlpw", password = "hunter2", schema = "plain" } }"""

  private def leaky(fail: CheckpointerSettings => Result[CheckpointStore]) = new CheckpointerBackend:
    val kind                                                          = "leaky"
    def open(settings: CheckpointerSettings): Result[CheckpointStore] = fail(settings)

  "A secret in what a backend throws" should "be redacted" in {
    val error = open(
      SecretBlock,
      CheckpointerBackends.of(leaky(_ => throw new IllegalStateException("cannot use hunter2 or urlpw")))
    ).left.value
    error.message should include("<redacted>")
    (error.message should not).include("hunter2")
    (error.message should not).include("urlpw")
  }

  "A secret in the error a backend returns" should "never leave in its message, context or cause" in {
    val inMessage = open(
      SecretBlock,
      CheckpointerBackends.of(leaky(_ => Left(ProcessingError("open", "bad url jdbc:x://db/app?password=urlpw"))))
    ).left.value
    inMessage shouldBe a[ConfigurationError]
    (inMessage.formatted should not).include("urlpw")

    val inCause = open(
      SecretBlock,
      CheckpointerBackends.of(
        leaky(_ =>
          Left(ProcessingError("open", "failed", Some(new RuntimeException("wrapped", new Exception("hunter2")))))
        )
      )
    ).left.value
    inCause shouldBe a[ConfigurationError]
    (inCause.formatted should not).include("hunter2")
  }

  "An error that carries no secret" should "be returned as the backend gave it" in {
    val original = ProcessingError("open", "the plain schema is locked")
    open(SecretBlock, CheckpointerBackends.of(leaky(_ => Left(original)))).left.value shouldBe original
  }
}
