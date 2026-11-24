package org.llm4s.types

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A wildcard import of `org.llm4s.types` adds no extension method (#2821). This file imports nothing from
 * `org.llm4s.syntax`, so the snippets below see only what the wildcard brings.
 */
class TypesImportSpec extends AnyFlatSpec with Matchers {

  "import org.llm4s.types.*" should "not bring toResult into scope" in {
    assertDoesNotCompile("""
      import org.llm4s.types.*
      val r: Result[Int] = scala.util.Try(1).toResult
    """)
  }

  it should "bring the Result type and its helper object" in {
    assertCompiles("""
      import org.llm4s.types.*
      val r: Result[List[Int]] = Result.traverse(List(1, 2))(n => Result.success(n))
    """)
  }

  "import org.llm4s.syntax.toResult" should "add it" in {
    assertCompiles("""
      import org.llm4s.syntax.toResult
      import org.llm4s.types.Result
      val r: Result[Int] = scala.util.Try(1).toResult
    """)
  }
}
