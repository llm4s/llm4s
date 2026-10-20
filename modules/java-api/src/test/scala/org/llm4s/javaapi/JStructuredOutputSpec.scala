package org.llm4s.javaapi

import org.llm4s.javaapi.StructuredRecords.*
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{ List as JList, Optional }
import java.util.concurrent.atomic.AtomicReference

/** `JLlmClient.complete(prompt, Record.class)` (#1486): a Java record's schema out, the record back. */
class JStructuredOutputSpec extends AnyFlatSpec with Matchers {

  /** A client answering `reply`, keeping the options it was called with. */
  final private class Replying(reply: String) extends LLMClient {
    val options = new AtomicReference[CompletionOptions]()
    override def complete(conversation: Conversation, opts: CompletionOptions): Result[Completion] = {
      options.set(opts)
      Right(Completion("id", 0L, reply, "test-model", AssistantMessage(reply)))
    }
    override def streamComplete(
      c: Conversation,
      o: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private val customerJson =
    """{"name":"Ada","age":36,"id":7,"score":9.5,"active":true,"tier":"PRO",
      |"address":{"city":"London","country":"UK"},"tags":["math","code"],
      |"previous":[{"city":"Paris","country":"FR"}],"nickname":null}""".stripMargin

  private def ask[T](reply: String, cls: Class[T]): LlmResult[T] =
    Llm4s.wrapClient(new Replying(reply)).complete("q", cls)

  private def failure(result: LlmResult[?]): String = {
    result.isFailure shouldBe true
    result.getError().getMessage
  }

  "complete(prompt, Record.class)" should "read a nested record with lists, an enum and an empty Optional" in {
    ask(customerJson, classOf[Customer]).get() shouldBe new Customer(
      "Ada",
      36,
      7L,
      9.5,
      true,
      Tier.PRO,
      new Address("London", "UK"),
      JList.of("math", "code"),
      JList.of(new Address("Paris", "FR")),
      Optional.empty()
    )
  }

  it should "read a present Optional, and treat a missing one as empty" in {
    ask(customerJson.replace("\"nickname\":null", "\"nickname\":\"Countess\""), classOf[Customer])
      .get()
      .nickname() shouldBe Optional.of("Countess")
    ask(customerJson.replace(",\"nickname\":null", ""), classOf[Customer]).get().nickname() shouldBe Optional.empty()
  }

  it should "read boxed and narrow numbers" in {
    ask("""{"count":3,"ratio":0.5,"flag":false,"small":12,"f":1.5}""", classOf[Boxed]).get() shouldBe
      new Boxed(3, 0.5, false, 12.toShort, 1.5f)
  }

  it should "read the record out of a fenced reply, as completeStructured does" in {
    ask("```json\n{\"city\":\"Oslo\",\"country\":\"NO\"}\n```", classOf[Address]).get() shouldBe new Address(
      "Oslo",
      "NO"
    )
  }

  it should "send the record's schema: every property required, enums, arrays and a nullable Optional" in {
    val client = new Replying(customerJson)
    Llm4s.wrapClient(client).complete("q", classOf[Customer]).isSuccess shouldBe true
    val schema = client.options.get().responseFormat match {
      case Some(ResponseFormat.JsonSchema(s, _, _)) => s
      case other                                    => fail(s"no JSON schema sent: $other")
    }
    val props = schema("properties").obj
    props.keySet shouldBe Set("name", "age", "id", "score", "active", "tier", "address", "tags", "previous", "nickname")
    schema("required").arr.map(_.str).toSet shouldBe props.keySet
    props("age")("type").str shouldBe "integer"
    props("score")("type").str shouldBe "number"
    props("tier")("enum").arr.map(_.str) shouldBe Seq("FREE", "PRO")
    props("address")("properties").obj.keySet shouldBe Set("city", "country")
    props("tags")("type").str shouldBe "array"
    props("previous")("items")("properties").obj.keySet shouldBe Set("city", "country")
    props("nickname").render() should include("null")
  }

  it should "name a missing field" in {
    failure(ask(customerJson.replace("\"age\":36,", ""), classOf[Customer])) should include("field 'age' is missing")
  }

  it should "name a field of the wrong type, nested and in a list" in {
    failure(ask(customerJson.replace("\"age\":36", "\"age\":\"36\""), classOf[Customer])) should include(
      "field 'age': expected an integer, got a string"
    )
    failure(ask(customerJson.replace("\"city\":\"Paris\"", "\"city\":5"), classOf[Customer])) should include(
      "field 'previous[0].city': expected a string, got a number"
    )
    failure(
      ask(customerJson.replace("\"address\":{", "\"address\":[{").replace("\"UK\"}", "\"UK\"}]"), classOf[Customer])
    ) should
      include("field 'address': expected an object, got an array")
  }

  it should "refuse a null for a field that is not an Optional" in {
    failure(ask(customerJson.replace("\"name\":\"Ada\"", "\"name\":null"), classOf[Customer])) should include(
      "field 'name' is null"
    )
  }

  it should "refuse an unknown enum name, a fraction and an out-of-range integer" in {
    failure(ask(customerJson.replace("\"PRO\"", "\"GOLD\""), classOf[Customer])) should include(
      "field 'tier': 'GOLD' is not one of FREE, PRO"
    )
    failure(ask(customerJson.replace("\"age\":36", "\"age\":36.5"), classOf[Customer])) should include(
      "field 'age': 36.5 is not an integer of type int"
    )
    failure(ask(customerJson.replace("\"age\":36", "\"age\":3000000000"), classOf[Customer])) should include(
      "is not an integer of type int"
    )
  }

  it should "refuse a reply that is not JSON as a structured-output failure" in {
    failure(ask("sorry, no", classOf[Address])) should include("structured_output")
  }

  it should "refuse a type it cannot describe before calling the model" in {
    val client = new Replying("{}")
    val jc     = Llm4s.wrapClient(client)
    failure(jc.complete("q", classOf[WithMap])) should include(
      "values: java.util.Map<java.lang.String, java.lang.String> is not supported"
    )
    failure(jc.complete("q", classOf[WithArray])) should include("is not supported")
    failure(jc.complete("q", classOf[Node])) should include("contains itself")
    failure(jc.complete("q", classOf[String])) should include("java.lang.String is not a record")
    client.options.get() shouldBe null
  }

  it should "fail, not throw, on a null argument" in {
    val jc = Llm4s.wrapClient(new Replying("{}"))
    failure(jc.complete(null: String, classOf[Address])) should include("query")
    failure(jc.complete("q", null: Class[Address])) should include("type")
    failure(jc.complete(null: Conversation, classOf[Address])) should include("conversation")
    failure(
      jc.complete(Conversation(Seq(UserMessage("q"))), null: JCompletionOptions, classOf[Address])
    ) should include(
      "options"
    )
  }

  it should "keep the other options and replace only the response format" in {
    val client  = new Replying("""{"city":"Oslo","country":"NO"}""")
    val options = JCompletionOptions.builder().maxTokens(77).build()
    Llm4s.wrapClient(client).complete(Conversation(Seq(UserMessage("q"))), options, classOf[Address]).get() shouldBe
      new Address("Oslo", "NO")
    client.options.get().maxTokens shouldBe Some(77)
  }

  "The guide's Structured output snippet" should "read a local record and print it" in {
    GuideSnippets.structured(
      Llm4s.wrapClient(new Replying("""{"name":"Tokyo","country":"Japan","population":37000000}"""))
    ) shouldBe "Tokyo"
  }

  "The structured overloads" should "expose no scala.* or ujson.* type to Java" in {
    val methods = classOf[JLlmClient].getMethods.toList.filter(m =>
      m.getName == "complete" && m.getParameterTypes.lastOption.contains(classOf[Class[?]])
    )
    methods should have size 3
    methods.flatMap(m => m.getGenericParameterTypes.toList :+ m.getGenericReturnType).map(_.getTypeName).foreach {
      name =>
        (name should not).startWith("scala."); (name should not).include("ujson.")
    }
  }
}
