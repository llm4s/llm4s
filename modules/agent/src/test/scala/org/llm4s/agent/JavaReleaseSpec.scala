package org.llm4s.agent

import org.llm4s.testutil.ClassFileVersion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** llm4s requires JDK 21 (#1493), and its bytecode says so; see [[ClassFileVersion]]. */
class JavaReleaseSpec extends AnyFlatSpec with Matchers {

  "llm4s-agent's classes" should "be JDK 21 bytecode, the level of the Ox classes it depends on" in {
    ClassFileVersion.major(classOf[Agent]) shouldBe ClassFileVersion.Jdk21
    ClassFileVersion.major(classOf[ox.Fork[?]]) shouldBe ClassFileVersion.Jdk21
  }
}
