package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig, TestJwt }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class AnthropicWorkloadIdentitySpec extends AnyWordSpec with Matchers with EitherValues with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val conversation = Conversation(Seq(UserMessage("hi")))

  // A Windows path has backslashes, which a quoted HOCON string reads as escapes (`\U` is not one).
  private def hoconEscaped(value: String): String = value.replace("\\", "\\\\")

  private def section(baseUrl: String, svid: String, idTokenKey: String = "identityTokenFile") =
    s"""provider = "anthropic"
       |model = "claude-test"
       |baseUrl = "$baseUrl"
       |auth { $idTokenKey = "${hoconEscaped(
        svid
      )}", federationRuleId = "fdrl_1", organizationId = "org_1", workspaceId = "wrkspc_1" }""".stripMargin

  // On POSIX the name gets a backslash, as every Windows path has, so the HOCON escaping is exercised everywhere.
  private val svidPrefix = if java.io.File.separatorChar == '/' then "svid\\Ufile" else "svid"

  private def svidFile(jwt: String) =
    val file = Files.createTempFile(svidPrefix, ".jwt")
    file.toFile.deleteOnExit()
    Files.writeString(file, jwt)

  "an anthropic section with auth" should {
    "exchange the SVID with the jwt-bearer grant and call messages with the access token" in FakeTokenExchangeServer
      .withServer { fake =>
        val jwt    = TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com")
        val file   = svidFile(jwt)
        val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
        client.complete(conversation, CompletionOptions()).isRight shouldBe true
        val grant = fake.exchanges.head
        grant("grant_type") shouldBe "urn:ietf:params:oauth:grant-type:jwt-bearer"
        grant("assertion") shouldBe jwt
        grant("federation_rule_id") shouldBe "fdrl_1"
        grant("organization_id") shouldBe "org_1"
        fake.apiAuthorizations.last shouldBe "Bearer t1"
      }

    "refresh after a 401" in FakeTokenExchangeServer.withServer { fake =>
      val file   = svidFile(TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com"))
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.rejectNextApiCalls(1)
      val second = client.complete(conversation, CompletionOptions())
      info(
        s"second call after a forced 401: ${second.fold(_.toString.take(120), _ => "Right")}; tokens issued: ${fake.issuedTokens}; authorizations: ${fake.apiAuthorizations}"
      )
      second.isRight shouldBe true
      fake.issuedTokens.size should be >= 2
    }

    "reject a literal identityToken, since the SDK reads only a file" in {
      val body   = section("https://api.anthropic.com", "eyJ.x.y", "identityToken")
      val result = ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$body\n}")
      result.left.value.message should include("identityTokenFile")
    }

    "reject a missing federationRuleId" in {
      sectionResult(
        section("https://api.anthropic.com", "/s").replace("federationRuleId = \"fdrl_1\", ", "")
      ).isLeft shouldBe true
    }
  }
