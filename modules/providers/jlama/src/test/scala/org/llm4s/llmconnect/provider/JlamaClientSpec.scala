package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ConfigurationError, ProcessingError, UnsupportedContentPartError, ValidationError }
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import scala.collection.mutable

/** How [[JlamaClient]] maps a conversation to the engine and the engine's output back, over stubbed engines. */
class JlamaClientSpec extends AnyWordSpec with Matchers:

  import JlamaTestEngines.*

  private val hello = Conversation(Seq(UserMessage("Hello")))

  "complete" should {

    "send system, user and assistant turns in order, and return the reply with its token usage" in {
      val engine = new ScriptedEngine(tokens = Seq("Hi", "!"), promptTokens = 11, generatedTokens = 3)
      val conversation = Conversation(
        Seq(SystemMessage("Be brief."), UserMessage("Hello"), AssistantMessage("Hi."), UserMessage("Again"))
      )
      val completion = client(engine).complete(conversation).fold(e => fail(e.message), identity)

      engine.requests.map(_.turns) shouldBe Seq(
        Seq(
          JlamaTurn(JlamaRole.System, "Be brief."),
          JlamaTurn(JlamaRole.User, "Hello"),
          JlamaTurn(JlamaRole.Assistant, "Hi."),
          JlamaTurn(JlamaRole.User, "Again")
        )
      )
      completion.content shouldBe "Hi!"
      completion.message.content shouldBe "Hi!"
      completion.model shouldBe config.model
      completion.toolCalls shouldBe empty
      completion.usage.map(u => (u.promptTokens, u.completionTokens, u.totalTokens)) shouldBe Some((11, 3, 14))
    }

    "pass the temperature, and maxTokens or else the reserve as the reply's length" in {
      val engine = new ScriptedEngine()
      val local  = client(engine)
      local.complete(hello, CompletionOptions().withTemperature(0.2).withMaxTokens(64)) shouldBe a[Right[?, ?]]
      local.complete(hello) shouldBe a[Right[?, ?]]
      engine.requests.map(_.maxNewTokens) shouldBe Seq(64, config.reserveCompletion)
      engine.requests.head.temperature shouldBe 0.2f
    }

    "refuse tools, tool calls and tool results before loading the model" in {
      val loader = new CountingLoader(Right(new ScriptedEngine()))
      val local  = client(loader)
      val tool = ToolBuilder[Map[String, Any], String]("echo", "Echoes", Schema.`object`[Map[String, Any]]("args"))
        .withHandler(_ => Right("ok"))
        .buildSafe()
        .fold(e => fail(e.toString), identity)
      val call = ToolCall("call-1", "echo", ujson.Obj())

      local.complete(hello, CompletionOptions().withTools(Seq(tool))) match
        case Left(e: ValidationError) => e.message should include("tool calling")
        case other                    => fail(s"expected a ValidationError, got $other")
      local.complete(Conversation(Seq(UserMessage("Hi"), AssistantMessage("", Seq(call))))) shouldBe a[Left[?, ?]]
      local.complete(
        Conversation(Seq(UserMessage("Hi"), AssistantMessage("", Seq(call)), ToolMessage("ok", "call-1")))
      ) shouldBe a[Left[?, ?]]
      loader.loads.get shouldBe 0
    }

    "refuse an image part before loading the model" in {
      val loader = new CountingLoader(Right(new ScriptedEngine()))
      val image = ContentPart
        .image(MediaSource.Url(URI.create("https://example.com/cat.png")), MediaType.Png)
        .fold(e => fail(e.message), identity)
      client(loader).complete(Conversation(Seq(UserMessage(Vector(ContentPart.Text("What is it?"), image))))) match
        case Left(e: UnsupportedContentPartError) => e.message should include("jlama")
        case other                                => fail(s"expected an UnsupportedContentPartError, got $other")
      loader.loads.get shouldBe 0
    }

    "load the model once, on the first call" in {
      val loader = new CountingLoader(Right(new ScriptedEngine()))
      val local  = client(loader)
      loader.loads.get shouldBe 0
      local.complete(hello) shouldBe a[Right[?, ?]]
      local.complete(hello) shouldBe a[Right[?, ?]]
      loader.loads.get shouldBe 1
    }

    "return a failed load, and try again on the next call" in {
      val loader = new CountingLoader(Left(ProcessingError("jlama.load", "no network")))
      val local  = client(loader)
      local.complete(hello).fold(_.message, c => fail(s"expected an error, got $c")) should include("no network")
      local.complete(hello) shouldBe a[Left[?, ?]]
      loader.loads.get shouldBe 2
    }

    "return the engine's exception as a ProcessingError" in {
      client(new FailingEngine("Prompt exceeds max tokens")).complete(hello) match
        case Left(e: ProcessingError) => e.message should include("Prompt exceeds max tokens")
        case other                    => fail(s"expected a ProcessingError, got $other")
    }

    "return an error the engine reports as a ProcessingError" in {
      client(new ScriptedEngine(finish = JlamaFinish.Failed)).complete(hello) shouldBe a[Left[?, ?]]
    }

    "refuse calls once closed, and close the loaded model" in {
      val engine = new ScriptedEngine()
      val local  = client(engine)
      local.complete(hello) shouldBe a[Right[?, ?]]
      local.close()
      engine.closed.get shouldBe true
      local.complete(hello) match
        case Left(_: ConfigurationError) => succeed
        case other                       => fail(s"expected a ConfigurationError, got $other")
    }

    "report jlama as its provider" in {
      client(new ScriptedEngine()).providerId.map(_.asString) shouldBe Some("jlama")
    }
  }

  "streamComplete" should {

    "deliver each token as a chunk, then a chunk with the finish reason, all with the completion's id" in {
      val chunks = mutable.Buffer.empty[StreamedChunk]
      val completion = client(new ScriptedEngine(tokens = Seq("A", "B", "C")))
        .streamComplete(hello, CompletionOptions(), chunks += _)
        .fold(e => fail(e.message), identity)
      chunks.flatMap(_.content) shouldBe Seq("A", "B", "C")
      chunks.last.finishReason shouldBe Some("stop")
      chunks.map(_.id).distinct shouldBe Seq(completion.id)
      completion.content shouldBe "ABC"
    }

    "report a reply cut off by the token limit as length" in {
      val chunks = mutable.Buffer.empty[StreamedChunk]
      client(new ScriptedEngine(finish = JlamaFinish.Length)).streamComplete(hello, CompletionOptions(), chunks += _)
      chunks.last.finishReason shouldBe Some("length")
    }
  }
