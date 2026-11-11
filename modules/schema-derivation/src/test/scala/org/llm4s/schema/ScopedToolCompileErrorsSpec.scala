package org.llm4s.schema

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `reject`, `ensure`, `?` and the call's details need a [[ToolScope]], which only a `Tool.scoped` handler is given. */
class ScopedToolCompileErrorsSpec extends AnyFlatSpec with Matchers {

  private def messages(errors: List[scala.compiletime.testing.Error]): String = errors.map(_.message).mkString("\n")

  private val outside = "only available inside a Tool.scoped handler"

  "Outside a scoped handler, reject" should "not compile" in {
    messages(scala.compiletime.testing.typeCheckErrors("""reject("nope")""")) should include(outside)
  }

  "ensure" should "not compile" in {
    messages(scala.compiletime.testing.typeCheckErrors("""ensure(false, "nope")""")) should include(
      outside
    )
  }

  "?" should "not compile on a Result" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        """import org.llm4s.schema.?
          |val r: org.llm4s.types.Result[Int] = Right(1)
          |r.?""".stripMargin
      )
    ) should include(outside)
  }

  it should "not compile on an Either[String, T]" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        """import org.llm4s.schema.?
          |val e: Either[String, Int] = Right(1)
          |e.?""".stripMargin
      )
    ) should include(outside)
  }

  "ToolScope.idempotencyKey and ToolScope.call" should "not compile" in {
    messages(scala.compiletime.testing.typeCheckErrors("ToolScope.idempotencyKey")) should include(outside)
    messages(scala.compiletime.testing.typeCheckErrors("ToolScope.call")) should include(outside)
  }

  "Inside a scoped handler, all of them" should "compile" in {
    scala.compiletime.testing.typeCheckErrors(
      """Tool.scoped[TypedToolFixtures.AddArgs, Int]("t", "d") { a =>
        |  ensure(a.a > 0, "positive")
        |  if (a.b == 0) reject("zero")
        |  val r: org.llm4s.types.Result[Int] = Right(a.a)
        |  val e: Either[String, Int] = Right(a.b)
        |  ToolScope.idempotencyKey.length + ToolScope.call.size + r.? + e.?
        |}""".stripMargin
    ) shouldBe empty
  }
}
