package org.llm4s.javaapi

import org.llm4s.error.{ AuthenticationError, CancelledError, LLMError, NetworkError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ AnthropicConfig, OllamaConfig, OpenAIConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.MockLLMClients.{ FailingMock, SimpleMock }
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.util.concurrent.CompletionException
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * `docs/guide/java.md`, compiled and run.
 *
 * The Java in the guide is real Java: `HelloLlm4s.java` is the first-call class and `GuideSnippets.java` holds
 * the other blocks, both in `src/test/java`, so sbt compiles them with javac against this module's public API. This
 * spec runs them against clients it supplies and pins what the surrounding prose claims. If a block stops
 * compiling or an assertion fails, the guide is teaching something that no longer works: change the guide and
 * these files together.
 */
class JavaGuideSpec extends AnyWordSpec with Matchers {

  // ---- The guide itself: its blocks are read from docs/guide/java.md, so a block cannot drift from the code.

  private val GuideFile = "docs/guide/java.md"

  private lazy val guide: String = GuideDocs.read(GuideFile, GuideFile)

  private def blocksIn(language: String): List[String] = GuideDocs.blocksIn(guide, language)

  private def squash(text: String): String = GuideDocs.squash(text)

  private lazy val javaSources: String = Seq("HelloLlm4s", "GuideSnippets")
    .map(name => squash(GuideDocs.read(GuideFile, s"modules/java-api/src/test/java/org/llm4s/javaapi/$name.java")))
    .mkString("\n")

  /** What a snippet printed. System.out and System.err are process-wide, so the capture is serialised. */
  final private case class Printed(out: String, err: String)

  /**
   * What a stream received, with Windows line endings read as `\n`, since `println` writes the platform's separator.
   * The console log lines of other suites running at the same time (`HH:mm:ss.SSS [thread] LEVEL ...`) are dropped:
   * the redirect is process-wide, so they land here too.
   */
  private def text(stream: ByteArrayOutputStream): String =
    stream
      .toString("UTF-8")
      .replace("\r\n", "\n")
      .split("(?<=\n)")
      .filterNot(_.matches("(?s)\\d{2}:\\d{2}:\\d{2}\\.\\d{3} \\[.*"))
      .mkString

  private def captured(body: => Unit): Printed = JavaGuideSpec.synchronized {
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val redirect = new AutoCloseable {
      private val (oldOut, oldErr) = (System.out, System.err)
      System.setOut(new PrintStream(out, true, "UTF-8"))
      System.setErr(new PrintStream(err, true, "UTF-8"))
      def close(): Unit = { System.setOut(oldOut); System.setErr(oldErr) }
    }
    Using.resource(redirect)(_ => body)
    Printed(text(out), text(err))
  }

  /** A client that records every conversation it is sent and answers `answer`. */
  private class Recording(answer: String) extends LLMClient {
    val sent: ArrayBuffer[Conversation] = ArrayBuffer.empty
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      sent += c
      Right(Completion("id", 0L, answer, "m", AssistantMessage(answer)))
    }
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def failingWith(error: LLMError): LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Left(error)
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      Left(error)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def client(underlying: LLMClient): JLlmClient = new JLlmClient(underlying)

  "The first-call example (HelloLlm4s)" should {

    "create the default client from application.conf, ask one question and print the answer" in {
      val out    = new ByteArrayOutputStream
      val err    = new ByteArrayOutputStream
      val status = HelloLlm4s.run(new PrintStream(out, true, "UTF-8"), new PrintStream(err, true, "UTF-8"))

      // This module's test classpath resolves the default provider to core's canned fixture provider.
      status shouldBe 0
      text(out) shouldBe "fixture reply\n"
      text(err) shouldBe ""
    }
  }

  "The conversation block" should {

    "send the system message and then the user message, in that order, and print the answer" in {
      val recording = new Recording("Because.")
      val printed   = captured(GuideSnippets.conversation(client(recording)))

      printed shouldBe Printed("Because.\n", "")
      recording.sent.map(_.messages.map(m => (m.role.name, m.content))) shouldBe Seq(
        Seq(("system", "You answer in one short sentence."), ("user", "What is a monad?"))
      )
    }
  }

  "The reading-a-result block" should {

    "give the same value through get, getOrNull, toOptional, map and toCompletableFuture" in {
      GuideSnippets.readingAResult(client(new SimpleMock("4"))).asScala.toList shouldBe
        List("4", "4", "4", "1", "4")
    }

    "throw LlmException from get when the call failed" in {
      val thrown = intercept[LlmException](GuideSnippets.readingAResult(client(new FailingMock("network down"))))
      thrown.getMessage should include("network down")
    }
  }

  "The agent-turn block" should {

    "switch over the status, print the history and usage, and continue the conversation" in {
      var next: JAgentResult = null
      val printed            = captured { next = GuideSnippets.agentTurn(client(new SimpleMock("4"))) }
      printed shouldBe Printed("4\nUSER: What is 2+2?\nASSISTANT: 4\n100 tokens in, 50 out\n", "")
      next.messages.asScala.map(_.content) shouldBe Seq("What is 2+2?", "4", "And 3+3?", "4")
    }
  }

  "The handling-a-failure block" should {

    "print the answer and nothing else when the call succeeds" in {
      captured(GuideSnippets.handlingAFailure(client(new SimpleMock("4")))) shouldBe Printed("4\n", "")
    }

    "print the message, the formatted error and the retry hint for a recoverable error" in {
      val printed = captured(GuideSnippets.handlingAFailure(client(new FailingMock("network down"))))

      printed.out shouldBe ""
      val lines = printed.err.linesIterator.toList
      lines.head should include("network down")
      lines(1) should include("network down")
      lines.last shouldBe "a retry may succeed"
    }

    "not print the retry hint for an error that is not recoverable" in {
      val printed = captured(
        GuideSnippets.handlingAFailure(client(failingWith(AuthenticationError("openai", "bad key", "401"))))
      )

      printed.err should include("bad key")
      (printed.err should not).include("a retry may succeed")
    }
  }

  "A failed future" should {
    "carry the LlmException when a failed result is turned into a CompletableFuture" in {
      val future = client(new FailingMock("down")).complete("hi").toCompletableFuture
      val thrown = intercept[CompletionException](future.join())
      thrown.getCause shouldBe a[LlmException]
    }
  }

  "The Java blocks of the guide" should {

    "each appear word for word in the Java the spec compiles" in {
      val javaBlocks = blocksIn("java")
      javaBlocks should not be empty
      javaBlocks.foreach { block =>
        withClue(s"This block of docs/guide/java.md is not in HelloLlm4s.java or GuideSnippets.java:\n$block\n") {
          javaSources should include(squash(block))
        }
      }
    }

    "still include the blocks the spec runs" in {
      val all = blocksIn("java").mkString("\n")
      Seq(
        "Llm4s.createDefaultClient()",
        "ConversationBuilder.create()",
        "result.toCompletableFuture()",
        "catch (LlmException e)",
        "OllamaConfig.apply(",
        "import org.llm4s.llmconnect.model.Conversation;"
      ).foreach(marker => all should include(marker))
    }
  }

  "The dependency blocks of the guide" should {

    "name llm4s-java-api with the Scala suffix for Maven and Gradle, and the version placeholder everywhere" in {
      val maven  = blocksIn("xml").mkString
      val gradle = blocksIn("kotlin").mkString
      val sbt    = blocksIn("scala").mkString
      maven should include("<artifactId>llm4s-java-api_3</artifactId>")
      gradle should include("org.llm4s:llm4s-java-api_3:")
      sbt should include(""""org.llm4s" %% "llm4s-java-api"""")
      Seq(maven, gradle, sbt).foreach(_ should include("{{ site.data.project.latest_release }}"))
    }
  }

  "LlmResult, as the guide describes it" should {

    val success: LlmResult[String] = LlmResult.success("4")
    val failure: LlmResult[String] = LlmResult.failure[String](ValidationError.required("q"))

    "run ifSuccess on a success only, run ifFailure on a failure only, and return the result so they chain" in {
      val seen = ArrayBuffer.empty[String]

      val afterSuccess = success.ifSuccess(v => seen += s"ok:$v").ifFailure(e => seen += s"err:${e.getMessage}")
      val afterFailure = failure.ifSuccess(v => seen += s"ok:$v").ifFailure(e => seen += s"err:${e.getMessage}")

      seen.toList shouldBe List("ok:4", s"err:${failure.getError().getMessage}")
      (afterSuccess should be).theSameInstanceAs(success)
      (afterFailure should be).theSameInstanceAs(failure)
    }

    "test with isSuccess and isFailure, and give the error back only for a failure" in {
      (success.isSuccess, success.isFailure) shouldBe (true, false)
      (failure.isSuccess, failure.isFailure) shouldBe (false, true)
      success.getError() shouldBe null
      failure.getError() shouldBe a[LlmException]
    }

    "give null from getOrNull and an empty Optional from toOptional for a failure, and the value for a success" in {
      failure.getOrNull() shouldBe null
      failure.toOptional.isPresent shouldBe false
      success.getOrNull() shouldBe "4"
      success.toOptional.get() shouldBe "4"
    }

    "transform a success with map and pass a failure through" in {
      success.map[Int](_.length).get() shouldBe 1
      failure.map[Int](_.length).isFailure shouldBe true
    }

    "make a future that is already complete, successfully or exceptionally" in {
      success.toCompletableFuture.isDone shouldBe true
      success.toCompletableFuture.join() shouldBe "4"
      failure.toCompletableFuture.isCompletedExceptionally shouldBe true
    }
  }

  "Configuring in code" should {

    "build a client for OpenAI, Anthropic and Ollama from the Java entry points, without a request" in {
      val results = GuideSnippets.inCode("sk-test").asScala.toList
      results.map(_.isSuccess) shouldBe List(true, true, true)
      results.foreach(_.get() shouldBe a[JLlmClient])
    }

    "offer the with methods the guide names" in {
      val openai = OpenAIConfig.apply("sk-test", "gpt-4o-mini")
      openai.withContextWindow(1000).contextWindow shouldBe 1000
      openai.withReserveCompletion(10).reserveCompletion shouldBe 10
      openai.withBaseUrl("http://example.test/v1").baseUrl shouldBe "http://example.test/v1"
      openai.withOrganization("org-1").organization shouldBe Some("org-1")
      AnthropicConfig.apply("sk-test", "m").withContextWindow(1000).contextWindow shouldBe 1000
      AnthropicConfig.apply("sk-test", "m").withReserveCompletion(10).reserveCompletion shouldBe 10
      OllamaConfig.apply("m", "http://h").withContextWindow(1000).contextWindow shouldBe 1000
      OllamaConfig.apply("m", "http://h").withReserveCompletion(10).reserveCompletion shouldBe 10
    }

    "fail, not throw, for a null config" in {
      val result = Llm4s.createClient(null)
      result.isFailure shouldBe true
      result.getError().error shouldBe a[ValidationError]
    }
  }

  "JLlmClient" should {

    "return a failed result, not throw, for a null query or a null conversation" in {
      val jClient = client(new SimpleMock("unused"))
      val query   = jClient.complete(null.asInstanceOf[String])
      val convo   = jClient.complete(null.asInstanceOf[Conversation])

      query.isFailure shouldBe true
      query.getError().error shouldBe a[ValidationError]
      query.getError().getMessage should include("query")
      convo.isFailure shouldBe true
      convo.getError().getMessage should include("conversation")
    }

    "close the underlying client when it is closed" in {
      val closes = new java.util.concurrent.atomic.AtomicInteger(0)
      val underlying = new LLMClient {
        override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
          Right(Completion("id", 0L, "ok", "m", AssistantMessage("ok")))
        override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) = complete(c, o)
        override def getContextWindow(): Int                                                         = 4096
        override def getReserveCompletion(): Int                                                     = 512
        override def close(): Unit = { closes.incrementAndGet(); () }
      }

      Using.resource(client(underlying))(_.complete("hi").isSuccess shouldBe true)

      closes.get() shouldBe 1
    }

    "turn an interruption into a CancelledError with the interrupt flag restored, as the guide says (#1591)" in {
      val interrupted = new LLMClient {
        override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
          throw new InterruptedException("cancelled")
        override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) = complete(c, o)
        override def getContextWindow(): Int                                                         = 4096
        override def getReserveCompletion(): Int                                                     = 512
      }
      // on a thread of its own, so the restored flag does not leak into other tests
      val result  = new java.util.concurrent.atomic.AtomicReference[LlmResult[String]]()
      val flagSet = new java.util.concurrent.atomic.AtomicReference[java.lang.Boolean](java.lang.Boolean.FALSE)
      val thread = new Thread(() => {
        result.set(client(interrupted).complete("hi"))
        flagSet.set(java.lang.Boolean.valueOf(Thread.currentThread().isInterrupted))
      })
      thread.setDaemon(true)
      thread.start()
      thread.join(20000L)

      thread.isAlive shouldBe false
      result.get().isFailure shouldBe true
      result.get().getError().error shouldBe a[CancelledError]
      result.get().getError().getCause.getMessage shouldBe "cancelled"
      flagSet.get().booleanValue() shouldBe true
    }
  }

  "ConversationBuilder" should {

    "reject a null message at once with NullPointerException" in {
      intercept[NullPointerException](ConversationBuilder.create().system(null))
      intercept[NullPointerException](ConversationBuilder.create().user(null))
      intercept[NullPointerException](ConversationBuilder.create().assistant(null))
    }

    "be immutable: a builder can be extended in two ways without one affecting the other" in {
      val base = ConversationBuilder.create().system("s")
      val a    = base.user("a").build()
      val b    = base.user("b").build()

      base.build().messages.map(_.content) shouldBe Seq("s")
      a.messages.map(_.content) shouldBe Seq("s", "a")
      b.messages.map(_.content) shouldBe Seq("s", "b")
    }

    "send assistant messages in the order they were added" in {
      val recording = new Recording("ok")
      client(recording).complete(
        ConversationBuilder.create().system("be brief").user("hi").assistant("hello").user("2+2").build()
      )

      recording.sent.head.messages.map(m => (m.role.name, m.content)) shouldBe Seq(
        ("system", "be brief"),
        ("user", "hi"),
        ("assistant", "hello"),
        ("user", "2+2")
      )
    }
  }

  "LlmException" should {

    "expose the underlying Throwable of the error as getCause, and null when there is none" in {
      val cause = new java.io.IOException("socket closed")

      val withCause = LlmResult.failure[String](NetworkError("down", Some(cause), "http://x")).getError()
      val without   = LlmResult.failure[String](ValidationError.required("q")).getError()

      (withCause.getCause should be).theSameInstanceAs(cause)
      withCause.error shouldBe a[NetworkError]
      without.getCause shouldBe null
    }
  }
}

object JavaGuideSpec
