package org.llm4s.javaapi

import org.llm4s.testutil.ClassFileVersion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** llm4s requires JDK 21 (#1493), and its bytecode says so; see [[ClassFileVersion]]. */
class JavaReleaseSpec extends AnyFlatSpec with Matchers {

  "llm4s-java-api's classes" should "be JDK 21 bytecode, compiled from Java and from Scala" in {
    ClassFileVersion.major(classOf[AgentStatusKind]) shouldBe ClassFileVersion.Jdk21
    ClassFileVersion.major(classOf[JAgent]) shouldBe ClassFileVersion.Jdk21
  }
}
