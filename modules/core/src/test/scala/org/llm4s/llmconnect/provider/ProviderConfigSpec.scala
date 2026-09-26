package org.llm4s.llmconnect.provider

import org.scalatest.EitherValues
import org.llm4s.llmconnect.config._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderConfigSpec extends AnyFunSuite with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  // ============================ fromValues VALIDATION ============================

  // The OpenAI and DeepSeek fromValues cases moved to llm4s-openai-compatible's
  // OpenAICompatibleProviderConfigSpec with their configs (#1132).

  test("every fromValues factory returns a Left for a blank required field") {
    val blanks: Seq[(String, Either[org.llm4s.error.LLMError, ProviderConfig])] = Seq(
      "Cohere apiKey"  -> CohereConfig.fromValues("command-r", " ", CohereConfig.DEFAULT_BASE_URL),
      "Cohere baseUrl" -> CohereConfig.fromValues("command-r", "key", " "),
      "Mistral apiKey" -> MistralConfig.fromValues("mistral-small-latest", " ", MistralConfig.DEFAULT_BASE_URL)
    )

    blanks.foreach { case (field, result) =>
      withClue(field) {
        result.left.value.message shouldBe s"$field must be non-empty"
      }
    }
  }
}
