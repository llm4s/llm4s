package org.llm4s.types.typeclass

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, Conversation, UserMessage }

/**
 * Tests for typeclass implementations in org.llm4s.types.typeclass package.
 */
class TypeclassSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Encoder Tests
  // ==========================================================================

  "Encoder.apply" should "summon implicit encoder" in {
    implicit val intEncoder: Encoder[Int] = (a: Int) => ujson.Num(a)

    val encoder = Encoder[Int]
    encoder.encode(42) shouldBe ujson.Num(42)
  }

  "Encoder.encode" should "encode using implicit encoder" in {
    implicit val stringEncoder: Encoder[String] = (s: String) => ujson.Str(s)

    val result = Encoder.encode("hello")
    result shouldBe ujson.Str("hello")
  }

  "Encoder.fromFunction" should "create encoder from function" in {
    val encoder = Encoder.fromFunction[Int](n => ujson.Num(n * 2))

    encoder.encode(21) shouldBe ujson.Num(42)
  }

  "Encoder.EncoderOps" should "provide encode extension method" in {
    implicit val boolEncoder: Encoder[Boolean] = (b: Boolean) => ujson.Bool(b)

    import Encoder._
    val result = true.encode
    result shouldBe ujson.Bool(true)
  }

  // ==========================================================================
  // LLMCapable - String Instance Tests
  // ==========================================================================

  "LLMCapable[String]" should "convert string to conversation" in {
    val result = LLMCapable.stringLLMCapable.toConversation("Hello")

    val conv = result.getOrElse(fail("Expected successful conversion"))
    conv.messages should have size 1
    conv.messages.head shouldBe a[UserMessage]
    conv.messages.head.content shouldBe "Hello"
  }

  it should "extract string from completion" in {
    val completion = Completion(
      id = "test-id",
      created = System.currentTimeMillis(),
      content = "Response text",
      model = "test-model",
      message = AssistantMessage("Response text"),
      toolCalls = List.empty,
      usage = None
    )

    val result = LLMCapable.stringLLMCapable.fromCompletion(completion)
    result.getOrElse(fail("Expected successful extraction")) shouldBe "Response text"
  }

  it should "validate non-empty strings" in {
    val result = LLMCapable.stringLLMCapable.validate("Hello")
    result.isRight shouldBe true
  }

  it should "reject empty strings" in {
    val result = LLMCapable.stringLLMCapable.validate("   ")
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // LLMCapable - Conversation Instance Tests
  // ==========================================================================

  "LLMCapable[Conversation]" should "pass through conversation" in {
    val conv   = Conversation(Seq(UserMessage("Test")))
    val result = LLMCapable.conversationLLMCapable.toConversation(conv)

    result.getOrElse(fail("Expected successful passthrough")) shouldBe conv
  }

  it should "extract conversation from completion" in {
    val completion = Completion(
      id = "test-id",
      created = System.currentTimeMillis(),
      content = "Response",
      model = "test-model",
      message = AssistantMessage("Response"),
      toolCalls = List.empty,
      usage = None
    )

    val result = LLMCapable.conversationLLMCapable.fromCompletion(completion)
    val conv   = result.getOrElse(fail("Expected successful extraction"))
    conv.messages should have size 1
    conv.messages.head shouldBe a[AssistantMessage]
  }

  it should "validate non-empty conversations" in {
    val conv   = Conversation(Seq(UserMessage("Test")))
    val result = LLMCapable.conversationLLMCapable.validate(conv)
    result.isRight shouldBe true
  }

  it should "reject empty conversations" in {
    val conv   = Conversation(Seq.empty)
    val result = LLMCapable.conversationLLMCapable.validate(conv)
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // LLMCapable - Extension Methods Tests
  // ==========================================================================

  "LLMCapableOps.toLLMConversation" should "use implicit instance" in {
    import LLMCapable._

    val result = "Hello world".toLLMConversation
    val conv   = result.getOrElse(fail("Expected successful conversion"))
    conv.messages.head.content shouldBe "Hello world"
  }

  "LLMCapableOps.validateForLLM" should "use implicit instance" in {
    import LLMCapable._

    "Valid".validateForLLM.isRight shouldBe true
    "   ".validateForLLM.isLeft shouldBe true
  }

  // ==========================================================================
  // Custom LLMCapable Instance Tests
  // ==========================================================================

  case class Question(text: String, context: Option[String] = None)

  "Custom LLMCapable instance" should "work with case classes" in {
    implicit val questionCapable: LLMCapable[Question] = new LLMCapable[Question] {
      def toConversation(q: Question): org.llm4s.types.Result[Conversation] = {
        val prompt = q.context match {
          case Some(ctx) => s"Context: $ctx\n\nQuestion: ${q.text}"
          case None      => q.text
        }
        Conversation.create(UserMessage(prompt))
      }

      def fromCompletion(completion: Completion): org.llm4s.types.Result[Question] =
        org.llm4s.Result.success(Question(completion.content))

      def validate(q: Question): org.llm4s.types.Result[Unit] =
        org.llm4s.Result.fromBoolean(
          q.text.trim.nonEmpty,
          org.llm4s.error.ValidationError("question", "text cannot be empty")
        )
    }

    import LLMCapable._

    val q      = Question("What is Scala?", Some("Programming languages"))
    val result = q.toLLMConversation

    val conv = result.getOrElse(fail("Expected successful conversion"))
    conv.messages.head.content should include("Context:")
    conv.messages.head.content should include("What is Scala?")
  }
}
