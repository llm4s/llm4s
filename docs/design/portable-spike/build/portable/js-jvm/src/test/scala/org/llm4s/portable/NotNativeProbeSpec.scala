package org.llm4s.portable

import org.llm4s.types.ArtifactKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Links on the JVM and Scala.js, not on Scala Native 0.5.12, whose javalib has no `java.security.SecureRandom`, which
 * `UUID.randomUUID` needs. On Scala.js, sttp brings `scalajs-java-securerandom`.
 */
class NotNativeProbeSpec extends AnyFlatSpec with Matchers {

  "ArtifactKey.generate" should "make a random UUID" in {
    ArtifactKey.generate().value.length shouldBe 36
  }
}
