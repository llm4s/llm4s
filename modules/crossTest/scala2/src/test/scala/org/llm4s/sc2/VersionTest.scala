package org.llm4s.sc2

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class VersionTest extends AnyFlatSpec with Matchers {
  "Scala 2" should "not support enum syntax" in {
    assertDoesNotCompile( """
      enum Color {
        case Red, Green, Blue
      }
    """)
  }
}

