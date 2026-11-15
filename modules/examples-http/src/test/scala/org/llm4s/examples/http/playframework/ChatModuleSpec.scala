package org.llm4s.examples.http.playframework

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import play.api.Configuration
import play.api.inject.DefaultApplicationLifecycle
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.running

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.*

/** The Guice wiring: the client from Play's configuration, closed on stop, and the blocking dispatcher. */
class ChatModuleSpec extends AnyFlatSpec with Matchers {

  private def configuration(entries: (String, Any)*): Configuration =
    Configuration.from(Map[String, Any]("examples.chat.fake" -> false) ++ entries)

  "ChatModule.client" should "load the provider section named in the llm4s block of Play's configuration" in {
    val config = configuration(
      "llm4s.providers.provider"       -> "local",
      "llm4s.providers.local.provider" -> "ollama",
      "llm4s.providers.local.model"    -> "llama3:latest",
      "llm4s.providers.local.baseUrl"  -> "http://127.0.0.1:11434"
    )
    val client = ChatModule.client(config)
    client.isRight shouldBe true
    client.foreach(_.close())
  }

  it should "fail with a ConfigurationError when the section it names is not there" in {
    ChatModule.client(configuration("llm4s.providers.provider" -> "absent")) match {
      case Left(error) => error shouldBe a[ConfigurationError]
      case Right(_)    => fail("a client for a section that does not exist")
    }
  }

  it should "serve the fake client when examples.chat.fake is set" in {
    ChatModule.client(Configuration.from(Map("examples.chat.fake" -> true))).isRight shouldBe true
  }

  "The application" should "not start when the provider section cannot be loaded" in {
    val thrown = the[Exception] thrownBy
      GuiceApplicationBuilder().configure("llm4s.providers.provider" -> "absent").build()
    thrown.getMessage should include("absent")
  }

  it should "run POST /chat's wait on the blocking dispatcher, not Play's default one" in {
    val app = GuiceApplicationBuilder().configure("examples.chat.fake" -> true).build()
    running(app) {
      val blocking = app.injector.instanceOf[ChatExecutionContext]
      val thread   = Await.result(Future(Thread.currentThread().getName)(using blocking), 10.seconds)
      thread should include("blocking-dispatcher")
    }
  }

  "ChatModule.closedOnStop" should "close the client when the application stops" in {
    val closed = new AtomicBoolean(false)
    val client = new LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Left(ConfigurationError("unused"))
      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = Left(ConfigurationError("unused"))
      def getContextWindow(): Int     = 4096
      def getReserveCompletion(): Int = 256
      override def close(): Unit      = closed.set(true)
    }
    val lifecycle = new DefaultApplicationLifecycle
    ChatModule.closedOnStop(client, lifecycle) shouldBe client
    closed.get shouldBe false
    Await.result(lifecycle.stop(), 10.seconds)
    closed.get shouldBe true
  }
}
