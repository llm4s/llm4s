package org.llm4s.vectorstore

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The pattern builders the Postgres stores use through `LIKE ? ESCAPE ...` can be tested without a database:
 * the escaping is pure text, and so is the clause.
 */
class SqlLikePatternSpec extends AnyFlatSpec with Matchers {

  "SqlLikePattern.prefix" should "escape the backslash, % and _ and end with the wildcard" in {
    SqlLikePattern.prefix("plain-chunk-") shouldBe "plain-chunk-%"
    SqlLikePattern.prefix("a_b-chunk-") shouldBe "a\\_b-chunk-%"
    SqlLikePattern.prefix("50%-chunk-") shouldBe "50\\%-chunk-%"
    SqlLikePattern.prefix("a\\b-chunk-") shouldBe "a\\\\b-chunk-%"
  }

  it should "escape the backslash first, so an escape it adds is not escaped again" in {
    SqlLikePattern.prefix("_") shouldBe "\\_%"
    SqlLikePattern.prefix("\\_") shouldBe "\\\\\\_%"
  }

  "SqlLikePattern.EscapeClause" should "not depend on standard_conforming_strings" in {
    // A plain '\' literal is one backslash only while standard_conforming_strings is on; with it off, Postgres
    // reads the backslash as an escape of the closing quote and the statement fails. An E'' literal always
    // processes escapes, so E'\\' is one backslash under either setting.
    SqlLikePattern.EscapeClause shouldBe "ESCAPE E'\\\\'"
  }

  "SqlLikePattern.globPrefix" should "match a wildcard literally as a one-character class" in {
    SqlLikePattern.globPrefix("plain-chunk-") shouldBe "plain-chunk-*"
    SqlLikePattern.globPrefix("a*b?c[d") shouldBe "a[*]b[?]c[[]d*"
  }
}
