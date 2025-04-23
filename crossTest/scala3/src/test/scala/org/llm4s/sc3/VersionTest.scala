package org.llm4s.sc3

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VersionTest extends AnyFlatSpec with Matchers {
  "Scala 3" should "support enum syntax" in {
    assertCompiles("""
      enum Color {
        case Red, Green, Blue
      }
    """)
  }
}
