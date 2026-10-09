package org.llm4s.schema

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A type that cannot be described is refused at compile time, with a message that says which and why. */
class CompileErrorsSpec extends AnyFlatSpec with Matchers {

  private def messages(errors: List[scala.compiletime.testing.Error]): String = errors.map(_.message).mkString("\n")

  "Deriving a schema" should "refuse a Map, saying why" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.WithMap]"))
    errors should include("Cannot derive a JSON schema for Map[String, Int]")
    errors should include("A Map cannot be described")
    errors should include("ObjectSchema")
  }

  it should "refuse a recursive type, naming it" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Tree]"))
    errors should include("Cannot derive SchemaOf[org.llm4s.schema.Fixtures.Tree]")
    errors should include("refers to itself")
  }

  it should "refuse a type that refers to itself through an Option" in {
    messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Self]")) should include(
      "refers to itself"
    )
  }

  it should "refuse a sealed hierarchy with fields, naming the case that has them" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.WithShape]"))
    errors should include("Circle")
    errors should include("has fields")
    errors should include("oneOf")
  }

  it should "refuse a field type it does not know, with the list of supported types" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.WithFunction]"))
    errors should include("Cannot derive a JSON schema for")
    errors should include("Int => Int")
    errors should include("SchemaOf.string")
  }

  it should "refuse a class that is not a case class" in {
    messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.NotACaseClass]")) should not be empty
  }

  it should "accept the types it supports" in {
    scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Nested]") shouldBe Nil
    scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Color]") shouldBe Nil
  }
}
