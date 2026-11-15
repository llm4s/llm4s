package org.llm4s.samples.guides

import org.apache.pekko.actor.ActorSystem
import org.llm4s.error.{ CancelledError, SimpleError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{ Runtime, Unsafe }

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The programs of `docs/guide/effect-systems.md`, the "pick your stack" page (#2334), compiled and run.
 *
 * The page shows one program - a streamed completion, then a one-tool agent run, with an error branch and a timeout
 * - in plain `Result`, cats-effect, ZIO and Pekko. Each is a region of [[EffectSystemsGuide]] between a
 * `// snippet: <name>` and an `// end snippet` comment, and the last case compares those regions with the page's
 * Scala blocks, word for word, so neither copy can change without the other.
 *
 * Every case runs all four programs against the same script and expects the same string from each: the answer, the
 * provider's error, or the timeout. The timeout cases use a provider call that parks until its thread is
 * interrupted, so they cannot pass by a race; no case sleeps.
 */
class EffectSystemsGuideSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  import EffectSystemsGuide.*

  private given system: ActorSystem = ActorSystem("effect-systems-guide")

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds)
    super.afterAll()
  }

  private val Generous = 30.seconds

  /** Each program's result, by stack, for a fresh client from `client` each time. */
  private def runAll(client: () => LLMClient, timeout: FiniteDuration): Map[String, String] = {
    import cats.effect.unsafe.implicits.global
    Map(
      "plain"       -> Plain.plainProgram(client(), timeout),
      "cats-effect" -> CatsEffect.catsProgram(client(), timeout).unsafeRunSync(),
      "zio" -> Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(Zio.zioProgram(client(), timeout)).getOrThrowFiberFailure()
      },
      "pekko" -> Await.result(Pekko.pekkoProgram(client(), timeout), 60.seconds)
    )
  }

  private def happyScript(): ScriptedLLMClient =
    ScriptedLLMClient
      .sequence(
        Reply.text("Hello from a scripted model"),
        Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
        Reply.text("It is 21 degrees in Oslo: pack light layers.")
      )
      .withChunkSize(4)

  "The pick-your-stack programs" should "stream the reply, run the tool and return the same answer in every stack" in {
    val results = runAll(() => happyScript(), Generous)
    results.values.toSet shouldBe Set("Hello from a scripted model | It is 21 degrees in Oslo: pack light layers.")
  }

  they should "send the tool's result to the model in every stack" in {
    Seq[(String, LLMClient => String)](
      "plain" -> (c => Plain.plainProgram(c, Generous)),
      "cats-effect" -> { c =>
        import cats.effect.unsafe.implicits.global
        CatsEffect.catsProgram(c, Generous).unsafeRunSync()
      },
      "zio" -> { c =>
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(Zio.zioProgram(c, Generous)).getOrThrowFiberFailure())
      },
      "pekko" -> (c => Await.result(Pekko.pekkoProgram(c, Generous), 60.seconds))
    ).foreach { case (stack, program) =>
      val client = happyScript()
      program(client)
      withClue(s"$stack:") {
        client.callCount shouldBe 3
        client.calls.last.conversation.messages.map(_.content).exists(_.contains("21")) shouldBe true
      }
    }
  }

  they should "start the agent turn only after the streamed reply has ended, in every stack" in {
    // Each program's first call is the greeting's stream; it waits up to a second for any other call to begin.
    // A stack that starts the agent turn early (Pekko's concatLazy materializes both sources at once) is caught here.
    Seq[(String, LLMClient => String)](
      "plain" -> (c => Plain.plainProgram(c, Generous)),
      "cats-effect" -> { c =>
        import cats.effect.unsafe.implicits.global
        CatsEffect.catsProgram(c, Generous).unsafeRunSync()
      },
      "zio" -> { c =>
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(Zio.zioProgram(c, Generous)).getOrThrowFiberFailure())
      },
      "pekko" -> (c => Await.result(Pekko.pekkoProgram(c, Generous), 60.seconds))
    ).foreach { case (stack, program) =>
      val client = new Sequenced(happyScript())
      val result = program(client)
      withClue(s"$stack:") {
        client.overlapped.get() shouldBe false
        result shouldBe "Hello from a scripted model | It is 21 degrees in Oslo: pack light layers."
      }
    }
  }

  they should "take the error branch with the provider's message when the streamed completion fails" in {
    val results = runAll(() => ScriptedLLMClient.sequence(Reply.failure(SimpleError("provider refused"))), Generous)
    results.values.toSet shouldBe Set("failed: provider refused")
  }

  they should "take the error branch with the same message in every stack when the agent's model call fails" in {
    val results = runAll(
      () => ScriptedLLMClient.sequence(Reply.text("Hello"), Reply.failure(SimpleError("agent call refused"))),
      Generous
    )
    results.values.toSet should have size 1
    results("plain") should startWith("failed: ")
    results("plain") should include("agent call refused")
  }

  they should "time out in every stack, interrupting the provider call and freeing its thread" in {
    val parked  = Vector.fill(4)(new Parked)
    val next    = Iterator.from(0)
    val results = runAll(() => parked(next.next()), 200.millis)
    results.values.toSet shouldBe Set("timed out")
    parked.foreach { call =>
      call.started.await(60, TimeUnit.SECONDS) shouldBe true
      withClue("the timed-out provider call never returned:")(call.ended.await(60, TimeUnit.SECONDS) shouldBe true)
      call.sawInterrupt.get() shouldBe true
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The page
  // ---------------------------------------------------------------------------------------------------------------

  private lazy val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private lazy val guideSource = read(
    root.resolve("modules/samples/src/test/scala/org/llm4s/samples/guides/EffectSystemsGuide.scala")
  )
  private lazy val page = read(root.resolve("docs/guide/effect-systems.md"))

  /** Removes the common indentation and the blank lines around a block. */
  private def normalise(block: String): String = {
    val lines  = block.split("\n", -1).toList.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    val indent = lines.filter(_.trim.nonEmpty).map(l => l.length - l.stripLeading().length).minOption.getOrElse(0)
    lines.map(l => if (l.length >= indent) l.substring(indent) else l.trim).mkString("\n")
  }

  private lazy val regions: List[String] =
    """(?ms)^[ ]*// snippet: ([a-z-]+)\n(.*?)^[ ]*// end snippet$""".r
      .findAllMatchIn(guideSource)
      .map(m => normalise(m.group(2)))
      .toList

  private lazy val pageBlocks: List[String] =
    """(?ms)^```scala\n(.*?)^```$""".r.findAllMatchIn(page).map(m => normalise(m.group(1))).toList

  "The effect-systems guide" should "show every program of this spec, in the same order, word for word" in {
    regions should have size 5
    pageBlocks shouldBe regions
  }

  it should "link the shared conformance contract its semantics table relies on" in {
    val contract = "modules/llm4s-effect/src/test/contract/org/llm4s/effect/contract/StreamingBridgeContract.scala"
    page should include(contract)
    Files.exists(root.resolve(contract)) shouldBe true
  }

  it should "be linked from the User Guide's Effect Systems section" in {
    val index   = read(root.resolve("docs/guide/index.md"))
    val section = index.split("### ").find(_.startsWith("Effect Systems")).getOrElse("")
    section should include("(effect-systems)")
  }
}

/**
 * A provider call that parks until its thread is interrupted, as llm4s providers are cancelled, then returns
 * `Left(CancelledError)`. `started` opens when the call begins, `ended` once it has returned.
 */
final private class Parked extends LLMClient {
  val started: CountDownLatch     = new CountDownLatch(1)
  val ended: CountDownLatch       = new CountDownLatch(1)
  val sawInterrupt: AtomicBoolean = new AtomicBoolean(false)

  private def park(): Result[Completion] = {
    started.countDown()
    val interrupted = CancelledError.catchInterrupt(new CountDownLatch(1).await(120, TimeUnit.SECONDS)).isLeft
    sawInterrupt.set(interrupted)
    ended.countDown()
    Left(CancelledError("parked provider call"))
  }

  def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = park()
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = park()
  def getContextWindow(): Int     = 8192
  def getReserveCompletion(): Int = 512
}

/**
 * Forwards to `underlying`, holding the first call open for up to a second until another call begins. `overlapped`
 * is set when one does: the program started its second call before its first had returned.
 */
final private class Sequenced(underlying: LLMClient) extends LLMClient {
  val overlapped: AtomicBoolean       = new AtomicBoolean(false)
  private val first: AtomicBoolean    = new AtomicBoolean(true)
  private val inFirst: AtomicBoolean  = new AtomicBoolean(false)
  private val another: CountDownLatch = new CountDownLatch(1)

  private def call(body: => Result[Completion]): Result[Completion] =
    if (first.getAndSet(false)) {
      inFirst.set(true)
      another.await(1, TimeUnit.SECONDS)
      val result = body
      inFirst.set(false)
      result
    } else {
      if (inFirst.get()) overlapped.set(true)
      another.countDown()
      body
    }

  def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    call(underlying.complete(conversation, options))
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = call(underlying.streamComplete(conversation, options, onChunk))
  def getContextWindow(): Int     = underlying.getContextWindow()
  def getReserveCompletion(): Int = underlying.getReserveCompletion()
}
