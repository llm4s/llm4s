package org.llm4s.capability

import org.llm4s.llmconnect.model.{ CompletionOptions, ContentPart, Conversation, UserMessage }
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.schema.SchemaOf
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI

final case class Caption(text: String, objects: List[String]) derives SchemaOf, upickle.default.ReadWriter

/** Each gated operation sends what the core call it stands for sends, and returns its answer. */
class GatedOperationsSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val image =
    ContentPart.image(MediaSource.Url(URI.create("https://example.com/cat.png")), MediaType.Png).value

  private def tool(name: String): ToolFunction[?, ?] =
    ToolBuilder[Map[String, Any], String](name, s"The $name tool", Schema.`object`[Map[String, Any]]("No parameters"))
      .withHandler(_ => Right(name))
      .buildSafe()
      .value

  "describe" should "send the image and then the question as one user message, through complete" in {
    val gated   = ScriptedLLMClient.always(Reply.text("A cat"))
    val direct  = ScriptedLLMClient.always(Reply.text("A cat"))
    val options = CompletionOptions().withMaxTokens(50)

    val answer = Capable.assume[Vision](gated).describe(image, "What is this?", options).value
    direct.complete(Conversation(Seq(UserMessage(Vector(image, ContentPart.Text("What is this?"))))), options)

    answer.content shouldBe "A cat"
    gated.calls shouldBe direct.calls
    gated.lastCall.value.streamed shouldBe false
  }

  it should "use the default options when none are given" in {
    val gated = ScriptedLLMClient.always(Reply.text("A cat"))
    Capable.assume[Vision & Tools](gated).describe(image, "What is this?").value
    gated.lastCall.value.options shouldBe CompletionOptions()
  }

  "completeWithTools" should "offer the tools, after any the options already have, through complete" in {
    val gated        = ScriptedLLMClient.always(Reply.toolCall("second"))
    val direct       = ScriptedLLMClient.always(Reply.toolCall("second"))
    val (one, two)   = (tool("first"), tool("second"))
    val conversation = Conversation(Seq(UserMessage("Use a tool")))
    val options      = CompletionOptions().withTools(Seq(one))

    val answer = Capable.assume[Tools](gated).completeWithTools(conversation, Seq(two), options).value
    direct.complete(conversation, CompletionOptions().withTools(Seq(one, two)))

    answer.toolCalls.map(_.name) shouldBe List("second")
    gated.calls shouldBe direct.calls
  }

  it should "pass a provider's failure on unchanged" in {
    val failure = org.llm4s.error.ValidationError("model", "refused")
    val gated   = ScriptedLLMClient.always(Reply.failure(failure))
    Capable
      .assume[Tools](gated)
      .completeWithTools(Conversation(Seq(UserMessage("hi"))), Nil)
      .left
      .value shouldBe failure
  }

  "completeStructuredOf" should "send what llm4s-schema-derivation's completeStructuredOf sends, and read the reply" in {
    val reply        = Reply.text("""{"text":"A cat","objects":["cat","mat"]}""")
    val gated        = ScriptedLLMClient.always(reply)
    val direct       = ScriptedLLMClient.always(reply)
    val conversation = Conversation(Seq(UserMessage("Caption the picture")))
    val options      = CompletionOptions().withMaxTokens(50)

    val answer = Capable.assume[StructuredOutput](gated).completeStructuredOf[Caption](conversation, options).value
    org.llm4s.schema.completeStructuredOf(direct)[Caption](conversation, options).value shouldBe answer

    answer shouldBe Caption("A cat", List("cat", "mat"))
    gated.calls shouldBe direct.calls
    gated.lastCall.value.options.responseFormat should not be empty
  }

  it should "use the default options when none are given" in {
    val gated        = ScriptedLLMClient.always(Reply.text("""{"text":"A cat","objects":[]}"""))
    val direct       = ScriptedLLMClient.always(Reply.text("""{"text":"A cat","objects":[]}"""))
    val conversation = Conversation(Seq(UserMessage("Caption the picture")))
    Capable.assume[StructuredOutput & Vision](gated).completeStructuredOf[Caption](conversation).value
    org.llm4s.schema.completeStructuredOf(direct)[Caption](conversation)
    gated.calls shouldBe direct.calls
  }
}
