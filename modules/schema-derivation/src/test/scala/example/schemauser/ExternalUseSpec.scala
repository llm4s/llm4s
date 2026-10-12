package example.schemauser

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.schema.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/**
 * The derivation used from a package outside `org.llm4s`, as a user's code is. Its macros are internal to the
 * module (`private[schema]`), and `derives SchemaOf` expands them at the user's call site, so this proves that
 * expansion still compiles and runs there.
 */
object ExternalTypes {
  enum Status derives SchemaOf, ReadWriter {
    case Open, Closed
  }

  @description("A ticket")
  case class Ticket(@description("Short title") title: String, status: Status, tags: List[String], due: Option[Int])
      derives SchemaOf,
        ReadWriter

  case class Loop(next: Option[Loop])
}

class ExternalUseSpec extends AnyFlatSpec with Matchers {
  import ExternalTypes.*

  private class Scripted(reply: String) extends LLMClient {
    var sent: Option[CompletionOptions] = None
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      sent = Some(options)
      Right(Completion("id", 0L, reply, "test-model", AssistantMessage(reply)))
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "Deriving a schema outside org.llm4s" should "read the annotations and describe the type" in {
    val schema = SchemaOf[Ticket].toJsonSchema()
    schema("description") shouldBe ujson.Str("A ticket")
    schema("properties")("title")("description") shouldBe ujson.Str("Short title")
    schema("properties")("status")("enum") shouldBe ujson.Arr("Open", "Closed")
    schema("required") shouldBe ujson.Arr("title", "status", "tags", "due")
  }

  it should "send that schema and read the answer through completeStructuredOf" in {
    val client = new Scripted("""{"title":"Fix it","status":"Open","tags":["bug"],"due":null}""")
    client.completeStructuredOf[Ticket](Conversation(Seq(UserMessage("Extract the ticket.")))) shouldBe
      Right(Ticket("Fix it", Status.Open, List("bug"), None))
    client.sent.flatMap(_.responseFormat) shouldBe Some(ResponseFormat.JsonSchema(SchemaOf[Ticket].toJsonSchema()))
  }

  it should "still refuse a recursive type at compile time" in {
    scala.compiletime.testing
      .typeCheckErrors("SchemaOf.derived[ExternalTypes.Loop]")
      .map(_.message)
      .mkString("\n") should include("refers to itself")
  }
}
