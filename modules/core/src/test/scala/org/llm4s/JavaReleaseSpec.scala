package org.llm4s

import org.llm4s.annotation.Stable
import org.llm4s.testutil.ClassFileVersion
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** llm4s requires JDK 21 (#1493), and its bytecode says so; see [[ClassFileVersion]]. */
class JavaReleaseSpec extends AnyFlatSpec with Matchers {

  "llm4s-core's classes" should "be JDK 21 bytecode, compiled from Scala and from Java" in {
    ClassFileVersion.major(classOf[ToolRegistry]) shouldBe ClassFileVersion.Jdk21
    ClassFileVersion.major(classOf[Stable]) shouldBe ClassFileVersion.Jdk21
  }
}
