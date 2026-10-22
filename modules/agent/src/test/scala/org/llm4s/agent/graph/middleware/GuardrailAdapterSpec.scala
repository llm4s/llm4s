package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.GraphTestSupport.testModelContext
import org.llm4s.agent.guardrails.{ CompositeGuardrail, Guardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/**
 * `InputGuardrail.from` and `OutputGuardrail.from` (#1521): a composite, or any `Guardrail[String]`, used in
 * `GuardrailMiddleware`, with the hook's context passed on.
 */
class GuardrailAdapterSpec extends AnyFlatSpec with Matchers {

  /** Records which `validate` form ran, and fails when `fails`. */
  final private class Recording(val name: String, fails: Boolean, calls: CopyOnWriteArrayList[String])
      extends Guardrail[String] {
    def validate(value: String): Result[String] = { calls.add(s"$name:plain"); outcome(value) }
    override def validate(value: String, context: ModelContext): Result[String] = {
      calls.add(s"$name:context"); outcome(value)
    }
    override def description: Option[String] = Some(s"$name checks")
    private def outcome(value: String): Result[String] =
      if (fails) Left(ValidationError(name, s"$name rejected")) else Right(value)
  }

  "InputGuardrail.from" should "run a sequential composite in the middleware, stopping at its first failure" in {
    val calls = new CopyOnWriteArrayList[String]()
    val composite = CompositeGuardrail.sequential(
      Seq(new Recording("A", fails = true, calls), new Recording("B", fails = false, calls))
    )
    val result = new GuardrailMiddleware(Seq(InputGuardrail.from(composite)), Nil).beforeAgent("hi", testModelContext())
    result.left.toOption.collect { case b: GuardrailBlocked => b.guardrail } shouldBe Some(composite.name)
    calls.asScala.toList shouldBe List("A:context")
  }

  it should "pass the hook's context on, so a judge's model calls are counted" in {
    val calls = new CopyOnWriteArrayList[String]()
    val input = InputGuardrail.from(new Recording("J", fails = false, calls))
    new GuardrailMiddleware(Seq(input), Nil).beforeAgent("hi", testModelContext()).isRight shouldBe true
    calls.asScala.toList shouldBe List("J:context")
  }

  it should "keep the name and description, and delegate the plain form too" in {
    val calls = new CopyOnWriteArrayList[String]()
    val input = InputGuardrail.from(new Recording("N", fails = true, calls))
    input.name shouldBe "N"
    input.description shouldBe Some("N checks")
    input.validate("x").isLeft shouldBe true
    calls.asScala.toList shouldBe List("N:plain")
  }

  it should "return an input guardrail unchanged" in {
    val input = new InputGuardrail {
      val name: String                            = "I"
      def validate(value: String): Result[String] = Right(value)
    }
    (InputGuardrail.from(input) should be).theSameInstanceAs(input)
  }

  "OutputGuardrail.from" should "run an all composite on the answer, reporting every failure" in {
    val calls = new CopyOnWriteArrayList[String]()
    val composite = CompositeGuardrail.all(
      Seq(new Recording("A", fails = true, calls), new Recording("B", fails = true, calls))
    )
    val result =
      new GuardrailMiddleware(Nil, Seq(OutputGuardrail.from(composite))).afterAgent("answer", testModelContext())
    val reason = result.left.toOption.collect { case b: GuardrailBlocked => b.reason }.getOrElse(fail("not blocked"))
    reason should include("A rejected")
    reason should include("B rejected")
    calls.asScala.toList shouldBe List("A:context", "B:context")
  }

  it should "return an output guardrail unchanged" in {
    val output = new OutputGuardrail {
      val name: String                            = "O"
      def validate(value: String): Result[String] = Right(value)
    }
    (OutputGuardrail.from(output) should be).theSameInstanceAs(output)
  }
}
