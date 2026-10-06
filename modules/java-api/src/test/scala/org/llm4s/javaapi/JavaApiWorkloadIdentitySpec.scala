package org.llm4s.javaapi

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.{ IdentitySource, TokenExchangeConfig }
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.testkit.FakeTokenExchangeServer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The Java facade builds clients through `LLMConnect.getClient`, so a workload-identity config the
 * provider's rules refuse fails there, before the client exists - no exchange, no request.
 */
class JavaApiWorkloadIdentitySpec extends AnyWordSpec with Matchers {

  private def exchanging(fake: FakeTokenExchangeServer, baseUrl: String): OpenAICompatibleConfig =
    OpenAICompatibleConfig("m", baseUrl).withTokenExchange(
      TokenExchangeConfig(
        IdentitySource.Literal("eyJ.svid.sig"),
        s"${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}"
      )
    )

  "Llm4s.createClient with an exchanging config" should {
    "fail for a plain-http baseUrl to a host the rules refuse, before any request" in
      FakeTokenExchangeServer.withServer { fake =>
        // `refusedBaseUrl` reaches the fake, so a request sent before the refusal would show in its records.
        val result = Llm4s.createClient(exchanging(fake, s"${fake.refusedBaseUrl}/serving-endpoints"))
        result.isFailure shouldBe true
        result.getError().error shouldBe a[ConfigurationError]
        fake.exchanges shouldBe empty
        fake.apiAuthorizations shouldBe empty
      }

    "build a client for a loopback baseUrl" in FakeTokenExchangeServer.withServer { fake =>
      val result = Llm4s.createClient(exchanging(fake, s"${fake.baseUrl}/serving-endpoints"))
      result.isSuccess shouldBe true
      result.get().close()
    }
  }
}
