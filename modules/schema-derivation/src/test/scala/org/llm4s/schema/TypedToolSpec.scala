package org.llm4s.schema

import org.llm4s.toolapi.*
import org.llm4s.toolapi.ToolParameterError.*
import org.llm4s.toolapi.builtin.core.{ CalculatorResult, CalculatorTool }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/** The types the typed tools of [[TypedToolSpec]] take. */
object TypedToolFixtures {

  // The parameters of the `add` tool of `MCPEmbeddedServerRoundTripSpec`, whose schema is built by hand.
  @description("Add parameters")
  case class AddArgs(@description("First operand") a: Int, @description("Second operand") b: Int)
      derives SchemaOf,
        ReadWriter

  case class NoArgs() derives SchemaOf, ReadWriter

  // The cases are the strings the calculator's hand-built schema lists, in its order.
  enum Operation derives SchemaOf, ReadWriter {
    case add, subtract, multiply, divide, power, sqrt, percentage, abs, min, max, modulo
  }

  // The calculator's parameters as its hand-built schema declares them: `b` is required there.
  @description("Calculator parameters")
  case class CalculatorAsDeclared(
    @description("Mathematical operation to perform") operation: Operation,
    @description("First operand (required for all operations)") a: Double,
    @description("Second operand (required for binary operations, optional for unary like sqrt/abs)") b: Double
  ) derives SchemaOf,
        ReadWriter

  // The calculator's parameters as its handler reads them: `b` may be left out.
  @description("Calculator parameters")
  case class CalculatorArgs(
    @description("Mathematical operation to perform") operation: Operation,
    @description("First operand (required for all operations)") a: Double,
    @description("Second operand (required for binary operations, optional for unary like sqrt/abs)") b: Option[
      Double
    ]
  ) derives SchemaOf,
        ReadWriter

  case class Address(city: String, zip: Option[String]) derives SchemaOf, ReadWriter
  case class Line(item: String, quantity: Int) derives SchemaOf, ReadWriter
  case class Order(address: Address, lines: List[Line], tags: List[String], id: Long, note: Option[String])
      derives SchemaOf,
        ReadWriter

  case class Prices(prices: List[BigDecimal], discount: Option[BigDecimal]) derives SchemaOf, ReadWriter

  case class Kinds(flag: Boolean, ids: List[Option[Int]], id: Long, maybe: Option[Int]) derives SchemaOf, ReadWriter

  // A type that may be left out without being nullable: its hand-written given says it is optional, and the field
  // has a default, which uPickle reads when the key is absent.
  case class Note(text: String)
  given ReadWriter[Note] = upickle.default.readwriter[String].bimap[Note](_.text, Note(_))
  given SchemaOf[Note] = new SchemaOf[Note] {
    def typeName: String                          = "note"
    override def isOptional: Boolean              = true
    def schema(d: String): SchemaDefinition[Note] = Schema.string(d).asInstanceOf[SchemaDefinition[Note]]
  }
  case class WithNote(title: String, note: Note = Note("none")) derives SchemaOf, ReadWriter

  // A type whose reader refuses what its schema allows: the whole object, not one field.
  case class Picky(n: Int)
  given ReadWriter[Picky] = upickle.default
    .readwriter[ujson.Value]
    .bimap[Picky](
      p => ujson.Obj("n" -> p.n),
      v => if (v("n").num > 10) sys.error("n is too large") else Picky(v("n").num.toInt)
    )
  given SchemaOf[Picky] = SchemaOf.derived[Picky]

  case class Echo(text: String) derives SchemaOf, ReadWriter

  case class Unbuildable(x: Int)
  given ReadWriter[Unbuildable] = upickle.default.macroRW
  given SchemaOf[Unbuildable] = new SchemaOf[Unbuildable] {
    def typeName: String                                 = "Unbuildable"
    def schema(d: String): SchemaDefinition[Unbuildable] = throw new IllegalStateException("cannot be described")
  }
}

/** The tools [[TypedToolSpec]] compares, built outside the spec class so that its initialisation stays simple. */
object TypedTools {
  import TypedToolFixtures.*

  def built[T, R](tool: org.llm4s.types.Result[ToolFunction[T, R]]): ToolFunction[T, R] =
    tool.fold(e => sys.error(s"could not build the tool: ${e.formatted}"), identity)

  // The `add` tool of `MCPEmbeddedServerRoundTripSpec`, as it builds it by hand.
  val handBuiltAdd = built(
    ToolBuilder[Map[String, Any], String](
      "add",
      "Returns the sum of two integers",
      Schema
        .`object`[Map[String, Any]]("Add parameters")
        .withProperty(Schema.property("a", Schema.integer("First operand")))
        .withProperty(Schema.property("b", Schema.integer("Second operand")))
    ).withHandler(p =>
      for {
        a <- p.getInt("a")
        b <- p.getInt("b")
      } yield (a + b).toString
    ).buildSafe()
  )

  val typedAdd =
    built(Tool.of[AddArgs, String]("add", "Returns the sum of two integers")(args => Right((args.a + args.b).toString)))

  val calculator = built(CalculatorTool.toolSafe)

  val addParameters = SchemaOf[AddArgs].definition.asInstanceOf[ObjectSchema[AddArgs]]

  def decodeAdd(arguments: ujson.Value) = Tool.decode(addParameters, arguments)

  // What a hand-written handler that checks every parameter up front reports.
  def handWritten(arguments: ujson.Value) =
    SafeParameterExtractor(arguments).validateRequired("a" -> "integer", "b" -> "integer")

  val orderParameters = SchemaOf[Order].definition.asInstanceOf[ObjectSchema[Order]]
}

/**
 * Typed tools (#1473): the schema a typed tool sends is the one a hand-built tool sends for the same parameters, and
 * the errors its argument decoding reports are the ones a hand-written handler reports through
 * `SafeParameterExtractor`.
 */
class TypedToolSpec extends AnyFlatSpec with Matchers {
  import TypedToolFixtures.*
  import TypedTools.*

  // ---- the schema

  "A typed tool" should "send the schema of the hand-built add tool, in strict and relaxed mode (golden)" in {
    typedAdd.toOpenAITool() shouldBe handBuiltAdd.toOpenAITool()
    typedAdd.toOpenAITool(strict = false) shouldBe handBuiltAdd.toOpenAITool(strict = false)
  }

  it should "send the hand-built calculator's schema for the parameters as it declares them (golden)" in {
    val typed = built(
      Tool.of[CalculatorAsDeclared, String]("calculator", calculator.description)(_ => Right("unused"))
    )
    typed.toOpenAITool() shouldBe calculator.toOpenAITool()
    typed.toOpenAITool(strict = false) shouldBe calculator.toOpenAITool(strict = false)
  }

  it should "differ from the hand-built calculator only in letting the optional operand be null or absent" in {
    val typed = built(Tool.of[CalculatorArgs, String]("calculator", calculator.description)(_ => Right("unused")))

    val strict = calculator.toOpenAITool()
    strict("function")("parameters")("properties")("b")("type") = ujson.Arr("number", "null")
    typed.toOpenAITool() shouldBe strict

    val relaxed = calculator.toOpenAITool(strict = false)
    relaxed("function")("parameters")("properties")("b")("type") = ujson.Arr("number", "null")
    relaxed("function")("parameters")("required") = ujson.Arr("operation", "a")
    typed.toOpenAITool(strict = false) shouldBe relaxed
  }

  it should "describe a Long as an integer, as an Int is" in {
    SchemaOf[Order].toJsonSchema()("properties")("id") shouldBe ujson.Obj("type" -> "integer", "description" -> "id")
  }

  // ---- calls that succeed

  it should "answer a call as the hand-built tool does, and through a ToolRegistry" in {
    val arguments = ujson.Obj("a" -> 2, "b" -> 3)
    typedAdd.execute(arguments) shouldBe Right(ujson.Str("5"))
    typedAdd.execute(arguments) shouldBe handBuiltAdd.execute(arguments)

    val registry = new ToolRegistry(Seq(typedAdd))
    registry.execute(ToolCallRequest("add", arguments)) shouldBe Right(ujson.Str("5"))
    registry.getOpenAITools() shouldBe ujson.Arr(handBuiltAdd.toOpenAITool())
  }

  it should "ignore keys the type has no field for, as a hand-written tool does" in {
    val arguments = ujson.Obj("a" -> 1, "b" -> 2, "c" -> true)
    typedAdd.execute(arguments) shouldBe Right(ujson.Str("3"))
    handBuiltAdd.execute(arguments) shouldBe Right(ujson.Str("3"))
  }

  it should "accept whole numbers written with a fraction or an exponent, as getInt does" in {
    val arguments = ujson.read("""{"a": 3.0, "b": 1e2}""")
    typedAdd.execute(arguments) shouldBe Right(ujson.Str("103"))
    handBuiltAdd.execute(arguments) shouldBe Right(ujson.Str("103"))
  }

  it should "write the result with its uPickle writer" in {
    val tool = built(Tool.of[Echo, Echo]("echo", "Echoes")(Right(_)))
    tool.execute(ujson.Obj("text" -> "hi")) shouldBe Right(ujson.Obj("text" -> "hi"))
  }

  it should "report the handler's Left as the tool's error, as a hand-written handler's" in {
    val tool = built(Tool.of[Echo, String]("echo", "Echoes")(_ => Left("kaboom")))
    tool.execute(ujson.Obj("text" -> "hi")) shouldBe Left(ToolCallError.HandlerError("echo", "kaboom"))
  }

  it should "treat null arguments as no arguments when it has no parameters, and refuse them otherwise" in {
    val handBuiltPing = built(
      ToolBuilder[Map[String, Any], String]("ping", "Pong", Schema.`object`[Map[String, Any]]("No parameters"))
        .withHandler(_ => Right("pong"))
        .buildSafe()
    )
    val ping = built(Tool.of[NoArgs, String]("ping", "Pong")(_ => Right("pong")))
    ping.execute(ujson.Null) shouldBe Right(ujson.Str("pong"))
    ping.execute(ujson.Null) shouldBe handBuiltPing.execute(ujson.Null)
    ping.execute(ujson.Str("anything")) shouldBe Right(ujson.Str("pong"))

    typedAdd.execute(ujson.Null) shouldBe Left(ToolCallError.NullArguments("add"))
    typedAdd.execute(ujson.Null) shouldBe handBuiltAdd.execute(ujson.Null)
  }

  // ---- argument errors, compared with a hand-written tool's

  "A typed tool's argument errors" should "name a missing parameter and the keys that were sent, as getInt does" in {
    val arguments = ujson.Obj("a" -> 1)
    decodeAdd(arguments) shouldBe Left(List(MissingParameter("b", "integer", List("a"))))
    decodeAdd(arguments) shouldBe handWritten(arguments)
    typedAdd.execute(arguments) shouldBe handBuiltAdd.execute(arguments)
  }

  it should "report a parameter of the wrong type, as getInt does" in {
    val arguments = ujson.Obj("a" -> "one", "b" -> 2)
    decodeAdd(arguments) shouldBe Left(List(TypeMismatch("a", "integer", "string")))
    decodeAdd(arguments) shouldBe handWritten(arguments)
    typedAdd.execute(arguments) shouldBe handBuiltAdd.execute(arguments)
  }

  it should "refuse a fraction or a number outside the Int range for an Int, as getInt does" in {
    for (a <- List(ujson.Num(1.5), ujson.Num(3e9), ujson.Num(2147483648.0))) {
      val arguments = ujson.Obj("a" -> a, "b" -> 2)
      decodeAdd(arguments) shouldBe Left(List(TypeMismatch("a", "integer", "number")))
      typedAdd.execute(arguments) shouldBe handBuiltAdd.execute(arguments)
    }
  }

  it should "report a required parameter that is null, as getInt does" in {
    val arguments = ujson.Obj("a" -> ujson.Null, "b" -> 2)
    decodeAdd(arguments) shouldBe Left(List(NullParameter("a", "integer")))
    decodeAdd(arguments) shouldBe handWritten(arguments)
    typedAdd.execute(arguments) shouldBe handBuiltAdd.execute(arguments)
  }

  it should "report every parameter when the arguments are not an object, as the extractor does" in {
    for (arguments <- List(ujson.Str("2 and 3"), ujson.Arr(2, 3), ujson.Num(5), ujson.True)) {
      decodeAdd(arguments) shouldBe handWritten(arguments)
      decodeAdd(arguments).left.map(_.map(_.getClass)) shouldBe Left(
        List(classOf[InvalidNesting], classOf[InvalidNesting])
      )
      // the hand-written handler stops at the first, which is the typed tool's first
      typedAdd.execute(arguments) shouldBe Left(
        ToolCallError.HandlerError("add", MultipleErrors(handWritten(arguments).left.getOrElse(Nil)).getMessage)
      )
      handBuiltAdd.execute(arguments) shouldBe Left(
        ToolCallError.HandlerError("add", handWritten(arguments).left.getOrElse(Nil).head.getMessage)
      )
    }
  }

  it should "report every problem at once, in field order, as validateRequired does" in {
    val arguments = ujson.Obj("b" -> "two")
    val errors    = List(MissingParameter("a", "integer", List("b")), TypeMismatch("b", "integer", "string"))
    decodeAdd(arguments) shouldBe Left(errors)
    decodeAdd(arguments) shouldBe handWritten(arguments)
    typedAdd.execute(arguments) shouldBe Left(ToolCallError.HandlerError("add", MultipleErrors(errors).getMessage))
  }

  it should "report a calculator operand of the wrong type as the hand-built calculator does" in {
    val typed = built(Tool.of[CalculatorArgs, String]("calculator", calculator.description)(_ => Right("unused")))
    for (
      arguments <- List(
        ujson.Obj("operation" -> "add", "a" -> "x", "b"        -> 1),
        ujson.Obj("operation" -> "add", "b" -> 1),
        ujson.Obj("operation" -> "add", "a" -> ujson.Null, "b" -> 1),
        ujson.Obj("operation" -> 7, "a"     -> 1, "b"          -> 1)
      )
    )
      withClue(arguments.render()) {
        typed.execute(arguments) shouldBe calculator.execute(arguments)
      }
  }

  it should "report an optional parameter of the wrong type as getOptionalDouble does, and accept it absent or null" in {
    val parameters = SchemaOf[CalculatorArgs].definition.asInstanceOf[ObjectSchema[CalculatorArgs]]
    val wrong      = ujson.Obj("operation" -> "add", "a" -> 1, "b" -> "two")
    Tool.decode(parameters, wrong) shouldBe Left(List(TypeMismatch("b", "number", "string")))
    Tool.decode(parameters, wrong) shouldBe SafeParameterExtractor(wrong)
      .getOptionalDouble("b")
      .map(_ => ())
      .left
      .map(List(_))

    Tool.decode(parameters, ujson.Obj("operation" -> "sqrt", "a" -> 9)) shouldBe
      Right(CalculatorArgs(Operation.sqrt, 9, None))
    Tool.decode(parameters, ujson.Obj("operation" -> "sqrt", "a" -> 9, "b" -> ujson.Null)) shouldBe
      Right(CalculatorArgs(Operation.sqrt, 9, None))
    Tool.decode(parameters, ujson.Obj("operation" -> "add", "a" -> 9, "b" -> 1)) shouldBe
      Right(CalculatorArgs(Operation.add, 9, Some(1)))
  }

  it should "report a value outside an enum's cases, listing them" in {
    val parameters = SchemaOf[CalculatorArgs].definition.asInstanceOf[ObjectSchema[CalculatorArgs]]
    Tool.decode(parameters, ujson.Obj("operation" -> "mul", "a" -> 1, "b" -> 2)) shouldBe Left(
      List(
        TypeMismatch(
          "operation",
          "one of add, subtract, multiply, divide, power, sqrt, percentage, abs, min, max, modulo",
          "'mul'"
        )
      )
    )
  }

  // ---- nested types

  "A typed tool's nested arguments" should "decode, with absent or null Options as None" in {
    val arguments = ujson.read(
      """{"address": {"city": "Paris"}, "lines": [{"item": "tea", "quantity": 2}], "tags": [], "id": 3e9, "note": null}"""
    )
    Tool.decode(orderParameters, arguments) shouldBe
      Right(Order(Address("Paris", None), List(Line("tea", 2)), Nil, 3000000000L, None))
  }

  it should "name a missing nested field by its path, as a dotted getString does" in {
    val arguments = ujson.read("""{"address": {"zip": "75001"}, "lines": [], "tags": [], "id": 1}""")
    val missing   = MissingParameter("address.city", "string", List("zip"))
    Tool.decode(orderParameters, arguments) shouldBe Left(List(missing))
    SafeParameterExtractor(arguments).getStringEnhanced("address.city") shouldBe Left(missing)
  }

  it should "report a nested object that is not an object as getObject does" in {
    val arguments = ujson.read("""{"address": "Paris", "lines": [], "tags": [], "id": 1}""")
    Tool.decode(orderParameters, arguments) shouldBe
      SafeParameterExtractor(arguments).getObjectEnhanced("address").map(_ => ()).left.map(List(_))
    Tool.decode(orderParameters, arguments) shouldBe Left(List(TypeMismatch("address", "object", "string")))
  }

  it should "name every problem inside objects and arrays by its path" in {
    val arguments = ujson.read(
      """{"address": {"city": 1, "zip": 5}, "lines": [{"item": "tea", "quantity": 1.5}, null, "coffee", {}],
        | "tags": [1, "ok"], "id": 1e19, "unknown": true}""".stripMargin
    )
    Tool.decode(orderParameters, arguments) shouldBe Left(
      List(
        TypeMismatch("address.city", "string", "number"),
        TypeMismatch("address.zip", "string", "number"),
        TypeMismatch("lines[0].quantity", "integer", "number"),
        NullParameter("lines[1]", "object"),
        TypeMismatch("lines[2]", "object", "string"),
        MissingParameter("lines[3].item", "string", Nil),
        MissingParameter("lines[3].quantity", "integer", Nil),
        TypeMismatch("tags[0]", "string", "number"),
        TypeMismatch("id", "integer", "number")
      )
    )
    Tool.decode(
      orderParameters,
      ujson.read("""{"address": {"city": "x"}, "lines": "none", "tags": [], "id": 1}""")
    ) shouldBe
      Left(List(TypeMismatch("lines", "array", "string")))
  }

  it should "report a value of the right JSON type that its reader refuses, at its path" in {
    val parameters = SchemaOf[Prices].definition.asInstanceOf[ObjectSchema[Prices]]
    Tool.decode(parameters, ujson.read("""{"prices": ["1.50", "2.50"]}""")) shouldBe
      Right(Prices(List(BigDecimal("1.50"), BigDecimal("2.50")), None))
    Tool.decode(parameters, ujson.read("""{"prices": ["1.50", "abc"]}""")) match {
      case Left(List(TypeMismatch("prices[1]", "string", received))) =>
        received should startWith("string that cannot be read (")
      case other => fail(s"unexpected $other")
    }
  }

  it should "report a nullable field's reader failure with the type the field takes" in {
    val parameters = SchemaOf[Prices].definition.asInstanceOf[ObjectSchema[Prices]]
    Tool.decode(parameters, ujson.read("""{"prices": [], "discount": "ten"}""")) match {
      case Left(List(TypeMismatch("discount", "string", received))) =>
        received should startWith("string that cannot be read (")
      case other => fail(s"unexpected $other")
    }
  }

  it should "report the arguments as a whole when the reader refuses the object itself" in {
    val parameters = SchemaOf[Picky].definition.asInstanceOf[ObjectSchema[Picky]]
    Tool.decode(parameters, ujson.Obj("n" -> 3)) shouldBe Right(Picky(3))
    Tool.decode(parameters, ujson.Obj("n" -> 30)) match {
      case Left(List(TypeMismatch("arguments", "object", received))) => received should include("n is too large")
      case other                                                     => fail(s"unexpected $other")
    }
  }

  "A typed tool's other field types" should "be named as SafeParameterExtractor names them when missing" in {
    val parameters = SchemaOf[Kinds].definition.asInstanceOf[ObjectSchema[Kinds]]
    val sent       = ujson.Obj("other" -> 1)
    Tool.decode(parameters, sent) shouldBe Left(
      List(
        MissingParameter("flag", "boolean", List("other")),
        MissingParameter("ids", "array", List("other")),
        MissingParameter("id", "integer", List("other"))
      )
    )
    Tool.decode(parameters, sent) shouldBe
      SafeParameterExtractor(sent).validateRequired("flag" -> "boolean", "ids" -> "array", "id" -> "integer")
  }

  it should "check a Boolean, null items of a list of Options, and Long and Int ranges" in {
    val parameters = SchemaOf[Kinds].definition.asInstanceOf[ObjectSchema[Kinds]]
    Tool.decode(parameters, ujson.read("""{"flag": true, "ids": [1, null], "id": -3e18, "maybe": 4}""")) shouldBe
      Right(Kinds(true, List(Some(1), None), -3000000000000000000L, Some(4)))
    val wrong = ujson.read("""{"flag": "yes", "ids": [1.5, {}], "id": 9.3e18, "maybe": 3e9}""")
    Tool.decode(parameters, wrong) shouldBe Left(
      List(
        TypeMismatch("flag", "boolean", "string"),
        TypeMismatch("ids[0]", "integer", "number"),
        TypeMismatch("ids[1]", "integer", "object"),
        TypeMismatch("id", "integer", "number"),
        TypeMismatch("maybe", "integer", "number")
      )
    )
    Tool.decode(parameters, wrong) shouldBe Left(
      List(
        SafeParameterExtractor(wrong).getBooleanEnhanced("flag"),
        SafeParameterExtractor(ujson.Obj("ids[0]" -> 1.5)).getIntEnhanced("ids[0]"),
        SafeParameterExtractor(ujson.Obj("ids[1]" -> ujson.Obj())).getIntEnhanced("ids[1]"),
        SafeParameterExtractor(wrong).getIntEnhanced("id"),
        SafeParameterExtractor(wrong).getOptionalInt("maybe")
      ).flatMap(_.left.toOption)
    )
  }

  it should "leave out an optional field that is not nullable, for its default" in {
    val parameters = SchemaOf[WithNote].definition.asInstanceOf[ObjectSchema[WithNote]]
    Tool.decode(parameters, ujson.Obj("title" -> "t")) shouldBe Right(WithNote("t", Note("none")))
    Tool.decode(parameters, ujson.Obj("title" -> "t", "note" -> "n")) shouldBe Right(WithNote("t", Note("n")))
  }

  it should "report arguments that are null or an object of the wrong shape as the extractor does" in {
    decodeAdd(ujson.Null) shouldBe handWritten(ujson.Null)
    val nested = ujson.Obj("a" -> ujson.Obj(), "b" -> 1)
    decodeAdd(nested) shouldBe Left(List(TypeMismatch("a", "integer", "object")))
    decodeAdd(nested) shouldBe handWritten(nested)
  }

  // ---- types that are not a tool's arguments

  "Tool.of" should "refuse arguments that are not a JSON object" in {
    val refused = Tool.of[Operation, String]("op", "An enum is not an object")(_ => Right("unused"))
    refused.left.map(_.message) match {
      case Left(message) => message should include("Operation is not a JSON object")
      case Right(_)      => fail("an enum was taken as a tool's arguments")
    }
  }

  it should "refuse a sum type with fields, and a case class holding one, saying why" in {
    import SumFixtures.*
    for (
      refused <- List(
        Tool.of[Action, String]("act", "A sum type")(_ => Right("unused")),
        Tool.of[Drawing, String]("draw", "Holds sum types")(_ => Right("unused"))
      )
    )
      refused.left.map(_.message) match {
        case Left(message) =>
          message should include("is a sum type with fields")
          message should include("a tool's arguments cannot hold a sum type")
        case Right(_) => fail("a sum type was taken as a tool's arguments")
      }
  }

  it should "refuse arguments whose schema cannot be built, with the reason" in {
    val refused = Tool.of[Unbuildable, String]("u", "Cannot be described")(_ => Right("unused"))
    refused.left.map(_.message) match {
      case Left(message) => message should include("cannot be described")
      case Right(_)      => fail("a schema that throws was taken")
    }
  }

  it should "build the CalculatorResult a typed calculator returns with its writer" in {
    val typed = built(
      Tool.of[CalculatorArgs, CalculatorResult]("calculator", calculator.description)(args =>
        Right(CalculatorResult("expr", args.a, args.a.toString))
      )
    )
    typed.execute(ujson.Obj("operation" -> "abs", "a" -> 2)) shouldBe
      Right(ujson.Obj("expression" -> "expr", "result" -> 2.0, "formatted" -> "2.0"))
  }
}
