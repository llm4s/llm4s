package org.llm4s.annotation

import org.llm4s.config.ApiKeySource
import org.llm4s.llmconnect.LLMClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.annotation.{ Retention, RetentionPolicy }

/**
 * The tier annotations exist so a tool, an IDE or a Java caller can read a type's tier at runtime,
 * which a Scala-only annotation cannot offer. These tests pin that: a change to the retention would
 * leave every annotated type looking untagged to them.
 */
class StabilityAnnotationsSpec extends AnyFlatSpec with Matchers {

  "@Stable and @Experimental" should "be retained in the class file and visible by reflection" in {
    Seq(classOf[Stable], classOf[Experimental]).foreach { annotation =>
      annotation.getAnnotation(classOf[Retention]).value() shouldBe RetentionPolicy.RUNTIME
    }
  }

  "A frozen type" should "report @Stable at runtime, and not @Experimental" in {
    classOf[LLMClient].isAnnotationPresent(classOf[Stable]) shouldBe true
    classOf[LLMClient].isAnnotationPresent(classOf[Experimental]) shouldBe false
  }

  "A Scala 3 enum" should "report @Stable at runtime" in {
    classOf[ApiKeySource].isAnnotationPresent(classOf[Stable]) shouldBe true
  }
}
