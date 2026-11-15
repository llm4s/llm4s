package org.llm4s.samples.json

import com.github.plokhotnyuk.jsoniter_scala.core.{ readFromArray, writeToArray, JsonValueCodec, ReaderConfig }
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ ObjectSchema, PropertyDefinition, Schema, ToolBuilder }
import org.llm4s.types.Result
import upickle.default.{ reader, readwriter, ReadWriter, Reader }

import scala.util.Try

/**
 * Structured output and a tool result with jsoniter-scala codecs instead of uPickle ones.
 *
 * LLM4S takes a uPickle `Reader[A]` in `completeStructured` and a `ReadWriter[R]` for a tool's result. An application
 * that already has a jsoniter-scala `JsonValueCodec[A]` for its types turns it into either one through the `ujson`
 * document, at the cost of one serialisation round trip, with the bridge in [[JsoniterBridge]]. The response schema
 * still comes from LLM4S's `Schema`, since a jsoniter-scala codec carries none.
 *
 * The model is a `ScriptedLLMClient` from `llm4s-testkit`, so the example needs no API key and no network. The guide
 * that explains each step is `docs/guide/json-libraries.md#jsoniter-scala`.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.json.JsoniterStructuredOutputExample"
 * }}}
 */
object JsoniterStructuredOutputExample extends App {
  import JsoniterBridge._

  final case class Address(city: String, zip: Option[String])
  final case class Order(id: Int, total: Double, address: Address, tags: List[String], note: Option[String])

  // The default JsonCodecMaker configuration: field names as declared, `None` read from `null` or an absent field.
  given JsonValueCodec[Order] = JsonCodecMaker.make

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

  private val conversation: Result[Conversation] =
    Conversation.fromPrompts("Extract the order as JSON.", "Order 7: 19.50 to Oslo, tagged gift and express.")

  // 1. Structured output: a well-formed reply decodes into the case class.
  private val model = ScriptedLLMClient.sequence(
    Reply.text("""{"id":7,"total":19.5,"address":{"city":"Oslo","zip":null},"tags":["gift","express"]}"""),
    Reply.text("""{"id":"seven","total":19.5,"address":{"city":"Oslo"},"tags":[]}""")
  )
  private val decoded = conversation.flatMap(c => model.completeStructured[Order](c, orderSchema)(using jsoniterReader))
  println(s"Decoded:   $decoded")

  // 2. A reply that does not fit the codec: jsoniter-scala's error comes back as a ValidationError, not a throw.
  private val refused = conversation.flatMap(c => model.completeStructured[Order](c, orderSchema)(using jsoniterReader))
  println(s"Malformed: $refused")

  // 3. A tool whose arguments and result use jsoniter-scala codecs.
  final case class AddArgs(a: Int, b: Int)
  final case class AddResult(sum: Long)

  given JsonValueCodec[AddArgs]   = JsonCodecMaker.make
  given JsonValueCodec[AddResult] = JsonCodecMaker.make
  given ReadWriter[AddResult]     = jsoniterReadWriter[AddResult]

  private val addSchema = Schema
    .`object`[Map[String, Any]]("Two integers")
    .withRequiredField("a", Schema.integer("The first"))
    .withRequiredField("b", Schema.integer("The second"))

  private val addTool = ToolBuilder[Map[String, Any], AddResult]("add", "Adds two integers", addSchema)
    .withHandler { extractor =>
      Try(readJsoniter[AddArgs](extractor.params)).toEither
        .map(args => AddResult(args.a.toLong + args.b))
        .left
        .map(_.getMessage)
    }
    .buildSafe()

  println(s"Tool:      ${addTool.flatMap(_.execute(ujson.Obj("a" -> 2, "b" -> 40)))}")
}

/** Turns a jsoniter-scala `JsonValueCodec[A]` into the uPickle `Reader` and `ReadWriter` that LLM4S takes. */
object JsoniterBridge {

  // No hex dump of the model's reply in error messages: they end up in logs and in the Left.
  private val readerConfig: ReaderConfig = ReaderConfig.withAppendHexDumpToParseException(false)

  /** Decodes a `ujson` document with the codec, through its bytes. */
  def readJsoniter[A](value: ujson.Value)(using codec: JsonValueCodec[A]): A =
    readFromArray[A](ujson.writeToByteArray(value), readerConfig)

  /** Encodes a value with the codec into a `ujson` document, through its bytes. */
  def fromJsoniter[A](value: A)(using codec: JsonValueCodec[A]): ujson.Value = ujson.read(writeToArray(value))

  /** The `Reader` that `completeStructured` takes. A jsoniter-scala error becomes its `ValidationError`. */
  def jsoniterReader[A](using JsonValueCodec[A]): Reader[A] =
    reader[ujson.Value].map(value => readJsoniter[A](value))

  /** The `ReadWriter` a tool's result type needs. */
  def jsoniterReadWriter[A](using JsonValueCodec[A]): ReadWriter[A] =
    readwriter[ujson.Value].bimap[A](value => fromJsoniter(value), json => readJsoniter[A](json))
}
