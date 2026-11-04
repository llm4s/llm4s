package org.llm4s.schema

// The build compiles with -source:3.3, which turns off named tuple values and field access by name; Scala 3.7's
// default source level has them.
import scala.language.`3.7`

import org.llm4s.agent.Agent
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.schema.Fixtures.*
import org.llm4s.schema.NamedTupleFixtures.*
import org.llm4s.schema.namedtuples.given
import org.llm4s.toolapi.{
  ObjectSchema,
  ToolCallError,
  ToolFunction,
  ToolParameterError,
  ToolRegistry,
  ToolCallRequest
}
import org.llm4s.toolapi.ToolParameterError.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/**
 * Named tuples (#2328) as `completeStructuredOf`'s output, a typed tool's arguments and result, and an agent's
 * answer: each behaves as the case class with the same fields does, including the errors it reports.
 */
class NamedTupleUseSpec extends AnyFlatSpec with Matchers {

  final private class Scripted(replies: String*) extends LLMClient {
    val asked = new CopyOnWriteArrayList[CompletionOptions]()

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val reply = replies(asked.size)
      asked.add(options)
      Right(Completion("id", Instant.EPOCH, reply, "test-model", AssistantMessage(reply)))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private val conversation = Conversation(Seq(UserMessage("How did the reviewer feel?")))

  private def sentSchema(client: Scripted): Option[ujson.Value] =
    client.asked.asScala.headOption.flatMap(_.responseFormat).collect { case ResponseFormat.JsonSchema(s, _, _) => s }

  private def built[A, R](tool: Result[ToolFunction[A, R]]): ToolFunction[A, R] =
    tool.fold(e => fail(e.formatted), identity)

  private val forecast = built(
    Tool.of[ForecastArgs, Forecast]("forecast", "Daily forecast") { q =>
      if (q.days < 1) Left("days must be positive")
      else Right((high = 21.0 + q.days, low = if (q.units.contains("F")) 54.5 else 12.5))
    }
  )

  private val forecastClass = built(
    Tool.of[ForecastArgsClass, Forecast]("forecast", "Daily forecast")(q => Right((high = 21.0 + q.days, low = 12.5)))
  )

  private val parameters      = SchemaOf[ForecastArgs].definition.asInstanceOf[ObjectSchema[ForecastArgs]]
  private val classParameters = SchemaOf[ForecastArgsClass].definition.asInstanceOf[ObjectSchema[ForecastArgsClass]]

  // ---- completeStructuredOf

  "completeStructuredOf" should "send a named tuple's schema and read the reply into it" in {
    val client = new Scripted("""{"sentiment":"Green","confidence":0.92,"quote":"Loved it"}""")
    val review = client.completeStructuredOf[(sentiment: Color, confidence: Double, quote: Option[String])](
      conversation
    )
    review.map(_.sentiment) shouldBe Right(Color.Green)
    review.map(_.confidence) shouldBe Right(0.92)
    review.map(_.quote) shouldBe Right(Some("Loved it"))
    sentSchema(client) shouldBe Some(SchemaOf[Review].toJsonSchema())
  }

  it should "read the same reply as the case class with the same fields, and send the same properties" in {
    val reply   = """{"sentiment":"Red","confidence":0.1,"quote":null}"""
    val tuple   = new Scripted(reply)
    val cls     = new Scripted(reply)
    val asTuple = tuple.completeStructuredOf[Review](conversation)
    val asClass = cls.completeStructuredOf[ReviewClass](conversation)
    asTuple.map(r => ReviewClass(r.sentiment, r.confidence, r.quote)) shouldBe asClass
    sentSchema(tuple).map(_("properties")) shouldBe sentSchema(cls).map(_("properties"))
  }

  it should "read a nested named tuple, and one that holds a sum type" in {
    new Scripted("""{"title":"t","point":{"x":1,"y":2},"points":[],"label":{"text":"l"}}""")
      .completeStructuredOf[Plot](conversation) shouldBe
      Right((title = "t", point = (x = 1, y = 2), points = Nil, label = Some(Tuple1("l"))))

    val client = new Scripted("""{"action":{"$type":"Reply","text":"hi"},"note":"n"}""")
    client.completeStructuredOf[WithSum](conversation) shouldBe
      Right((action = SumFixtures.Action.Reply("hi"), note = "n"))
    sentSchema(client) shouldBe Some(SchemaOf[WithSum].toJsonSchema())
  }

  it should "fail with a ValidationError, as for a case class, when the reply misses or mistypes an element" in {
    for (reply <- List("""{"quote":"x"}""", """{"sentiment":"Red","confidence":"high","quote":null}""", "[1]"))
      withClue(reply) {
        val tuple = new Scripted(reply).completeStructuredOf[Review](conversation)
        val cls   = new Scripted(reply).completeStructuredOf[ReviewClass](conversation)
        tuple.left.map(_.getClass) shouldBe Left(classOf[ValidationError])
        cls.left.map(_.getClass) shouldBe Left(classOf[ValidationError])
      }
    new Scripted("""{"quote":"x"}""").completeStructuredOf[Review](conversation).left.map(_.message) shouldBe
      new Scripted("""{"quote":"x"}""").completeStructuredOf[ReviewClass](conversation).left.map(_.message)
  }

  // ---- Tool.of

  "A tool over named tuples" should "decode its arguments and write its result as a JSON object" in {
    forecast.execute(ujson.Obj("city" -> "Paris", "days" -> 2)) shouldBe
      Right(ujson.Obj("high" -> 23.0, "low" -> 12.5))
    forecast.execute(ujson.Obj("city" -> "Paris", "days" -> 1, "units" -> "F")) shouldBe
      Right(ujson.Obj("high" -> 22.0, "low" -> 54.5))
    new ToolRegistry(Seq(forecast))
      .execute(ToolCallRequest("forecast", ujson.Obj("city" -> "Oslo", "days" -> 3))) shouldBe
      Right(ujson.Obj("high" -> 24.0, "low" -> 12.5))
  }

  it should "send the parameters of the case class with the same fields" in {
    forecast.toOpenAITool()("function")("parameters")("properties") shouldBe
      forecastClass.toOpenAITool()("function")("parameters")("properties")
    forecast.toOpenAITool()("function")("parameters")("required") shouldBe
      forecastClass.toOpenAITool()("function")("parameters")("required")
  }

  it should "report missing, null and mistyped arguments as the case class tool does" in {
    val cases = List(
      ujson.Obj("days" -> 2)                        -> List(MissingParameter("city", "string", List("days"))),
      ujson.Obj("city" -> "Paris", "days" -> "two") -> List(TypeMismatch("days", "integer", "string")),
      ujson.Obj("city" -> ujson.Null, "days" -> 1)  -> List(NullParameter("city", "string")),
      ujson.Obj("city" -> "Paris", "days" -> 1.5)   -> List(TypeMismatch("days", "integer", "number")),
      ujson.Obj("city" -> 3, "days" -> 1, "units" -> false) -> List(
        TypeMismatch("city", "string", "number"),
        TypeMismatch("units", "string", "boolean")
      )
    )
    for ((arguments, errors) <- cases) withClue(arguments.render()) {
      Tool.decode(parameters, arguments) shouldBe Left(errors)
      Tool.decode(classParameters, arguments) shouldBe Left(errors)
      forecast.execute(arguments) shouldBe forecastClass.execute(arguments)
    }
    for (arguments <- List(ujson.Arr(1, 2), ujson.Str("Paris"))) withClue(arguments.render()) {
      Tool.decode(parameters, arguments) shouldBe Tool.decode(classParameters, arguments)
      forecast.execute(arguments) shouldBe forecastClass.execute(arguments)
    }
  }

  it should "read an absent or null Option argument as None, and report the handler's Left" in {
    Tool.decode(parameters, ujson.Obj("city" -> "Paris", "days" -> 1)) shouldBe
      Right((city = "Paris", days = 1, units = None))
    Tool.decode(parameters, ujson.Obj("city" -> "Paris", "days" -> 1, "units" -> ujson.Null)) shouldBe
      Right((city = "Paris", days = 1, units = None))
    forecast.execute(ujson.Obj("city" -> "Paris", "days" -> 0)) shouldBe
      Left(ToolCallError.HandlerError("forecast", "days must be positive"))
  }

  it should "name a nested element its reader refuses, as for a case class" in {
    type Priced = (item: String, price: (amount: BigDecimal, currency: String))
    val priced = SchemaOf[Priced].definition.asInstanceOf[ObjectSchema[Priced]]
    Tool.decode(priced, ujson.read("""{"item":"tea","price":{"amount":"abc","currency":"EUR"}}""")) match {
      case Left(List(TypeMismatch(name, expected, received))) =>
        name shouldBe "price.amount"
        expected shouldBe "string"
        received should startWith("string that cannot be read")
      case other => fail(s"unexpected $other")
    }
    Tool.decode(priced, ujson.read("""{"item":"tea","price":{"amount":"1.50","currency":"EUR"}}""")) shouldBe
      Right((item = "tea", price = (amount = BigDecimal("1.50"), currency = "EUR")))
  }

  it should "report each problem as a ToolParameterError" in {
    Tool.decode(parameters, ujson.Obj()).left.map(_.map(_.getClass)) shouldBe
      Left(List(classOf[MissingParameter], classOf[MissingParameter]))
    Tool.decode(parameters, ujson.Obj()).left.map(_.map(_.isInstanceOf[ToolParameterError])) shouldBe
      Left(List(true, true))
  }

  // ---- an agent's answer

  "An agent's response format" should "be a named tuple's schema, and its answer decode into the named tuple" in {
    val client = new Scripted("""{"sentiment":"Blue","confidence":0.5,"quote":null}""")
    val result =
      Agent.builder("reviewer", client).withOutput(SchemaOf[Review].definition).build().flatMap(_.run("Classify it"))
    result.flatMap(_.decodeOutput[Review]) shouldBe Right((sentiment = Color.Blue, confidence = 0.5, quote = None))
    client.asked.asScala.map(_.responseFormat) shouldBe Seq(
      Some(ResponseFormat.JsonSchema(SchemaOf[Review].toJsonSchema()))
    )
  }
}
