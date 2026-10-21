package org.llm4s.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The text a "missing apiKey" error tells the user to act on: [[SharedCredentials.missingKeyHint]], and the
 * shared-credentials path it names, [[SharedCredentials.apiKeyPath]].
 */
class SharedCredentialsHintsSpec extends AnyWordSpec with Matchers {

  private val openai  = ProviderId("openai")
  private val section = "llm4s.providers.openai-main"

  "SharedCredentials.missingKeyHint" should {

    "name the shared credentials path when the vendor binds no variable" in {
      SharedCredentials.missingKeyHint(Nil, openai, section) shouldBe
        "set llm4s.credentials.openai.apiKey, or set apiKey under llm4s.providers.openai-main in application.conf"
    }

    "name the one variable the vendor binds" in {
      SharedCredentials.missingKeyHint(Seq("OPENAI_API_KEY"), openai, section) shouldBe
        "set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf"
    }

    "join two variables with or" in {
      SharedCredentials.missingKeyHint(Seq("GOOGLE_API_KEY", "GEMINI_API_KEY"), ProviderId("gemini"), section) shouldBe
        "set GOOGLE_API_KEY or GEMINI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf"
    }

    "join three variables with commas and the last with or" in {
      SharedCredentials.missingKeyHint(Seq("A_KEY", "B_KEY", "C_KEY"), openai, section) shouldBe
        "set A_KEY, B_KEY or C_KEY, or set apiKey under llm4s.providers.openai-main in application.conf"
    }

    "keep the variables in the order given, highest precedence first" in {
      SharedCredentials.missingKeyHint(Seq("C_KEY", "A_KEY"), openai, section) should startWith("set C_KEY or A_KEY,")
    }

    "include the client section unchanged" in {
      Seq("llm4s.embeddings.openai", "llm4s.rerank.cohere", "llm4s.providers.\"odd.name\"").foreach { s =>
        SharedCredentials.missingKeyHint(Seq("OPENAI_API_KEY"), openai, s) shouldBe
          s"set OPENAI_API_KEY, or set apiKey under $s in application.conf"
      }
    }
  }

  "SharedCredentials.apiKeyPath" should {

    "be llm4s.credentials.<id>.apiKey" in {
      SharedCredentials.apiKeyPath(openai) shouldBe "llm4s.credentials.openai.apiKey"
    }

    "give the same path for an id written with different case or surrounding spaces" in {
      SharedCredentials.apiKeyPath(ProviderId(" OpenAI ")) shouldBe "llm4s.credentials.openai.apiKey"
    }

    "leave a hyphenated id unquoted, as HOCON allows" in {
      SharedCredentials.apiKeyPath(ProviderId("openai-compatible")) shouldBe
        "llm4s.credentials.openai-compatible.apiKey"
    }

    "quote an id containing a dot, so it stays one path element" in {
      SharedCredentials.apiKeyPath(ProviderId("acme.ai")) shouldBe "llm4s.credentials.\"acme.ai\".apiKey"
    }
  }
}
