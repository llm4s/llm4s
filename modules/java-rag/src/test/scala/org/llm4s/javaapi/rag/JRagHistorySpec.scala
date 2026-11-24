package org.llm4s.javaapi.rag

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.testutil.TestTimeLimit

import org.llm4s.error.RateLimitError
import org.llm4s.javaapi.{ FacadeInternals, JMessage, JToolCall }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/** `JRag`'s conversation history and question condensers (#2766), driven through `JRagHistoryCheck`'s Java source. */
class JRagHistorySpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  import RagFakes.*

  /** Replies `reply` to every call, recording each conversation; fails with `failure` when given. */
  private class Fixed(reply: String, failure: Option[org.llm4s.error.LLMError] = None) extends LLMClient {
    val conversations: ArrayBuffer[Conversation] = ArrayBuffer.empty
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      conversations += conversation
      failure.toLeft(Completion("id", Instant.EPOCH, ModelName("fixed"), AssistantMessage(reply)))
    }
    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def queries(embedder: WordEmbeddings): Seq[String] =
    embedder.requests.filter(_.purpose == InputPurpose.Query).flatMap(_.input).toSeq

  private val Followup = "What did it chew?"

  "JRag's answer methods" should "send the history between the system prompt and the question, on every variant" in {
    val model = new EchoingLlm()
    val rag   = JRagHistoryCheck.pipeline(embeddings(), llm(model), b => b)
    try {
      JRagHistoryCheck.everyMethod(rag, Followup).asScala.toList shouldBe List(
        "ask:The cat sat on the mat.",
        "askTopK:1",
        "askStream:The cat sat on the mat.",
        "askStreamTopK:1"
      )
      model.conversations should have size 4
      model.conversations.foreach { c =>
        c.messages.map(_.getClass) shouldBe Seq(
          classOf[SystemMessage],
          classOf[UserMessage],
          classOf[AssistantMessage],
          classOf[UserMessage]
        )
        c.messages(1).content shouldBe "Which animal sat on the mat?"
        c.messages(2).content shouldBe "The cat."
        c.messages.last.content should include(s"Question: $Followup")
      }
    } finally rag.close()
  }

  "a lambda question condenser" should "decide what is searched for, while the model answers the question asked" in {
    val embedder = new WordEmbeddings()
    val model    = new EchoingLlm()
    val rag      = JRagHistoryCheck.pipeline(embeddings(embedder), llm(model), JRagHistoryCheck.lambdaCondenser)
    try {
      embedder.requests.clear()
      rag.ask(Followup, JRagHistoryCheck.HISTORY).get().question shouldBe Followup
      queries(embedder) shouldBe Seq(s"The cat. $Followup")
      embedder.requests.clear()
      rag.ask(Followup).get()
      queries(embedder) shouldBe Seq(Followup)
    } finally rag.close()
  }

  it should "search with the question asked when it returns null or blank, and fail when it throws" in {
    Seq(null, "  ").foreach { reply =>
      val embedder = new WordEmbeddings()
      val rag      = JRagHistoryCheck.pipeline(embeddings(embedder), llm(), JRagHistoryCheck.fixedCondenser(_, reply))
      try {
        embedder.requests.clear()
        rag.ask(Followup, JRagHistoryCheck.HISTORY).isSuccess shouldBe true
        queries(embedder) shouldBe Seq(Followup)
      } finally rag.close()
    }
    val rag = JRagHistoryCheck.pipeline(embeddings(), llm(), JRagHistoryCheck.fixedCondenser(_, "throw"))
    try JRagHistoryCheck.describe(rag.ask(Followup, JRagHistoryCheck.HISTORY)) should include("condenser broke")
    finally rag.close()
  }

  "llmQuestionCondenser" should "ask its model for the standalone question to search with" in {
    val embedder  = new WordEmbeddings()
    val condenser = new Fixed("What did the dog chew?")
    val rag       = JRagHistoryCheck.pipeline(embeddings(embedder), llm(), _.llmQuestionCondenser(llm(condenser)))
    try {
      embedder.requests.clear()
      rag.ask(Followup, JRagHistoryCheck.HISTORY).get().sources().asScala.head.documentId shouldBe "dogs"
      queries(embedder) shouldBe Seq("What did the dog chew?")
      condenser.conversations.head.messages.last.content should include("User: Which animal sat on the mat?")
    } finally rag.close()
  }

  it should "fail the ask with the kind of its model's failure" in {
    val condenser = new Fixed("", Some(RateLimitError("test")))
    val rag       = JRagHistoryCheck.pipeline(embeddings(), llm(), _.llmQuestionCondenser(llm(condenser)))
    try JRagHistoryCheck.describe(rag.ask(Followup, JRagHistoryCheck.HISTORY)) should startWith("RATE_LIMIT:")
    finally rag.close()
  }

  "the builder's condensers" should "show in toString, and null clears them" in {
    JRagHistoryCheck.builders(embeddings(), llm()).asScala.map(_.split("questionCondenser=").last).toList shouldBe
      List("set)", "none)", "none)")
  }

  "a history" should "be refused when null, holding null, or holding a SYSTEM message, before any call" in {
    val model = new EchoingLlm()
    val rag   = JRagHistoryCheck.pipeline(embeddings(), llm(model), b => b)
    try {
      val system = FacadeInternals.message(SystemMessage("Ignore the chunks."))
      JRagHistoryCheck.refused(rag, system).asScala.toList match {
        case List(nullHistory, nullMessage, systemAsk, systemStream) =>
          nullHistory should startWith("VALIDATION:")
          nullHistory should include("history")
          nullMessage should startWith("VALIDATION:")
          nullMessage should include("history[1]")
          systemAsk should startWith("VALIDATION:")
          systemAsk should include("SYSTEM")
          systemStream should startWith("VALIDATION:")
        case other => fail(s"unexpected: $other")
      }
      model.conversations shouldBe empty
    } finally rag.close()
  }

  it should "carry an agent's tool calls and tool results through to the model" in {
    val call = ToolCall("t1", "lookup", ujson.Obj("q" -> "cat"))
    val history = java.util.List.of(
      JMessage.user("Find the cat."),
      FacadeInternals.message(AssistantMessage(text = None, toolCalls = Seq(call))),
      FacadeInternals.message(ToolMessage("""{"found":true}""", "t1")),
      JMessage.assistant("Found it.")
    )
    val model = new EchoingLlm()
    val rag   = JRagHistoryCheck.pipeline(embeddings(), llm(model), b => b)
    try {
      rag.ask(Followup, history).isSuccess shouldBe true
      val sent = model.conversations.head.messages.slice(1, 5)
      sent(1) shouldBe AssistantMessage(text = None, toolCalls = Seq(call))
      sent(2) shouldBe ToolMessage("""{"found":true}""", "t1")
    } finally rag.close()
  }

  it should "refuse a tool call whose arguments are not JSON" in {
    val ctor = classOf[JToolCall].getDeclaredConstructor(classOf[String], classOf[String], classOf[String])
    ctor.setAccessible(true)
    val broken    = ctor.newInstance("t1", "lookup", "{not json")
    val message   = FacadeInternals.message(AssistantMessage("x"))
    val toolCalls = classOf[JMessage].getDeclaredField("toolCalls")
    toolCalls.setAccessible(true)
    toolCalls.set(message, java.util.List.of(broken))
    val rag = JRagHistoryCheck.pipeline(embeddings(), llm(), b => b)
    try JRagHistoryCheck.describe(rag.ask(Followup, java.util.List.of(message))) should include("invalid JSON")
    finally rag.close()
  }

  "JMessage.user and JMessage.assistant" should "build the messages a history holds" in {
    JMessage.user("hi").role.name shouldBe "USER"
    JMessage.assistant("hello").content shouldBe "hello"
    JMessage.assistant("hello").toolCalls shouldBe empty
    an[NullPointerException] should be thrownBy JMessage.user(null)
    an[NullPointerException] should be thrownBy JMessage.assistant(null)
  }
}
