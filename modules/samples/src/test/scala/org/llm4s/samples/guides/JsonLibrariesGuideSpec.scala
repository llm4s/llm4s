package org.llm4s.samples.guides

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ ObjectSchema, PropertyDefinition, Schema, ToolBuilder }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * Compiles and runs the recipes of `docs/guide/json-libraries.md` against the real circe, play-json and zio-json.
 *
 * Every recipe sits between a `// snippet: <name>` line and an `// end snippet` line, in the order the page shows
 * them. The last tests read this file and the page and fail unless each Scala block of the page is one of those
 * regions, in the same order, so the page cannot drift from code that compiled and ran. The snippets are members
 * and the tests come after them, because Scala's initialisation check refuses a test that runs before a later
 * member exists.
 *
 * The client is a script, not a model: it returns the reply each test hands it, so the checks are on what llm4s
 * does with a reply and never on what a model says.
 */
class JsonLibrariesGuideSpec extends AnyFlatSpec with Matchers with EitherValues {

  /** Answers every request with `reply` and records the requests. */
  private class ScriptedClient(reply: String) extends LLMClient {
    private val seen = new ConcurrentLinkedQueue[CompletionOptions]()

    def optionsSeen: Seq[CompletionOptions] = seen.asScala.toSeq

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      seen.add(options)
      Right(
        Completion(
          id = "script",
          created = 0L,
          content = reply,
          model = "script",
          message = AssistantMessage(reply)
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  // snippet: model
  final case class Address(city: String, zip: Option[String])
  final case class Order(id: Int, total: Double, address: Address, tags: List[String], note: Option[String])

  val addressSchema: ObjectSchema[Address] = Schema
    .`object`[Address]("Where to deliver")
    .withRequiredField("city", Schema.string("The city"))
    .withProperty(PropertyDefinition("zip", Schema.string("The postal code"), required = false))

  val orderSchema: ObjectSchema[Order] = Schema
    .`object`[Order]("An order")
    .withRequiredField("id", Schema.integer("The order number"))
    .withRequiredField("total", Schema.number("The total"))
    .withRequiredField("address", addressSchema)
    .withRequiredField("tags", Schema.array("Labels", Schema.string("A label")))
    .withProperty(PropertyDefinition("note", Schema.string("A note"), required = false))
  // end snippet

  // snippet: circe-conversion
  import io.circe.Json
  import ujson.circe.CirceJson

  def toCirce(value: ujson.Value): Json  = ujson.transform(value, CirceJson)
  def fromCirce(json: Json): ujson.Value = CirceJson.transform(json, ujson.Value)
  // end snippet

  // snippet: circe-codecs
  import io.circe.{ Decoder, Encoder }
  import io.circe.syntax._

  given Decoder[Address] = Decoder.derived
  given Decoder[Order]   = Decoder.derived
  given Encoder[Address] = Encoder.AsObject.derived
  given Encoder[Order]   = Encoder.AsObject.derived
  // end snippet

  // snippet: circe-reader
  import upickle.default.{ reader, Reader }

  def circeReader[A](using decoder: Decoder[A]): Reader[A] =
    reader[ujson.Value].map { value =>
      decoder.decodeJson(toCirce(value)).fold(error => throw upickle.core.Abort(error.getMessage), identity)
    }
  // end snippet

  // snippet: circe-structured
  def orderViaCirce(client: LLMClient, conversation: Conversation): Result[Order] =
    client.completeStructured[Order](conversation, orderSchema)(using circeReader[Order])
  // end snippet

  // snippet: circe-tool
  final case class AddArgs(a: Int, b: Int)
  final case class AddResult(sum: Long)

  given Decoder[AddArgs]   = Decoder.derived
  given Encoder[AddResult] = Encoder.AsObject.derived

  val addSchema = Schema
    .`object`[Map[String, Any]]("Two integers")
    .withRequiredField("a", Schema.integer("The first"))
    .withRequiredField("b", Schema.integer("The second"))

  val addTool = ToolBuilder[Map[String, Any], ujson.Value]("add", "Adds two integers", addSchema)
    .withHandler { extractor =>
      toCirce(extractor.params).as[AddArgs] match {
        case Right(args) => Right(fromCirce(AddResult(args.a.toLong + args.b).asJson))
        case Left(error) => Left(error.getMessage)
      }
    }
    .buildSafe()
  // end snippet

  // snippet: circe-answers
  val answer: ujson.Value = fromCirce(Json.obj("approved" -> Json.fromBoolean(true)))
  // end snippet

  // snippet: play-json
  import play.api.libs.json.{ JsError, JsSuccess, Json as PlayJson, JsValue, Reads }

  def toPlay(value: ujson.Value): JsValue  = PlayJson.parse(value.render())
  def fromPlay(json: JsValue): ujson.Value = ujson.read(PlayJson.stringify(json))

  def playReader[A](using reads: Reads[A]): Reader[A] =
    reader[ujson.Value].map { value =>
      reads.reads(toPlay(value)) match {
        case JsSuccess(decoded, _) => decoded
        case JsError(errors)       => throw upickle.core.Abort(errors.toString)
      }
    }

  given Reads[Address] = PlayJson.reads[Address]
  given Reads[Order]   = PlayJson.reads[Order]
  // end snippet

  // snippet: play-structured
  def orderViaPlay(client: LLMClient, conversation: Conversation): Result[Order] =
    client.completeStructured[Order](conversation, orderSchema)(using playReader[Order])
  // end snippet

  // snippet: zio-json
  import zio.json.{ DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder }
  import zio.json.{ DecoderOps, EncoderOps }

  given JsonDecoder[Address] = DeriveJsonDecoder.gen[Address]
  given JsonDecoder[Order]   = DeriveJsonDecoder.gen[Order]
  given JsonEncoder[Address] = DeriveJsonEncoder.gen[Address]
  given JsonEncoder[Order]   = DeriveJsonEncoder.gen[Order]

  def zioReader[A](using decoder: JsonDecoder[A]): Reader[A] =
    reader[ujson.Value].map { value =>
      value.render().fromJson[A].fold(message => throw upickle.core.Abort(message), identity)
    }

  def fromZio[A](value: A)(using encoder: JsonEncoder[A]): ujson.Value = ujson.read(value.toJson)
  // end snippet

  // snippet: zio-structured
  def orderViaZio(client: LLMClient, conversation: Conversation): Result[Order] =
    client.completeStructured[Order](conversation, orderSchema)(using zioReader[Order])
  // end snippet

  // snippet: numbers
  val big = 9007199254740993L // 2^53 + 1: the first integer a double cannot hold

  val parsed: Long = ujson.read(s"""{"id":$big}""")("id").num.toLong
  // end snippet

  // ---------------------------------------------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------------------------------------------

  private val orderJson =
    """{"id":7,"total":19.5,"address":{"city":"Oslo","zip":null},"tags":["a","b"],"note":null}"""
  private val order = Order(7, 19.5, Address("Oslo", None), List("a", "b"), None)

  private val conversation: Conversation = Conversation.fromPrompts("Extract the order.", "Order 7 ...").value

  private val wrongType = """{"id":"not a number","total":1,"address":{"city":"Oslo"},"tags":[]}"""

  private def idSchema[A](field: String, schema: org.llm4s.toolapi.SchemaDefinition[?]): ObjectSchema[A] =
    Schema.`object`[A]("An id").withRequiredField(field, schema)

  "The circe conversion" should "carry nested objects, arrays, null, numbers and unicode both ways" in {
    val original = ujson.read("""{"a":{"b":[1,2.5,"x",null,true]},"u":"héllo 😀","n":-0.125}""")

    val circe = toCirce(original)
    circe.hcursor.downField("a").downField("b").downArray.as[Int] shouldBe Right(1)
    circe.hcursor.downField("u").as[String] shouldBe Right("héllo 😀")
    circe.hcursor.downField("n").as[Double] shouldBe Right(-0.125)

    fromCirce(circe) shouldBe original
  }

  "A circe Decoder used as a uPickle Reader" should "let completeStructured return the application's own type" in {
    val client = new ScriptedClient(orderJson)

    orderViaCirce(client, conversation).value shouldBe order
    // The schema reaches the provider as a response format, exactly as with a uPickle Reader.
    client.optionsSeen.flatMap(_.responseFormat) should have size 1
  }

  it should "read Option fields given as null or left out, and nested objects" in {
    val reply = """{"id":1,"total":2,"address":{"city":"Bergen","zip":"5003"},"tags":[]}"""

    orderViaCirce(new ScriptedClient(reply), conversation).value shouldBe
      Order(1, 2.0, Address("Bergen", Some("5003")), Nil, None)
  }

  it should "turn a decoding failure into the ValidationError the uPickle path gives" in {
    orderViaCirce(new ScriptedClient(wrongType), conversation).left.value match {
      case error: ValidationError =>
        error.field shouldBe "structured_output"
        error.message should include("does not match expected schema")
      case other => fail(s"expected a ValidationError, got $other")
    }
  }

  it should "not turn a JSON null answer into Right(null)" in {
    orderViaCirce(new ScriptedClient("null"), conversation).isLeft shouldBe true
  }

  "A tool whose arguments and result use circe" should "decode the model's arguments and return an encoded result" in {
    val tool = addTool.value

    tool.execute(ujson.Obj("a" -> 2, "b" -> 40)) shouldBe Right(ujson.Obj("sum" -> 42))
    // A value that does not fit the case class comes back as an error for the model to read, not an exception.
    tool.execute(ujson.Obj("a" -> "two", "b" -> 40)).isLeft shouldBe true
    tool.execute(ujson.Obj("a" -> 2)).isLeft shouldBe true
  }

  "A circe value given where llm4s takes a ujson.Value" should "be the same document" in {
    answer shouldBe ujson.Obj("approved" -> true)
  }

  "A play-json Reads used as a uPickle Reader" should "let completeStructured return the application's own type" in {
    orderViaPlay(new ScriptedClient(orderJson), conversation).value shouldBe order
    fromPlay(toPlay(ujson.read(orderJson))) shouldBe ujson.read(orderJson)
  }

  it should "give a ValidationError when the document does not fit" in {
    orderViaPlay(new ScriptedClient(wrongType), conversation).left.value shouldBe a[ValidationError]
  }

  "A zio-json JsonDecoder used as a uPickle Reader" should "let completeStructured return the application's own type" in {
    orderViaZio(new ScriptedClient(orderJson), conversation).value shouldBe order
  }

  it should "leave a None field out when it encodes, where circe writes null; both read back as None" in {
    fromZio(order) shouldBe ujson.read("""{"id":7,"total":19.5,"address":{"city":"Oslo"},"tags":["a","b"]}""")
    fromCirce(order.asJson) shouldBe ujson.read(orderJson)

    val withoutFields = """{"id":7,"total":19.5,"address":{"city":"Oslo"},"tags":["a","b"]}"""
    orderViaZio(new ScriptedClient(withoutFields), conversation).value shouldBe order
    orderViaCirce(new ScriptedClient(withoutFields), conversation).value shouldBe order
    orderViaPlay(new ScriptedClient(withoutFields), conversation).value shouldBe order
  }

  it should "give a ValidationError when the document does not fit" in {
    orderViaZio(new ScriptedClient(wrongType), conversation).left.value shouldBe a[ValidationError]
  }

  "A whole number above 2^53" should "be rounded by ujson's AST, whichever JSON library reads it afterwards" in {
    parsed should not be big
    parsed shouldBe big - 1

    final case class Id(id: Long)
    given Decoder[Id] = Decoder.derived
    val viaCirce = new ScriptedClient(s"""{"id":$big}""")
      .completeStructured[Id](conversation, idSchema[Id]("id", Schema.integer("id")))(using circeReader[Id])

    viaCirce.value shouldBe Id(big - 1)
  }

  it should "survive when it travels as a string" in {
    final case class Id(id: String)
    given Decoder[Id] = Decoder.derived
    val viaCirce = new ScriptedClient(s"""{"id":"$big"}""")
      .completeStructured[Id](conversation, idSchema[Id]("id", Schema.string("id")))(using circeReader[Id])

    viaCirce.value.id.toLong shouldBe big
  }

  "Doubles and ordinary integers" should "round trip through ujson unchanged" in {
    val values = Seq("0.1", "1e-7", "12345678901", "-0.5", "3.141592653589793")
    values.foreach { text =>
      ujson.read(text).num shouldBe text.toDouble
      toCirce(ujson.read(text)).asNumber.map(_.toDouble) shouldBe Some(text.toDouble)
    }
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The page (lazy: Scala's initialisation check refuses a test registered before a later plain val)
  // ---------------------------------------------------------------------------------------------------------------

  private lazy val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private lazy val specSource = read(
    root.resolve("modules/samples/src/test/scala/org/llm4s/samples/guides/JsonLibrariesGuideSpec.scala")
  )
  private lazy val page = read(root.resolve("docs/guide/json-libraries.md"))

  /** Removes the common indentation and the blank lines around a block, so the page and the spec compare by text. */
  private def normalise(block: String): String = {
    val lines  = block.split("\n", -1).toList.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    val indent = lines.filter(_.trim.nonEmpty).map(l => l.length - l.stripLeading().length).minOption.getOrElse(0)
    lines.map(l => if (l.length >= indent) l.substring(indent) else l.trim).mkString("\n")
  }

  private lazy val regions: List[(String, String)] =
    """(?ms)^[ ]*// snippet: ([a-z-]+)\n(.*?)^[ ]*// end snippet$""".r
      .findAllMatchIn(specSource)
      .map(m => (m.group(1), normalise(m.group(2))))
      .toList

  private lazy val pageBlocks: List[String] =
    """(?ms)^```scala\n(.*?)^```$""".r.findAllMatchIn(page).map(m => normalise(m.group(1))).toList

  "The page" should "have a Scala block for every snippet of this spec, in the same order, word for word" in {
    regions should not be empty
    pageBlocks shouldBe regions.map(_._2)
  }

  it should "name each library and the version of it this spec ran against" in {
    val dependencies = read(root.resolve("project/Dependencies.scala"))
    def versionOf(name: String): String =
      s"""val $name\\s*=\\s*"([^"]+)"""".r
        .findFirstMatchIn(dependencies)
        .map(_.group(1))
        .getOrElse(fail(s"no `val $name` in project/Dependencies.scala"))

    page should include(s""""circe-core"  % "${versionOf("circe")}"""")
    page should include(s""""ujson-circe" % "${versionOf("ujson")}"""")
    page should include(s""""play-json" % "${versionOf("playJson")}"""")
    page should include(s""""zio-json" % "${versionOf("zioJson")}"""")
  }
}
