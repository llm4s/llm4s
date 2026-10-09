package org.llm4s.annotation

import org.llm4s.llmconnect.config.{ CohereConfig, MistralConfig }
import org.llm4s.llmconnect.provider.{ CohereClient, CohereProvider, MistralClient, MistralProvider }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * 1.0 Scope freezes `llm4s-openai-compatible` but not its Mistral and Cohere dialects (Beta). They
 * are `@Experimental` so the exception is in the code, and `stabilityTierCheck` fails the build if
 * any of them turns `@Stable` by accident.
 */
class ExperimentalDialectsSpec extends AnyFlatSpec with Matchers {

  "The Mistral and Cohere dialects" should "be @Experimental and not @Stable" in {
    Seq[Class[?]](
      classOf[MistralClient],
      classOf[MistralConfig],
      MistralProvider.getClass,
      classOf[CohereClient],
      classOf[CohereConfig],
      CohereProvider.getClass
    ).foreach { cls =>
      withClue(cls.getName + ": ") {
        cls.isAnnotationPresent(classOf[Experimental]) shouldBe true
        cls.isAnnotationPresent(classOf[Stable]) shouldBe false
      }
    }
  }
}
