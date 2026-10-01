package org.llm4s.util

import org.llm4s.error.ProcessingError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SqlIdentifierSpec extends AnyFlatSpec with Matchers {

  "SqlIdentifier.validate" should "pass valid simple names" in {
    SqlIdentifier.validate("users", "op") shouldBe Right("users")
    SqlIdentifier.validate("my_table", "op") shouldBe Right("my_table")
    SqlIdentifier.validate("column1", "op") shouldBe Right("column1")
  }

  it should "pass valid names with underscores" in {
    SqlIdentifier.validate("search_index", "op") shouldBe Right("search_index")
    SqlIdentifier.validate("vector_store_v2", "op") shouldBe Right("vector_store_v2")
  }

  it should "reject a name starting with a digit" in {
    SqlIdentifier.validate("1table", "op") match {
      case Left(err: ProcessingError) =>
        err.message should include("Invalid table name: '1table'")
      case other => fail(s"Expected Left(ProcessingError), got $other")
    }
  }

  it should "reject a name with hyphens" in {
    SqlIdentifier.validate("my-table", "op") match {
      case Left(err: ProcessingError) =>
        err.message should include("Invalid table name: 'my-table'")
      case other => fail(s"Expected Left(ProcessingError), got $other")
    }
  }

  it should "reject SQL injection attempts" in {
    val inject1 = "users; DROP TABLE users"
    SqlIdentifier.validate(inject1, "op") match {
      case Left(err: ProcessingError) =>
        err.message should include(s"Invalid table name: '$inject1'")
      case other => fail(s"Expected Left(ProcessingError), got $other")
    }

    val inject2 = "admin'--"
    SqlIdentifier.validate(inject2, "op") match {
      case Left(err: ProcessingError) =>
        err.message should include(s"Invalid table name: '$inject2'")
      case other => fail(s"Expected Left(ProcessingError), got $other")
    }
  }

  it should "reject an empty string" in {
    SqlIdentifier.validate("", "op") match {
      case Left(err: ProcessingError) =>
        err.message should include("Invalid table name: ''")
      case other => fail(s"Expected Left(ProcessingError), got $other")
    }
  }

  it should "reject a null string" in {
    SqlIdentifier.validate(null, "op") match {
      case Left(err: ProcessingError) =>
        err.message should include("Table name must not be null")
      case other => fail(s"Expected Left(ProcessingError), got $other")
    }
  }

  it should "handle max-length names correctly" in {
    val exact63 = "a" * 63
    SqlIdentifier.validate(exact63, "op") shouldBe Right(exact63)

    val over63 = "a" * 64
    SqlIdentifier.validate(over63, "op") match {
      case Left(err: ProcessingError) =>
        err.message should include(s"Invalid table name: '$over63'")
      case other => fail(s"Expected Left(ProcessingError), got $other")
    }
  }

  it should "not modify mixed case identifiers" in {
    // Note: The validator validates but does not lowercase unquoted mixed case strings
    SqlIdentifier.validate("MyTable", "op") shouldBe Right("MyTable")
  }
}
