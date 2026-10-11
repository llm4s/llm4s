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

  it should "refuse a generic type that refers to itself" in {
    messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Node[Int]]")) should include(
      "refers to itself"
    )
  }

  it should "refuse, and terminate on, a type that refers to itself with ever larger type arguments" in {
    // Grow[Int] holds a Grow[List[Int]], which holds a Grow[List[List[Int]]]...: no type repeats, so only the
    // depth bound stops the walk. Without it this test does not compile, it never finishes compiling.
    messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Grow[Int]]")) should include(
      "refers to itself"
    )
  }

  it should "refuse mutually recursive types, and recursion through a generic case class" in {
    messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Ping]")) should include(
      "refers to itself"
    )
    messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Wrapped]")) should include(
      "refers to itself"
    )
  }

  it should "accept a generic case class nested in itself with different type arguments" in {
    scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Boxes]") shouldBe Nil
  }

  it should "refuse a sealed hierarchy nested in another, whose case names uPickle would not write" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.WithOuter]"))
    errors should include("Middle of Outer is not a singleton")
  }

  it should "refuse a sealed hierarchy with fields, naming the case that has them" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.WithShape]"))
    errors should include("Circle")
    errors should include("Circle of Shape is not a singleton")
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

  it should "refuse an Option directly inside an Option, which uPickle writes as an array" in {
    val direct = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf[Option[Option[Int]]]"))
    direct should include("An Option directly inside an Option cannot be described")
    direct should include("Cannot derive a JSON schema for Option[Option[Int]]")
    direct should include("[null]")
    val field = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.WithNestedOption]"))
    field should include("Cannot derive a JSON schema for Option[Option[Int]]")
    field should include("An Option directly inside an Option cannot be described")
  }

  it should "still describe an Option of a type parameter, and an Option inside a List inside an Option" in {
    scala.compiletime.testing.typeCheckErrors("def f[T: SchemaOf] = SchemaOf[Option[T]]") shouldBe Nil
    scala.compiletime.testing.typeCheckErrors("SchemaOf[Option[List[Option[Int]]]]") shouldBe Nil
  }

  it should "refuse a tuple, which uPickle writes as an array" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[(Int, String)]"))
    errors should include("Cannot derive SchemaOf[scala.Tuple2[scala.Int, scala.Predef.String]]")
    errors should include("a tuple")
    messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.WithTuple]")) should include(
      "Cannot derive a JSON schema for (Int, String)"
    )
    messages(scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.WithTuple]")) should include(
      "a tuple as an array of mixed types: use a case class"
    )
  }

  it should "take a hand-written given as it is, even for a sealed hierarchy with fields or a recursive type" in {
    scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Order]") shouldBe Nil
  }

  it should "still refuse a recursive type whose own given is derived by hand" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "{ given tree: SchemaOf[Fixtures.Tree] = SchemaOf.derived[Fixtures.Tree]; tree }"
      )
    ) should include("refers to itself")
  }

  it should "still refuse mutually recursive types that both derive SchemaOf" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "case class P(q: Option[Q]) derives SchemaOf; case class Q(p: P) derives SchemaOf"
      )
    ) should include("refers to itself")
  }

  it should "check a wide, deep type in reasonable time, walking each type once" in {
    scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Wide12]") shouldBe Nil
  }

  it should "accept the types it supports" in {
    scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Nested]") shouldBe Nil
    scala.compiletime.testing.typeCheckErrors("SchemaOf.derived[Fixtures.Color]") shouldBe Nil
  }
}
