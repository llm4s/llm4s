package org.llm4s.rag

import org.llm4s.error.{ NetworkError, ValidationError }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable

/** The built-in LLM question condenser (#2767). */
class QuestionCondenserSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /** Replies `condensed` to a condense call (recognised by its prompt) and "42" to anything else. */
  private class ScriptedClient(condensed: Result[String] = Right("price of the blue widget")) extends LLMClient {
    val conversations: mutable.Buffer[Conversation] = mutable.Buffer.empty
    val options: mutable.Buffer[CompletionOptions]  = mutable.Buffer.empty
    private def completion(text: String): Completion =
      Completion(
        id = "c",
        created = Instant.EPOCH,
        content = text,
        model = "mock-model",
        message = AssistantMessage(text),
        usage = Some(TokenUsage(4, 3, 7))
      )
    override def complete(conversation: Conversation, opts: CompletionOptions): Result[Completion] = {
      conversations += conversation
      options += opts
      if (conversation.messages.lastOption.exists(_.content.contains("Standalone question:")))
        condensed.map(completion)
      else Right(completion("42"))
    }
    override def streamComplete(
      conversation: Conversation,
      opts: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, opts)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private val history: Seq[Message] = Seq(
    UserMessage("Which widgets do you sell?"),
    AssistantMessage("The red one and the blue one.")
  )

  "QuestionCondenser.llm" should "send the system prompt, then the transcript and the question, and return the reply" in {
    val client = new ScriptedClient(Right("  price of the blue widget \n"))
    QuestionCondenser.llm(client).condense("How much is the second one?", history) shouldBe
      Right("price of the blue widget")
    val Seq(conversation) = client.conversations.toSeq
    conversation.messages.map(_.getClass) shouldBe Seq(classOf[SystemMessage], classOf[UserMessage])
    conversation.messages.head.content shouldBe QuestionCondenser.DefaultSystemPrompt
    conversation.messages.last.content shouldBe
      """Conversation:
        |User: Which widgets do you sell?
        |Assistant: The red one and the blue one.
        |
        |Follow-up question: How much is the second one?
        |
        |Standalone question:""".stripMargin
    client.options.head.temperature shouldBe Some(0.0)
  }

  it should "send only the latest messages with text, leaving out tool messages and tool-call-only replies" in {
    val client = new ScriptedClient
    val long = Seq(
      UserMessage("first"),
      AssistantMessage("one"),
      AssistantMessage(contentOpt = None, toolCalls = Seq(ToolCall("t1", "lookup", ujson.Obj()))),
      ToolMessage("{}", "t1"),
      UserMessage("second"),
      AssistantMessage("two")
    )
    QuestionCondenser.llm(client, maxHistoryMessages = 3).condense("And?", long).isRight shouldBe true
    val transcript = client.conversations.head.messages.last.content
    transcript should include("Assistant: one\nUser: second\nAssistant: two\n")
    (transcript should not).include("first")
    (transcript should not).include("{}")
  }

  it should "use a custom prompt and options" in {
    val client = new ScriptedClient
    val opts   = CompletionOptions(temperature = Some(0.3))
    QuestionCondenser.llm(client, opts, systemPrompt = "Condense.").condense("Q?", history).isRight shouldBe true
    client.conversations.head.messages.head.content shouldBe "Condense."
    client.options.head shouldBe opts
  }

  it should "fall back to the question when the model's reply is blank" in {
    QuestionCondenser.llm(new ScriptedClient(Right("   "))).condense("Q?", history) shouldBe Right("Q?")
  }

  it should "return the client's error unchanged when the call fails" in {
    val down = NetworkError("down", None, "test")
    QuestionCondenser.llm(new ScriptedClient(Left(down))).condense("Q?", history) shouldBe Left(down)
  }

  it should "refuse a history window below 1 without calling the model" in {
    val client = new ScriptedClient
    QuestionCondenser.llm(client, maxHistoryMessages = 0).condense("Q?", history).left.toOption.get shouldBe
      a[ValidationError]
    client.conversations shouldBe empty
  }

  "QuestionCondenser.apply" should "wrap a function, which can fall back to the question on failure" in {
    val failing = QuestionCondenser.llm(new ScriptedClient(Left(NetworkError("down", None, "test"))))
    val lenient = QuestionCondenser((q, h) => failing.condense(q, h).orElse(Right(q)))
    lenient.condense("Q?", history) shouldBe Right("Q?")
    lenient.withTracing(new Recording) shouldBe theSameInstanceAs(lenient)
  }

  private class Recording extends Tracing {
    val events: mutable.Buffer[TraceEvent]          = mutable.Buffer.empty
    def traceEvent(event: TraceEvent): Result[Unit] = { events += event; Right(()) }
    def usages: Seq[String] = events.collect { case e: TraceEvent.TokenUsageRecorded => e.operation }.toSeq
  }

  private object Embeddings extends EmbeddingProvider {
    val inputs: mutable.Buffer[String] = mutable.Buffer.empty
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      inputs ++= request.input
      Right(EmbeddingResponse(embeddings = request.input.map(_ => Seq(0.1, 0.2, 0.3))))
    }
  }

  "A RAG with QuestionCondenser.llm and a tracer" should "search with the condensed question and tag its call rag.condense" in {
    val client = new ScriptedClient
    val tracer = new Recording
    val config =
      RAGConfig.default.withLLM(client).withQuestionCondenser(QuestionCondenser.llm(client)).withTracing(tracer)
    val rag = RAG.buildWithClient(config, new EmbeddingClient(Embeddings)).fold(e => fail(e.message), identity)
    try {
      rag.ingestText("The blue widget costs 42.", "doc1").fold(e => fail(e.message), identity)
      Embeddings.inputs.clear()
      rag.queryWithAnswer("How much is the second one?", history = history).map(_.answer) shouldBe Right("42")
      Embeddings.inputs.toList shouldBe List("price of the blue widget")
      tracer.usages.toList shouldBe List("rag.condense", "rag.answer")
      tracer.events.count(_.isInstanceOf[TraceEvent.CompletionReceived]) shouldBe 2
    } finally rag.close()
  }
}
