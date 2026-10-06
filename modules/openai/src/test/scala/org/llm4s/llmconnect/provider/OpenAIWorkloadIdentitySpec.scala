package org.llm4s.llmconnect.provider

import com.openai.auth.SubjectTokenType
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.llmconnect.config.{ OpenAIConfig, OpenAIWorkloadIdentity }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.util.concurrent.CompletionException
import scala.util.Try

class OpenAIWorkloadIdentitySpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with OptionValues
    with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val section =
    """provider = "openai"
      |model = "gpt-4o-mini"
      |auth { identityTokenFile = "/var/run/svid", identityProviderId = "idp_1", serviceAccountId = "sa_1", clientId = "c_1" }""".stripMargin

  "an openai section with auth" should {
    "build an OpenAIConfig carrying the workload identity and no key" in {
      val config = ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$section\n}").value
      val openai = config.asInstanceOf[OpenAIConfig]
      openai.apiKey shouldBe ""
      openai.workloadIdentity.value shouldBe OpenAIWorkloadIdentity(
        IdentitySource.File(Path.of("/var/run/svid").toAbsolutePath),
        "idp_1",
        "sa_1",
        Some("c_1")
      )
      openai.toString should include("workloadIdentity=Some(OpenAIWorkloadIdentity(")
      for id <- Seq("idp_1", "sa_1", "c_1") do (openai.toString should not).include(id)
    }

    "build a client" in {
      assertBuildsClient(OpenAIProvider, sectionOf(section))
    }

    "map to the SDK's WorkloadIdentity with a JWT subject token read from the file" in {
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, "eyJ.svid.sig\n")
      val sdk = OpenAIClientTransport.sdkWorkloadIdentity(
        OpenAIWorkloadIdentity(IdentitySource.File(file), "idp_1", "sa_1", Some("c_1"))
      )
      sdk.identityProviderId() shouldBe "idp_1"
      sdk.serviceAccountId() shouldBe "sa_1"
      sdk.clientId() shouldBe "c_1"
      sdk.provider().tokenType() shouldBe SubjectTokenType.JWT
      sdk.provider().getToken(null, null) shouldBe "eyJ.svid.sig"
    }

    "reread the file on each call, so a rotated SVID is picked up" in {
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, "first")
      val provider = OpenAIClientTransport
        .sdkWorkloadIdentity(OpenAIWorkloadIdentity(IdentitySource.File(file), "i", "s"))
        .provider()
      provider.getToken(null, null) shouldBe "first"
      Files.writeString(file, "second")
      provider.getToken(null, null) shouldBe "second"
    }

    "fail a request, without touching the network, when the identity token file is missing" in {
      val missing = Files.createTempFile("svid", ".jwt")
      Files.delete(missing)
      // Forward slashes: a Windows path's backslashes would be read as escapes inside the quoted HOCON string.
      val body  = section.replace("/var/run/svid", missing.toString.replace('\\', '/'))
      val c     = assertBuildsClient(OpenAIProvider, sectionOf(body))
      val error = c.complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions()).left.value
      error shouldBe an[AuthenticationError]
      error.message should include("workload-identity")
    }

    "keep a missing identity token an AuthenticationError through the SDK's async path and its wrapping" in {
      val missing = Files.createTempFile("svid", ".jwt")
      Files.delete(missing)
      val provider = OpenAIClientTransport
        .sdkWorkloadIdentity(OpenAIWorkloadIdentity(IdentitySource.File(missing), "i", "s"))
        .provider()
      val failure = Try(provider.getTokenAsync(null, null).join()).failed.get
      failure shouldBe a[CompletionException]
      OpenAIClient.mapError(new IllegalStateException("token refresh failed", failure), "openai") shouldBe
        an[AuthenticationError]
    }

    "read the identity token for the async path off the common pool" in {
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, "eyJ.svid.sig")
      val provider = OpenAIClientTransport
        .sdkWorkloadIdentity(OpenAIWorkloadIdentity(IdentitySource.File(file), "i", "s"))
        .provider()
      provider.getTokenAsync(null, null).join() shouldBe "eyJ.svid.sig"
    }

    "refuse to list models, naming the reason, rather than fail for a missing apiKey" in {
      val error = org.llm4s.config.OpenAIModelLister
        .listModels(sectionOf(section), org.llm4s.http.Llm4sHttpClient.create())
        .left
        .value
      error shouldBe a[ConfigurationError]
      error.message should include("model listing is not supported with workload identity auth for openai")
    }

    "reject a missing serviceAccountId" in {
      sectionResult(section.replace("serviceAccountId = \"sa_1\", ", "")).isLeft shouldBe true
    }

    "reject neither apiKey nor auth as before" in {
      sectionResult(
        """provider = "openai"
                      |model = "m"
                      |apiKey = "sk-x"
                      |auth { identityTokenFile = "/s", identityProviderId = "i", serviceAccountId = "s" }""".stripMargin
      ).isLeft shouldBe true
    }
  }

  "auth on azure or requesty" should {
    "be rejected" in {
      for provider <- Seq("azure", "requesty") do
        sectionResult(
          s"""provider = "$provider"
             |model = "m"
             |auth { identityTokenFile = "/s" }""".stripMargin
        ).isLeft shouldBe true
    }
  }
