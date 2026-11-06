package org.llm4s.schema.iron

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A constraint the module cannot describe is refused at compile time, naming it, rather than left out of the schema. */
// Each snippet imports what it needs: the compiler checks it on its own.
class IronCompileErrorsSpec extends AnyFlatSpec with Matchers {

  private def messages(errors: List[scala.compiletime.testing.Error]): String = errors.map(_.message).mkString("\n")

  "A constraint with no JSON Schema form" should "be refused, naming it" in {
    val errors = messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[Int :| Multiple[3]]"
      )
    )
    errors should include("Cannot describe the Iron constraint Multiple[3]")
    errors should include("refused rather than left out of the schema")
  }

  it should "be named inside a conjunction, as the part that cannot be described" in {
    val errors = messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[String :| (MinLength[1] & StartWith[\"a\"])]"
      )
    )
    errors should include("Cannot describe the Iron constraint StartWith[\"a\"]")
    errors should include("it is not a length constraint")
  }

  // Summoned for a field, the compiler reports SchemaOf's own message for the field's type, which names the
  // constraint, and that the module's given does not match it; summoned directly, the module's message above.
  it should "be refused in a field of a derived case class, naming the constraint" in {
    val errors = messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; final case class Even(n: Int :| Multiple[2]); SchemaOf.derived[Even]"
      )
    )
    errors should include("Cannot derive a JSON schema for io.github.iltotore.iron.IronType[Int,")
    errors should include("Multiple[(2 : Int)]")
    errors should include("refinedSchemaOf")
  }

  "Match" should "be refused, saying that core's StringSchema has no pattern" in {
    val errors = messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[String :| Match[\"[a-z]+\"]]"
      )
    )
    errors should include("Match[\"[a-z]+\"]")
    errors should include("StringSchema has no pattern")
  }

  "A negation other than Not[Empty] and Not[Blank]" should "be refused" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[String :| Not[MaxLength[3]]]"
      )
    ) should include(
      "only Not[Empty], and Not[Blank] on a String, are described"
    )
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[List[String] :| Not[Blank]]"
      )
    ) should include(
      "only Not[Empty], and Not[Blank] on a String, are described"
    )
  }

  "A union other than >= and <=" should "be refused" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[Int :| (Less[0] | Greater[10])]"
      )
    ) should include(
      "it is a union other than >= or <="
    )
  }

  "A numeric bound on a string, or a length on a number" should "be refused" in {
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[Int :| MinLength[2]]"
      )
    ) should include(
      "it is not a numeric bound"
    )
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[String :| Positive]"
      )
    ) should include(
      "it is not a length constraint"
    )
  }

  "A constraint on a type with no JSON Schema bounds" should "be refused, naming the type" in {
    val errors = messages(
      scala.compiletime.testing.typeCheckErrors(
        "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[BigDecimal :| Positive]"
      )
    )
    errors should include("BigDecimal is not a number, a string or a list")
  }

  "A supported constraint" should "compile" in {
    scala.compiletime.testing.typeCheckErrors(
      "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[Int :| Interval.Closed[1, 5]]"
    ) shouldBe empty
    scala.compiletime.testing.typeCheckErrors(
      "import io.github.iltotore.iron.*; import io.github.iltotore.iron.constraint.all.*; import org.llm4s.schema.*; SchemaOf[Int :| Pure]"
    ) shouldBe empty
  }
}
