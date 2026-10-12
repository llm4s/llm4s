package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ FakeTokenExchangeServer, TestJwt }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

/**
 * `Llm4sConfig.listModels`, the path an application lists models through: it applies only section
 * validation and then hands the section to the provider's model lister, so the lister must apply the
 * rules chat applies before it exchanges the identity token or sends the access token.
 */
class OpenAICompatibleListModelsWorkloadIdentitySpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry = ProviderRegistry.default

  private def hocon(fake: FakeTokenExchangeServer, baseUrl: String): String =
    val svid = Files.createTempFile("svid", ".jwt")
    svid.toFile.deleteOnExit()
    Files.writeString(svid, TestJwt.es256("spiffe://llm4s.test/app", "databricks"))
    s"""llm4s.providers {
       |  provider = "main"
       |  main {
       |    provider = "openai-compatible"
       |    model    = "databricks-model"
       |    baseUrl  = "$baseUrl"
       |    auth {
       |      identityTokenFile = "${svid.toString.replace("\\", "\\\\")}"
       |      tokenUrl = "${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}"
       |    }
       |  }
       |}""".stripMargin

  "Llm4sConfig.listModels for a workload-identity section" should {
    "refuse a plain-http non-loopback baseUrl before the token exchange, by name and as the default" in
      FakeTokenExchangeServer.withServer { fake =>
        // Reaches the fake, so a token sent before validation would show in its records.
        val source = ReferenceConfig.withEnv(hocon(fake, s"${fake.refusedBaseUrl}/serving-endpoints"), Map.empty)
        val byName = Llm4sConfig.listModels("main", source, Llm4sHttpClient.create())
        byName.left.value shouldBe a[ConfigurationError]
        byName.left.value.message should include("baseUrl")
        Llm4sConfig.listModels(source, Llm4sHttpClient.create()).left.value shouldBe a[ConfigurationError]
        fake.exchanges shouldBe empty
        fake.apiAuthorizations shouldBe empty
      }

    "not repeat the exchanged access token, or a credential field, from a rejected listing's body" in {
      val bearer = "opaque-listing-token-3"
      val http = org.llm4s.http.MockHttpClient(
        Seq(
          org.llm4s.http.HttpResponse(200, s"""{"access_token":"$bearer","expires_in":3600}"""),
          org.llm4s.http.HttpResponse(
            401,
            s"""{"message":"token $bearer is revoked","refresh_token":"opaque-refresh-token-9"}"""
          )
        )
      )
      val section = org.llm4s.testkit.ProviderTestConfig
        .loadSection(
          "main",
          """llm4s.providers.main {
            |  provider = "openai-compatible"
            |  model    = "m"
            |  baseUrl  = "https://ws.example/serving-endpoints"
            |  auth { identityToken = "opaque-svid-literal-1", tokenUrl = "https://ws.example/oidc/v1/token" }
            |}""".stripMargin
        )
        .fold(error => fail(error.message), identity)
      val error = OpenAICompatibleModelLister.listModels(section, http).left.value
      error.message should include("revoked")
      for secret <- Seq(bearer, "opaque-refresh-token-9", "opaque-svid-literal-1") do
        (error.message should not).include(secret)
    }

    "exchange and list for a loopback baseUrl" in FakeTokenExchangeServer.withServer { fake =>
      val source = ReferenceConfig.withEnv(hocon(fake, s"${fake.baseUrl}/serving-endpoints"), Map.empty)
      Llm4sConfig.listModels("main", source, Llm4sHttpClient.create()).value.map(_.name.toString) shouldBe
        List("fake-model")
      fake.exchanges.size shouldBe 1
      fake.apiAuthorizations shouldBe Seq("Bearer t1")
    }
  }
