package org.llm4s.schema

import org.llm4s.error.{ LLMError, NotFoundError }
import org.llm4s.schema.TypedToolFixtures.*
import org.llm4s.toolapi.*
import org.llm4s.types.Result
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import upickle.default.ReadWriter

/**
 * The scoped tools of [[ScopedToolSpec]]. They live outside the spec, whose ScalaTest `fail` would otherwise be the
 * `fail` they call.
 */
object ScopedToolFixtures {

  case class Sum(total: Int, key: String, toolCallId: Option[String]) derives ReadWriter

  /** `add`, as `Tool.of` builds it in [[TypedToolSpec]], for comparing how the two refuse arguments. */
  def typedAdd: Result[ToolFunction[AddArgs, Int]]  = Tool.of[AddArgs, Int]("add", "Add numbers")(a => Right(a.a + a.b))
  def scopedAdd: Result[ToolFunction[AddArgs, Int]] = Tool.scoped[AddArgs, Int]("add", "Add numbers")(a => a.a + a.b)

  def typedOrder: Result[ToolFunction[Order, String]] =
    Tool.of[Order, String]("order", "Order")(o => Right(o.address.city))
  def scopedOrder: Result[ToolFunction[Order, String]] = Tool.scoped[Order, String]("order", "Order")(_.address.city)

  /** Reports what its scope says about the call. */
  def describe(name: String): Result[ToolFunction[AddArgs, Sum]] =
    Tool.scoped[AddArgs, Sum](name, "Add numbers, saying which call it served") { a =>
      Sum(a.a + a.b, ToolScope.idempotencyKey, ToolScope.call.map(_.toolCallId))
    }

  /** Every way to end a call early, chosen by `a`. */
  def checked(lookups: Int => Result[Int], parse: Int => Either[String, Int]): Result[ToolFunction[AddArgs, Int]] =
    Tool.scoped[AddArgs, Int]("checked", "Add numbers, with checks") { args =>
      ensure(args.a >= 0, s"a must not be negative, was ${args.a}")
      if (args.a == 13) fail("13 is unlucky")
      val looked = lookups(args.a).?
      val parsed = parse(args.b).?
      looked + parsed
    }

  var evaluated: Int = 0

  /** An `ensure` whose message is counted when it is built. */
  def lazyMessage: Result[ToolFunction[AddArgs, Int]] =
    Tool.scoped[AddArgs, Int]("lazy", "Lazy message") { args =>
      ensure(args.a > 0, { evaluated += 1; "a must be positive" })
      args.a
    }

  /** Ends the call from inside a lambda the handler passes to `map` and `foreach`. */
  def nested: Result[ToolFunction[Echo, List[Int]]] =
    Tool.scoped[Echo, List[Int]]("digits", "Digits of a text") { e =>
      e.text.toList.foreach(c => ensure(!c.isWhitespace, "no spaces"))
      e.text.toList.map(c => if (c.isDigit) c.asDigit else fail(s"'$c' is not a digit"))
    }

  def throwing: Result[ToolFunction[Echo, String]] =
    Tool.scoped[Echo, String]("boom", "Throws")(e => throw new IllegalStateException(s"cannot echo ${e.text}"))

  def noArgs: Result[ToolFunction[NoArgs, String]] = Tool.scoped[NoArgs, String]("ping", "Ping")(_ => "pong")
}

class ScopedToolSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  import ScopedToolFixtures.*

  private def built[A, R](tool: Result[ToolFunction[A, R]]): ToolFunction[A, R] =
    tool.fold(e => fail(s"the tool was not built: ${e.formatted}"), identity)

  private def handlerError(name: String, message: String): Either[ToolCallError, ujson.Value] =
    Left(ToolCallError.HandlerError(name, message))

  private val missing: Result[Int] = Left(NotFoundError("no customer 7", "customer"))

  private lazy val checkedTool =
    built(
      checked(a => if (a == 7) missing else Right(a * 10), b => if (b < 0) Left("b must not be negative") else Right(b))
    )

  "Tool.scoped" should "decode and refuse arguments exactly as Tool.of does" in {
    val add = (built(typedAdd), built(scopedAdd))
    val addCalls = List(
      ujson.Obj("a" -> 2, "b"          -> 3),
      ujson.Obj("a" -> 2),
      ujson.Obj("a" -> ujson.Null, "b" -> 3),
      ujson.Obj("a" -> "two", "b"      -> 2.5),
      ujson.Obj("a" -> 2, "b"          -> 3, "extra" -> true),
      ujson.Arr(1, 2),
      ujson.Str("2 + 3"),
      ujson.Null
    )
    addCalls.foreach(args => add._2.execute(args) shouldBe add._1.execute(args))

    val order = (built(typedOrder), built(scopedOrder))
    val orderCalls = List(
      ujson.Obj(
        "address" -> ujson.Obj("city" -> "Paris"),
        "lines"   -> ujson.Arr(ujson.Obj("item" -> "pen", "quantity" -> 2)),
        "tags"    -> ujson.Arr(),
        "id"      -> 1
      ),
      ujson.Obj("address" -> ujson.Obj(), "lines" -> ujson.Arr(ujson.Obj("item" -> 1)), "tags" -> "x", "id" -> 1.5)
    )
    orderCalls.foreach(args => order._2.execute(args) shouldBe order._1.execute(args))
    order._2.execute(orderCalls.head) shouldBe Right(ujson.Str("Paris"))
  }

  it should "send the same schema as Tool.of" in {
    built(scopedAdd).toOpenAITool() shouldBe built(typedAdd).toOpenAITool()
  }

  it should "refuse arguments that are not a JSON object, as Tool.of does" in {
    val refused = Tool.scoped[TypedToolFixtures.Operation, Int]("op", "Op")(_ => 1)
    refused.isLeft shouldBe true
    refused.left.map(_.formatted) shouldBe Tool
      .of[TypedToolFixtures.Operation, Int]("op", "Op")(_ => Right(1))
      .left
      .map(_.formatted)
  }

  it should "write a successful handler's result with its ReadWriter" in {
    val tool = built(describe("sum"))
    tool.execute(ujson.Obj("a" -> 2, "b" -> 3), ToolCallInfo("call-1", "key-1")) shouldBe
      Right(upickle.default.writeJs(Sum(5, "key-1", Some("call-1"))))
    built(noArgs).execute(ujson.Null) shouldBe Right(ujson.Str("pong"))
  }

  it should "answer through a ToolRegistry" in {
    val registry = new ToolRegistry(Seq(built(scopedAdd)))
    registry.execute(ToolCallRequest("add", ujson.Obj("a" -> 2, "b" -> 3))) shouldBe Right(ujson.Num(5))
  }

  "fail" should "end the call with its message as the tool's error" in {
    checkedTool.execute(ujson.Obj("a" -> 13, "b" -> 1)) shouldBe handlerError("checked", "13 is unlucky")
  }

  "ensure" should "end the call with its message when the condition does not hold" in {
    checkedTool.execute(ujson.Obj("a" -> -1, "b" -> 1)) shouldBe
      handlerError("checked", "a must not be negative, was -1")
    checkedTool.execute(ujson.Obj("a" -> 1, "b" -> 2)) shouldBe Right(ujson.Num(12))
  }

  it should "build its message only when the condition does not hold" in {
    val tool = built(lazyMessage)
    evaluated = 0
    tool.execute(ujson.Obj("a" -> 1, "b" -> 0)) shouldBe Right(ujson.Num(1))
    evaluated shouldBe 0
    tool.execute(ujson.Obj("a" -> 0, "b" -> 0)) shouldBe handlerError("lazy", "a must be positive")
    evaluated shouldBe 1
  }

  "?" should "end the call with an LLMError's formatted message" in {
    val expected = missing.left.map((e: LLMError) => e.formatted).swap.getOrElse("")
    expected should include("no customer 7")
    checkedTool.execute(ujson.Obj("a" -> 7, "b" -> 1)) shouldBe handlerError("checked", expected)
  }

  it should "end the call with an Either's message" in {
    checkedTool.execute(ujson.Obj("a" -> 1, "b" -> -1)) shouldBe handlerError("checked", "b must not be negative")
  }

  it should "end the call from a lambda nested in the handler" in {
    val tool = built(nested)
    tool.execute(ujson.Obj("text" -> "123")) shouldBe Right(ujson.Arr(1, 2, 3))
    tool.execute(ujson.Obj("text" -> "1x3")) shouldBe handlerError("digits", "'x' is not a digit")
    tool.execute(ujson.Obj("text" -> "1 3")) shouldBe handlerError("digits", "no spaces")
  }

  "A scoped handler that throws" should "throw from execute, and be a tool error through a ToolRegistry, as Tool.of's" in {
    val tool = built(throwing)
    an[IllegalStateException] should be thrownBy tool.execute(ujson.Obj("text" -> "hi"))

    val viaRegistry = new ToolRegistry(Seq(tool)).execute(ToolCallRequest("boom", ujson.Obj("text" -> "hi")))
    viaRegistry.isLeft shouldBe true
    viaRegistry.left.map(_.getFormattedMessage).swap.getOrElse("") should include("cannot echo hi")

    val typed =
      built(Tool.of[Echo, String]("boom", "Throws")(e => throw new IllegalStateException(s"cannot echo ${e.text}")))
    new ToolRegistry(Seq(typed))
      .execute(ToolCallRequest("boom", ujson.Obj("text" -> "hi")))
      .left
      .map(_.getFormattedMessage) shouldBe viaRegistry.left.map(_.getFormattedMessage)
  }

  "ToolScope" should "give the handler the call's idempotency key and id when the call supplies them" in {
    val tool  = built(describe("sum"))
    val args  = ujson.Obj("a" -> 1, "b" -> 1)
    val call  = ToolCallInfo("call-9", "agent-key-9")
    val first = tool.execute(args, call)
    first shouldBe Right(upickle.default.writeJs(Sum(2, "agent-key-9", Some("call-9"))))
    tool.execute(args, call) shouldBe first // a replay of the same call
  }

  it should "derive the key from the tool's name and arguments when no ToolCallInfo is supplied" in {
    val keys = Gen.zip(Gen.choose(-1000, 1000), Gen.choose(-1000, 1000))
    forAll(keys, keys) { case ((a, b), (c, d)) =>
      def key(name: String, args: ujson.Value): String =
        built(describe(name))
          .execute(args)
          .map(upickle.default.read[Sum](_))
          .fold(e => fail(e.getFormattedMessage), _.key)

      val forward  = ujson.Obj("a" -> a, "b" -> b)
      val reversed = ujson.Obj("b" -> b, "a" -> a)
      key("sum", forward) shouldBe key("sum", forward)
      key("sum", reversed) shouldBe key("sum", forward)
      key("sum", forward) should startWith("tool-")
      key("total", forward) should not be key("sum", forward)
      if ((a, b) != (c, d)) key("sum", ujson.Obj("a" -> c, "b" -> d)) should not be key("sum", forward)
    }
    built(describe("sum")).execute(ujson.Obj("a" -> 1, "b" -> 1)) shouldBe
      Right(upickle.default.writeJs(Sum(2, ToolScope.fallbackKey("sum", ujson.Obj("b" -> 1, "a" -> 1)), None)))
  }

  "The fallback key" should "sort the keys of nested objects and keep the order of arrays" in {
    val nested   = ujson.Obj("x" -> ujson.Obj("p" -> 1, "q" -> ujson.Arr(1, 2)), "y" -> ujson.Null)
    val shuffled = ujson.Obj("y" -> ujson.Null, "x" -> ujson.Obj("q" -> ujson.Arr(1, 2), "p" -> 1))
    ToolScope.fallbackKey("t", shuffled) shouldBe ToolScope.fallbackKey("t", nested)
    ToolScope.fallbackKey("t", ujson.Obj("x" -> ujson.Obj("p" -> 1, "q" -> ujson.Arr(2, 1)), "y" -> ujson.Null)) should
      not be ToolScope.fallbackKey("t", nested)
    (ToolScope.fallbackKey("t", nested) should fullyMatch).regex("tool-[0-9a-f]{64}")
  }
}
