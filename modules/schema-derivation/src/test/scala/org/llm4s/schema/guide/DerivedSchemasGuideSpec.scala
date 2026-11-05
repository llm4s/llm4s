package org.llm4s.schema.guide

// The build compiles with -source:3.3, which turns off named tuple values and field access by name; Scala 3.7's
// default source level, which the guide's readers have, has them.
import scala.language.`3.7`

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }
import java.time.Instant

/**
 * The code of `docs/guide/derived-schemas.md`, compiled and run as written.
 *
 * Every `scala` block of the guide must appear in this file word for word (the first spec below checks it, ignoring
 * whitespace), so a guide that teaches something that no longer compiles fails here. Its `json` block must be the
 * schema that is derived. Change the guide and this spec together.
 */
class DerivedSchemasGuideSpec extends AnyFlatSpec with Matchers {

  // ---- A first call

  object Quickstart {
    import org.llm4s.schema.*
    import upickle.default.ReadWriter

    @description("An invoice extracted from text")
    case class Invoice(
      @description("Name of the vendor or supplier") vendor: String,
      @description("Total invoice amount as a decimal number") amount: Double,
      @description("ISO 4217 currency code, e.g. USD, EUR, GBP") currency: String
    ) derives SchemaOf,
          ReadWriter

    def extract(client: LLMClient, conversation: Conversation): Result[Invoice] = {
      val invoice: Result[Invoice] = client.completeStructuredOf[Invoice](conversation)
      invoice
    }

    def schema(): ujson.Value = {
      val json = SchemaOf[Invoice].toJsonSchema()
      json
    }
  }

  // ---- What is derived

  object Models {
    import org.llm4s.schema.*
    import upickle.default.ReadWriter

    case class Contact(
      name: String,
      email: Option[String],
      tags: List[String] = Nil
    ) derives SchemaOf,
          ReadWriter

    enum Priority derives SchemaOf, ReadWriter {
      case Low, Medium, High
    }
  }

  // ---- Sum types

  object SumTypes {
    import org.llm4s.schema.*
    import upickle.default.ReadWriter

    @description("What the support bot decides to do")
    enum Action derives SchemaOf, ReadWriter {
      @description("Refund the customer") case Refund(amount: BigDecimal, reason: String)
      @description("Hand over to a human team") case Escalate(team: Team)
      case Reply(text: String)
    }

    enum Team derives SchemaOf, ReadWriter {
      case Billing, Shipping
    }

    def decide(client: LLMClient, conversation: Conversation): String =
      client.completeStructuredOf[Action](conversation) match {
        case Right(Action.Refund(amount, _)) => s"refund $amount"
        case Right(Action.Escalate(team))    => s"escalate to $team"
        case Right(Action.Reply(text))       => text
        case Left(error)                     => error.message
      }
  }

  // ---- Named tuples

  object NamedTuples {
    import org.llm4s.schema.*
    import org.llm4s.schema.namedtuples.given
    import org.llm4s.toolapi.ToolFunction
    import upickle.default.ReadWriter

    enum Sentiment derives SchemaOf, ReadWriter {
      case Positive, Neutral, Negative
    }

    def review(client: LLMClient, conversation: Conversation): Result[String] =
      client
        .completeStructuredOf[(sentiment: Sentiment, confidence: Double, quote: Option[String])](conversation)
        .map(r => s"${r.sentiment} (${r.confidence})")

    val lookup: Result[ToolFunction[(city: String, days: Int), (high: Double, low: Double)]] =
      Tool.of[(city: String, days: Int), (high: Double, low: Double)]("forecast", "Daily forecast") { query =>
        Right((high = 20.0 + query.days, low = 12.5))
      }
  }

  // ---- Tool parameters

  object Tools {
    import org.llm4s.schema.*
    import org.llm4s.toolapi.{ ToolBuilder, ToolFunction }
    import org.llm4s.types.Result
    import upickle.default.{ read, ReadWriter }

    import scala.util.Try

    @description("Where to look up the weather")
    case class WeatherQuery(
      @description("City name, for example Paris") city: String,
      @description("Temperature unit; the default is Celsius") unit: Option[TemperatureUnit]
    ) derives SchemaOf,
          ReadWriter

    enum TemperatureUnit derives SchemaOf, ReadWriter {
      case Celsius, Fahrenheit
    }

    val weatherTool: Result[ToolFunction[WeatherQuery, String]] =
      ToolBuilder[WeatherQuery, String]("get_weather", "Current weather in a city", SchemaOf[WeatherQuery].definition)
        .withHandler { args =>
          Try(read[WeatherQuery](args.params)).toEither.left.map(_.getMessage).map(query => s"Sunny in ${query.city}")
        }
        .buildSafe()
  }

  // ---- Typed tools

  object TypedTools {
    import org.llm4s.schema.*
    import org.llm4s.toolapi.ToolFunction
    import org.llm4s.types.Result
    import upickle.default.ReadWriter

    @description("A forecast request")
    case class ForecastQuery(
      @description("City name, for example Paris") city: String,
      @description("How many days ahead, from 1 to 7") days: Int
    ) derives SchemaOf,
          ReadWriter

    case class Forecast(city: String, days: Int, summary: String) derives ReadWriter

    val forecastTool: Result[ToolFunction[ForecastQuery, Forecast]] =
      Tool.of[ForecastQuery, Forecast]("get_forecast", "Weather forecast for a city") { query =>
        if (query.days < 1 || query.days > 7) Left("days must be between 1 and 7")
        else Right(Forecast(query.city, query.days, "Sunny"))
      }
  }

  // ---- Scoped tools

  object ScopedTools {
    import org.llm4s.schema.*
    import org.llm4s.toolapi.ToolFunction
    import org.llm4s.types.Result
    import upickle.default.ReadWriter

    @description("A charge to a customer's saved card")
    case class Charge(
      @description("The customer to charge") customerId: String,
      @description("Amount in cents, above zero") amountCents: Long,
      @description("ISO 4217 code: EUR or USD") currency: String
    ) derives SchemaOf,
          ReadWriter

    case class Receipt(chargeId: String) derives ReadWriter

    case class Customer(id: String, blocked: Boolean)

    trait Customers { def find(id: String): Result[Customer]                                                    }
    trait Payments  { def charge(customer: Customer, amountCents: Long, idempotencyKey: String): Result[String] }

    def chargeTool(customers: Customers, payments: Payments): Result[ToolFunction[Charge, Receipt]] =
      Tool.scoped[Charge, Receipt]("charge_card", "Charge a customer's saved card") { c =>
        ensure(c.amountCents > 0, "amountCents must be positive")
        ensure(Set("EUR", "USD")(c.currency), s"unsupported currency ${c.currency}")
        val customer = customers.find(c.customerId).?
        if (customer.blocked) reject("this customer cannot be charged")
        Receipt(payments.charge(customer, c.amountCents, ToolScope.idempotencyKey).?)
      }
  }

  // ---- An agent's answer

  object Agents {
    import Quickstart.Invoice

    import org.llm4s.agent.Agent
    import org.llm4s.schema.*

    def extractWithAgent(client: LLMClient, email: String): Result[Invoice] =
      for {
        agent   <- Agent.builder("extractor", client).withOutput(SchemaOf[Invoice].definition).build()
        result  <- agent.run(s"Extract the invoice from this email: $email")
        invoice <- result.decodeOutput[Invoice]
      } yield invoice
  }

  // ---- Types the module does not know

  object Meetings {
    import org.llm4s.schema.*
    import upickle.default.ReadWriter

    import java.time.Instant

    given ReadWriter[Instant] = upickle.default.readwriter[String].bimap[Instant](_.toString, Instant.parse)
    given SchemaOf[Instant]   = SchemaOf.string("ISO-8601 instant, for example 2026-10-08T09:30:00Z")

    case class Meeting(title: String, starts: Instant) derives SchemaOf, ReadWriter
  }

  // ---- Recursion

  object Recursion {
    import org.llm4s.schema.*

    case class P(q: Option[Q])
    case class Q(p: List[P])
    object Schemas {
      given ps: SchemaOf[P] = SchemaOf.derived[P] // compiles: Q's given is hand-written
      given qs: SchemaOf[Q] = SchemaOf.derived[Q] // compiles: P's given is hand-written
    }
  }

  // ---- The guide, read from the repository

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("docs/guide/derived-schemas.md")))
      .getOrElse(fail("docs/guide/derived-schemas.md was not found in this directory or above it"))

  // The guide without its "Iron refinements" section, whose code needs Iron: `IronGuideSpec` in llm4s-schema-iron
  // compiles and checks that section the same way.
  private val guide = {
    val full  = Files.readString(root.resolve("docs/guide/derived-schemas.md"))
    val start = full.indexOf("\n## Iron refinements\n")
    val end   = full.indexOf("\n## ", start + 1)
    if (start < 0 || end < 0) fail("docs/guide/derived-schemas.md has no \"Iron refinements\" section before another")
    full.substring(0, start) + full.substring(end)
  }
  private val thisSource =
    Files.readString(
      root.resolve("modules/schema-derivation/src/test/scala/org/llm4s/schema/guide/DerivedSchemasGuideSpec.scala")
    )

  private def blocks(language: String): List[String] =
    ("(?s)```" + language + "\n(.*?)```").r.findAllMatchIn(guide).map(_.group(1)).toList

  private def squash(text: String): String = text.replaceAll("\\s+", " ").trim

  private class Scripted(reply: String) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(Completion("id", Instant.EPOCH, reply, "test-model", AssistantMessage(reply)))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "The guide's Scala blocks" should "all appear in this spec word for word" in {
    val scalaBlocks = blocks("scala")
    scalaBlocks should have size 12
    scalaBlocks.foreach(block =>
      withClue(s"This block of the guide is not in the code the spec compiles:\n$block\n") {
        squash(thisSource) should include(squash(block))
      }
    )
  }

  it should "be matched by an sbt block that names the artifact the build publishes" in {
    blocks("sbt").map(squash) shouldBe List(
      """libraryDependencies += "org.llm4s" %% "llm4s-schema-derivation" % "{{ site.data.project.latest_release }}""""
    )
  }

  "The first call" should "send the derived schema and read the answer" in {
    import Quickstart.*
    val reply = """{"vendor":"Acme","amount":1250.0,"currency":"GBP"}"""
    extract(new Scripted(reply), Conversation(Seq(UserMessage("Extract the invoice.")))) shouldBe
      Right(Invoice("Acme", 1250.0, "GBP"))
  }

  it should "show the schema the guide prints" in {
    val printed = blocks("json")
    printed should have size 1
    ujson.write(Quickstart.schema()) shouldBe ujson.write(ujson.read(printed.head))
  }

  "The models of the guide" should "describe an Option as nullable and an enum as its case names" in {
    val contact = ujson.read(org.llm4s.schema.SchemaOf[Models.Contact].toJsonSchema().render())
    contact("properties")("email")("type") shouldBe ujson.Arr("string", "null")
    contact("properties")("tags")("type") shouldBe ujson.Str("array")
    org.llm4s.schema.SchemaOf[Models.Priority].toJsonSchema()("enum") shouldBe ujson.Arr("Low", "Medium", "High")
  }

  "The sum type of the guide" should "be an anyOf of its cases, sent wrapped, with each reply read into its case" in {
    import SumTypes.*
    val schema = org.llm4s.schema.SchemaOf[Action].toJsonSchema()
    schema("description") shouldBe ujson.Str("What the support bot decides to do")
    schema("anyOf").arr.map(_("properties")("$type")("enum")) shouldBe
      List(ujson.Arr("Refund"), ujson.Arr("Escalate"), ujson.Arr("Reply"))
    schema("anyOf")(0)("description") shouldBe ujson.Str("Refund the customer")
    val conversation = Conversation(Seq(UserMessage("My parcel arrived broken.")))
    decide(new Scripted("""{"value":{"$type":"Refund","amount":"25.50","reason":"broken"}}"""), conversation) shouldBe
      "refund 25.50"
    decide(new Scripted("""{"value":{"$type":"Escalate","team":"Shipping"}}"""), conversation) shouldBe
      "escalate to Shipping"
    decide(new Scripted("""{"value":{"$type":"Reply","text":"Sorry!"}}"""), conversation) shouldBe "Sorry!"
    decide(new Scripted("""{"value":{"$type":"Shout"}}"""), conversation) should include("does not match")
  }

  "The named tuples of the guide" should "read the reply, and answer a tool call, with no case class" in {
    import NamedTuples.*
    val conversation = Conversation(Seq(UserMessage("Great product, would buy again.")))
    review(new Scripted("""{"sentiment":"Positive","confidence":0.9,"quote":null}"""), conversation) shouldBe
      Right("Positive (0.9)")
    val tool = lookup.fold(e => fail(e.formatted), identity)
    tool.toOpenAITool()("function")("parameters")("required") shouldBe ujson.Arr("city", "days")
    tool.execute(ujson.Obj("city" -> "Paris", "days" -> 2)) shouldBe Right(ujson.Obj("high" -> 22.0, "low" -> 12.5))
    tool.execute(ujson.Obj("city" -> "Paris")).isLeft shouldBe true
  }

  "The tool of the guide" should "send the derived schema as its parameters, and read a call with it" in {
    val tool = Tools.weatherTool.fold(e => fail(e.toString), identity)
    tool.toOpenAITool()("function")("parameters") shouldBe org.llm4s.schema.SchemaOf[Tools.WeatherQuery].toJsonSchema()
    val parameters = tool.toOpenAITool()("function")("parameters")
    parameters("required") shouldBe ujson.Arr("city", "unit")
    parameters("properties")("unit")("enum") shouldBe ujson.Arr("Celsius", "Fahrenheit", ujson.Null)
    tool.execute(ujson.Obj("city" -> "Paris", "unit" -> ujson.Null)) shouldBe Right(ujson.Str("Sunny in Paris"))
    tool.execute(ujson.Obj("city" -> "Oslo", "unit" -> "Fahrenheit")) shouldBe Right(ujson.Str("Sunny in Oslo"))
    tool.execute(ujson.Obj("unit" -> "Kelvin")).isLeft shouldBe true
  }

  "The typed tool of the guide" should "decode a call, run the function and report the errors the table lists" in {
    import org.llm4s.toolapi.{ ToolCallError, ToolParameterError }
    import ToolParameterError.*
    val tool = TypedTools.forecastTool.fold(e => fail(e.toString), identity)
    tool.toOpenAITool()("function")("parameters") shouldBe
      org.llm4s.schema.SchemaOf[TypedTools.ForecastQuery].toJsonSchema()

    def error(e: ToolParameterError) = Left(ToolCallError.HandlerError("get_forecast", e.getMessage))
    tool.execute(ujson.Obj("city" -> "Paris", "days" -> 3, "extra" -> true)) shouldBe
      Right(ujson.Obj("city" -> "Paris", "days" -> 3, "summary" -> "Sunny"))
    tool.execute(ujson.Obj("city" -> "Paris", "days" -> 9)) shouldBe
      Left(ToolCallError.HandlerError("get_forecast", "days must be between 1 and 7"))
    tool.execute(ujson.Obj("city" -> "Paris")) shouldBe error(MissingParameter("days", "integer", List("city")))
    tool.execute(ujson.Obj("city" -> "Paris", "days" -> ujson.Null)) shouldBe error(NullParameter("days", "integer"))
    for (days <- List(ujson.Str("three"), ujson.Num(2.5), ujson.Num(3e9)))
      tool.execute(ujson.Obj("city" -> "Paris", "days" -> days)) shouldBe
        error(TypeMismatch("days", "integer", if (days.strOpt.isDefined) "string" else "number"))
    tool.execute(ujson.Str("Paris")) shouldBe Left(
      ToolCallError.HandlerError(
        "get_forecast",
        MultipleErrors(
          List(InvalidNesting("city", "root", "string"), InvalidNesting("days", "root", "string"))
        ).getMessage
      )
    )
  }

  "The agent of the guide" should "answer with the derived type" in {
    import Quickstart.*
    val reply = """{"vendor":"Acme","amount":1250.0,"currency":"GBP"}"""
    Agents.extractWithAgent(new Scripted(reply), "Acme bills GBP 1250") shouldBe Right(Invoice("Acme", 1250.0, "GBP"))
  }

  "A type the module does not know" should "be described by the given the guide defines, and round-trip" in {
    import Meetings.*
    val schema = org.llm4s.schema.SchemaOf[Meeting].toJsonSchema()
    schema("properties")("starts")("type") shouldBe ujson.Str("string")
    schema("properties")("starts")("description").str should startWith("ISO-8601 instant")
    val meeting = Meeting("Standup", java.time.Instant.parse("2026-10-08T09:30:00Z"))
    upickle.default.read[Meeting](upickle.default.write(meeting)) shouldBe meeting
  }

  "The scoped tool of the guide" should "pass the call's idempotency key on and end the call on each check" in {
    import ScopedTools.*
    import org.llm4s.error.NotFoundError
    import org.llm4s.toolapi.{ ToolCallError, ToolCallInfo }

    val charged = scala.collection.mutable.ListBuffer.empty[(String, Long, String)]
    val customers = new Customers {
      def find(id: String): Result[Customer] = id match {
        case "c-1" => Right(Customer("c-1", blocked = false))
        case "c-2" => Right(Customer("c-2", blocked = true))
        case other => Left(NotFoundError(s"no customer $other", "customer"))
      }
    }
    val payments = new Payments {
      def charge(customer: Customer, amountCents: Long, idempotencyKey: String): Result[String] = {
        charged += ((customer.id, amountCents, idempotencyKey))
        Right(s"ch-${charged.size}")
      }
    }
    val tool = chargeTool(customers, payments).fold(e => fail(e.toString), identity)
    def call(id: String, cents: Long, currency: String = "EUR") =
      tool.execute(
        ujson.Obj("customerId" -> id, "amountCents" -> ujson.Num(cents.toDouble), "currency" -> currency),
        ToolCallInfo("t-1", "k-1")
      )
    def error(message: String) = Left(ToolCallError.HandlerError("charge_card", message))

    call("c-1", 500) shouldBe Right(ujson.Obj("chargeId" -> "ch-1"))
    charged.toList shouldBe List(("c-1", 500L, "k-1"))
    call("c-1", 0) shouldBe error("amountCents must be positive")
    call("c-1", 500, "GBP") shouldBe error("unsupported currency GBP")
    call("c-2", 500) shouldBe error("this customer cannot be charged")
    call("c-9", 500) shouldBe error(NotFoundError("no customer c-9", "customer").formatted)
    charged should have size 1
  }

  "A recursion through a hand-written given" should "fail on first use with the message the guide quotes, not hang" in {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration.*
    import Recursion.Schemas.given
    val built = scala.concurrent.Future(scala.util.Try(org.llm4s.schema.SchemaOf[Recursion.P].toJsonSchema()))
    val error = scala.concurrent.Await.result(built, 30.seconds).failed.get
    error shouldBe an[IllegalStateException]
    error.getMessage should startWith(
      "recursive type P: llm4s-schema-derivation cannot describe recursive types; core's schema model has no $ref."
    )
  }
}
