package org.llm4s.testkit

import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.fixtures.{ AcmeEmbeddings, AcmeProvider, UnboundEmbeddings, UnboundProvider }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CredentialsRoundTripSpec extends AnyWordSpec with Matchers with EitherValues:

  given ProviderRegistry = ProviderRegistry.default

  "chatBindings" should {

    "resolve each declared variable to its value when it alone is set" in {
      CredentialsRoundTrip.chatBindings(AcmeProvider) shouldBe Map(
        "ACME_API_KEY" -> Right(Some("key-from-ACME_API_KEY"))
      )
    }

    "report a declared variable that reference.conf does not bind" in {
      CredentialsRoundTrip.chatBindings(UnboundProvider)("UNBOUND_API_KEY").left.value.message should include(
        "UNBOUND_API_KEY"
      )
    }
  }

  "chatSectionKey" should {

    "resolve an alias to its provider's shared key" in {
      CredentialsRoundTrip.chatSectionKey("acme-ai", Map("ACME_API_KEY" -> "shared")) shouldBe Right(Some("shared"))
    }

    "apply extra fields, so a section's own key wins" in {
      CredentialsRoundTrip.chatSectionKey(
        "acme",
        Map("ACME_API_KEY" -> "shared", "OTHER" -> "own"),
        "apiKey = ${?OTHER}"
      ) shouldBe Right(Some("own"))
    }

    "not take another vendor's variable" in {
      CredentialsRoundTrip.chatSectionKey("acme", Map("UNBOUND_API_KEY" -> "k")).isLeft shouldBe true
    }
  }

  "embeddingBindings and embeddingsKey" should {

    "resolve an embeddings block's key from the shared credential" in {
      CredentialsRoundTrip.embeddingBindings(AcmeEmbeddings, "acme-small") shouldBe
        Map("ACME_API_KEY" -> Right("key-from-ACME_API_KEY"))
      CredentialsRoundTrip.embeddingsKey("acme-embed", "acme-small", Map("ACME_API_KEY" -> "shared")) shouldBe
        Right("shared")
    }

    "report an unbound one" in {
      CredentialsRoundTrip.embeddingBindings(UnboundEmbeddings, "m")("UNBOUND_API_KEY").isLeft shouldBe true
    }
  }
