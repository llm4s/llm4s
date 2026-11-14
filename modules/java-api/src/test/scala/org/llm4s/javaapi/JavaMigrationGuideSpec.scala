package org.llm4s.javaapi

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.time.Instant
import java.util.concurrent.{ CountDownLatch, Flow, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * `docs/guide/java-migration.md`, compiled and run (#2282).
 *
 * The guide's Java spans four modules, each compiled by javac against its own public API: `MigrationSnippets.java`
 * here, `MigrationRagSnippets.java` in `llm4s-java-rag` (run by `JavaMigrationRagSpec`), the Spring controller
 * `AskController.java` in the starter's `src/test/web` (run by `AskControllerSpec`), and the Reactor block, which is
 * the Reactor guide's own (`ReactorGuideSnippets.java`, run by `ReactorGuideSpec`). This spec checks that every Java
 * block of the guide is word for word in one of them, and runs this module's blocks against `FakeLlmClient` or a
 * client it scripts. The LangChain4j and Spring AI side of the guide is prose with sourced links, not code.
 */
class JavaMigrationGuideSpec extends AnyWordSpec with TestTimeLimit with Matchers {

  private val GuideFile = "docs/guide/java-migration.md"

  private lazy val guide: String = GuideDocs.read(GuideFile, GuideFile)

  private val SourceFiles = Seq(
    "modules/java-api/src/test/java/org/llm4s/javaapi/MigrationSnippets.java",
    "modules/java-rag/src/test/java/org/llm4s/javaapi/rag/MigrationRagSnippets.java",
    "modules/spring-boot-starter/src/test/web/org/llm4s/spring/AskController.java",
    "modules/reactor/src/test/java/org/llm4s/javaapi/reactor/ReactorGuideSnippets.java"
  )

  private lazy val sources: Map[String, String] =
    SourceFiles.map(file => file -> GuideDocs.squash(GuideDocs.read(GuideFile, file))).toMap

  private def captured(body: => Unit): String = JavaGuideSpec.synchronized {
    val out    = new ByteArrayOutputStream
    val oldOut = System.out
    Using.resource(new AutoCloseable {
      System.setOut(new PrintStream(out, true, "UTF-8"))
      def close(): Unit = System.setOut(oldOut)
    })(_ => body)
    out.toString("UTF-8")
  }

  /** A model that asks for `orderStatus` once, then answers with what the tool returned. */
  private class OrderModel extends LLMClient {
    val calls                     = new AtomicInteger(0)
    val sent: ArrayBuffer[String] = ArrayBuffer.empty
    private val call              = ToolCall("c1", "orderStatus", ujson.Obj("orderId" -> "42"))

    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      sent.synchronized(sent += o.tools.map(_.name).mkString(","))
      if (calls.getAndIncrement() == 0)
        Right(
          Completion(
            "1",
            Instant.EPOCH,
            "",
            "m",
            AssistantMessage(contentOpt = None, toolCalls = List(call)),
            toolCalls = List(call)
          )
        )
      else {
        val toolOutput = c.messages.collect { case m: ToolMessage => m.content }.mkString
        Right(Completion("2", Instant.EPOCH, toolOutput, "m", AssistantMessage(toolOutput)))
      }
    }
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  "The guide's Java blocks" should {

    "each be word for word in a source file that javac compiles" in {
      val blocks = GuideDocs.blocksIn(guide, "java")
      blocks.size should be >= 12
      blocks.foreach { block =>
        withClue(s"This block of $GuideFile is in none of ${SourceFiles.mkString(", ")}:\n$block\n") {
          sources.values.exists(_.contains(GuideDocs.squash(block))) shouldBe true
        }
      }
    }

    "use every source file, so none of them is compiled for nothing" in {
      val squashedBlocks = GuideDocs.blocksIn(guide, "java").map(GuideDocs.squash)
      SourceFiles.foreach { file =>
        withClue(s"$file holds no block of $GuideFile: ") {
          squashedBlocks.exists(block => sources(file).contains(block)) shouldBe true
        }
      }
    }

    "link the issues it names as gaps, and be linked from the pages the issue lists" in {
      Seq("2277", "2278").foreach(n => guide should include(s"https://github.com/llm4s/llm4s/issues/$n"))
      Seq("migrating-from-langchain4j", "java", "spring-boot", "comparison").foreach { page =>
        withClue(s"docs/guide/$page.md does not link the Java migration guide: ") {
          GuideDocs.read(GuideFile, s"docs/guide/$page.md") should include("(java-migration")
        }
      }
    }
  }

  "The first-call blocks" should {

    "build a client in code without a request, and one from application.conf" in {
      val clients = MigrationSnippets.firstClient("sk-test").asScala.toList
      clients.foreach(_.isSuccess shouldBe true)
      clients.foreach(_.get().close())
    }

    "ask one question and return the answer" in {
      val fake = FakeLlmClient.replying("Bonjour")
      MigrationSnippets.firstCall(fake.client()) shouldBe "Bonjour"
      fake.prompts().asScala shouldBe Seq("Say hello in French")
    }

    "throw an LlmException when the call fails" in {
      val fake = FakeLlmClient.builder().failWith(LlmErrorKind.AUTHENTICATION, "bad key").build()
      intercept[LlmException](MigrationSnippets.firstCall(fake.client())).getKind shouldBe LlmErrorKind.AUTHENTICATION
    }
  }

  "The structured-output block" should {
    "read the reply into the record" in {
      val fake = FakeLlmClient.replying("""{"customer":"ACME","total":9.5,"items":["bolt","bolt","nut"]}""")
      MigrationSnippets.structured(fake.client()) shouldBe "ACME 9.5 [bolt, bolt, nut]"
    }
  }

  "The tool block" should {
    "offer the annotated tool, run it when the model asks, and answer with its output" in {
      val model = new OrderModel
      MigrationSnippets.tool(Llm4s.wrapClient(model)) should include("Order 42 shipped on Monday")
      model.calls.get() shouldBe 2
      model.sent.head shouldBe "orderStatus"
    }
  }

  "The conversation blocks" should {

    "send the whole history, in order, as one conversation" in {
      val fake = FakeLlmClient.replying("Your name is Ada.")
      MigrationSnippets.history(fake.client(), "Nice to meet you, Ada.") shouldBe "Your name is Ada."
      fake.lastPrompt().orElseThrow() shouldBe "What is my name?"
    }

    "continue the agent's thread, so the second turn sees the first" in {
      val seen = ArrayBuffer.empty[Seq[String]]
      val model = new LLMClient {
        override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
          seen.synchronized(seen += c.messages.collect { case m: UserMessage => m.content }.toSeq)
          Right(Completion("id", Instant.EPOCH, "ok", "m", AssistantMessage("ok")))
        }
        override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) =
          complete(c, o)
        override def getContextWindow(): Int     = 4096
        override def getReserveCompletion(): Int = 512
      }
      val second = MigrationSnippets.thread(Llm4s.wrapClient(model))
      second.answer().orElseThrow() shouldBe "ok"
      seen.last shouldBe Seq("My name is Ada.", "What is my name?")
    }
  }

  "The streaming block" should {
    "print the pieces, return the whole reply, and publish the text to a subscriber" in {
      val received = new java.util.concurrent.CopyOnWriteArrayList[String]()
      val done     = new CountDownLatch(1)
      val subscriber = new Flow.Subscriber[String] {
        def onSubscribe(s: Flow.Subscription): Unit = s.request(Long.MaxValue)
        def onNext(piece: String): Unit             = received.add(piece): Unit
        def onError(e: Throwable): Unit             = done.countDown()
        def onComplete(): Unit                      = done.countDown()
      }
      val fake  = FakeLlmClient.replying("Once upon a time.", "Once upon a time.")
      var reply = null: JCompletion
      captured { reply = MigrationSnippets.streaming(fake.client(), subscriber) } shouldBe "Once upon a time."
      reply.content() shouldBe "Once upon a time."
      done.await(10, TimeUnit.SECONDS) shouldBe true
      received.asScala.mkString shouldBe "Once upon a time."
    }
  }

  "The error-handling blocks" should {

    "return the answer when the call succeeds" in {
      MigrationSnippets.errors(FakeLlmClient.replying("4").client()) shouldBe "4"
      MigrationSnippets.throwing(FakeLlmClient.replying("4").client()) shouldBe "4"
    }

    "branch on the kind of a failure" in {
      def failing(kind: LlmErrorKind) = FakeLlmClient.builder().failWith(kind, "boom").build().client()
      MigrationSnippets.errors(failing(LlmErrorKind.AUTHENTICATION)) should startWith("Fix the setup: ")
      MigrationSnippets.errors(failing(LlmErrorKind.RATE_LIMIT)) shouldBe "Try again later"
      MigrationSnippets.errors(failing(LlmErrorKind.SERVICE)) should (startWith("Retry: ").or(startWith("Failed: ")))
      MigrationSnippets.errors(failing(LlmErrorKind.VALIDATION)) should startWith("Failed: ")
      MigrationSnippets.throwing(failing(LlmErrorKind.RATE_LIMIT)) should startWith("Failed (RATE_LIMIT): ")
    }
  }

  "The FakeLlmClient test block" should {
    "pass" in {
      new MigrationSnippets.GreeterTest().greetsInFrench()
    }
  }

  "The configuration block" should {
    "build a client for each provider from plain values, sending no request" in {
      val clients = MigrationSnippets.providers("sk-openai", "azure-key", "sk-ant").asScala.toList
      clients should have size 4
      clients.foreach { created =>
        withClue(Option(created.getError()).map(_.getMessage).getOrElse(""))(created.isSuccess shouldBe true)
        created.get().close()
      }
    }
  }
}
