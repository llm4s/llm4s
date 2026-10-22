package org.llm4s.samples.toolapi

import org.llm4s.toolapi.ToolCallError
import org.llm4s.toolapi.builtin.core.CalculatorTool
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The typed calculator of [[TypedCalculatorExample]] is the built-in one, re-expressed. */
class TypedCalculatorExampleSpec extends AnyFlatSpec with Matchers {

  private val handBuilt = CalculatorTool.toolSafe.fold(e => sys.error(e.formatted), identity)
  private val typed     = TypedCalculator.tool.fold(e => sys.error(e.formatted), identity)

  "The typed calculator" should "send the built-in schema, with the optional operand allowed to be null" in {
    val expected = handBuilt.toOpenAITool()
    expected("function")("parameters")("properties")("b")("type") = ujson.Arr("number", "null")
    typed.toOpenAITool() shouldBe expected
    typed.name shouldBe handBuilt.name
    typed.description shouldBe handBuilt.description
  }

  it should "answer every operation, and every error, as the built-in one does" in {
    val calls = List(
      ujson.Obj("operation" -> "add", "a"        -> 15, "b"         -> 27),
      ujson.Obj("operation" -> "subtract", "a"   -> 1.5, "b"        -> 0.25),
      ujson.Obj("operation" -> "multiply", "a"   -> 6, "b"          -> 7),
      ujson.Obj("operation" -> "divide", "a"     -> 1, "b"          -> 3),
      ujson.Obj("operation" -> "divide", "a"     -> 1, "b"          -> 0),
      ujson.Obj("operation" -> "power", "a"      -> 2, "b"          -> 10),
      ujson.Obj("operation" -> "power", "a"      -> 10, "b"         -> 400),
      ujson.Obj("operation" -> "sqrt", "a"       -> 144),
      ujson.Obj("operation" -> "sqrt", "a"       -> -1),
      ujson.Obj("operation" -> "percentage", "a" -> 15, "b"         -> 250),
      ujson.Obj("operation" -> "abs", "a"        -> -3),
      ujson.Obj("operation" -> "min", "a"        -> 3, "b"          -> 4),
      ujson.Obj("operation" -> "max", "a"        -> 3, "b"          -> 4),
      ujson.Obj("operation" -> "modulo", "a"     -> 7, "b"          -> 3),
      ujson.Obj("operation" -> "modulo", "a"     -> 7, "b"          -> 0),
      ujson.Obj("operation" -> "add", "a"        -> 1),
      ujson.Obj("operation" -> "add", "a"        -> "fifteen", "b"  -> 27),
      ujson.Obj("operation" -> "add", "b"        -> 27),
      ujson.Obj("operation" -> "add", "a"        -> ujson.Null, "b" -> 27),
      ujson.Obj("operation" -> 3, "a"            -> 1, "b"          -> 2)
    )
    calls.foreach { arguments =>
      withClue(arguments.render()) {
        typed.execute(arguments) shouldBe handBuilt.execute(arguments)
      }
    }
  }

  it should "refuse an operation the built-in one rejects in its handler, before the handler runs" in {
    typed.execute(ujson.Obj("operation" -> "mul", "a" -> 1, "b" -> 2)) match {
      case Left(ToolCallError.HandlerError("calculator", message)) => message should include("one of add, subtract")
      case other                                                   => fail(s"unexpected $other")
    }
  }

  "The sample" should "run without a model" in {
    noException should be thrownBy TypedCalculatorExample.main(Array.empty)
  }
}
