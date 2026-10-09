package org.llm4s.llmconnect.provider

import org.llm4s.config.BedrockConfigKeys.*
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ BedrockConfig, BedrockCredentials }
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.LocalProviderTestServer.{ holdOpen, sendJsonResponse, withServer }
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-bedrock` registers itself, and what it registers works: the services entry is found, the
 * descriptor arrives, nothing else supplies `bedrock`, and a section builds a client that streams
 * and honours cancellation. The checks are `llm4s-provider-testkit`'s, the ones a provider module
 * outside this repository uses.
 */
class Llm4sBedrockModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private val section: NamedProviderConfig =
    NamedProviderConfig(
      provider = BedrockProvider.id,
      model = ModelName("anthropic.claude-3-5-sonnet-20241022-v2:0"),
      baseUrl = None,
      apiKey = None
    ).withExtras(Map(REGION_KEY -> "eu-west-1"))

  private def clientAt(url: String): LLMClient =
    assertBuildsClient(
      BedrockProvider,
      section
        .withBaseUrl(Some(BaseUrl(url)))
        .withExtras(
          Map(REGION_KEY -> "us-east-1", ACCESS_KEY_ID_KEY -> "AKIDEXAMPLE", SECRET_ACCESS_KEY_KEY -> "secret")
        )
    )

  "the llm4s-bedrock services entry" should {

    "be discovered, the only supplier of bedrock, and registrable explicitly" in {
      assertModule(new Llm4sBedrockModule)
    }

    "contribute no embedding provider" in {
      new Llm4sBedrockModule().embeddingProviders shouldBe empty
      ProviderRegistry.default.findEmbedding(ProviderId("bedrock")) shouldBe None
    }
  }

  "the llm4s-bedrock descriptor" should {

    "declare streaming and tool calling, which the client implements" in {
      BedrockProvider.features.streaming shouldBe true
      BedrockProvider.features.toolCalling shouldBe true
    }

    "require a region and never default one" in {
      BedrockProvider.configSpec.extra(REGION_KEY).value.required shouldBe true
      BedrockProvider.configSpec.extra(REGION_KEY).value.default shouldBe None
    }

    "build a BedrockClient from a section" in {
      assertBuildsClient(BedrockProvider, section).getClass.getSimpleName shouldBe "BedrockClient"
    }

    "refuse a config belonging to another provider" in {
      assertRefusesForeignConfig(BedrockProvider)
    }

    "bind no vendor API key, so its credential round trip is vacuous but must still pass" in {
      BedrockProvider.configSpec.apiKeyEnv shouldBe empty
      assertCredentialBindings(BedrockProvider, extraFields = """region = "us-east-1"""")
    }
  }

  "a bedrock section" should {

    given ProviderRegistry = ProviderRegistry.default

    def load(fields: String, env: Map[String, String] = Map.empty) =
      ProviderTestConfig.loadProvider(
        "claude",
        s"""llm4s.providers.claude { provider = "bedrock", model = "anthropic.claude-3-5-sonnet-20241022-v2:0", $fields }""",
        env
      )

    "load a BedrockConfig with the region, resolving the context window from the model registry" in {
      val config = load("""region = "us-east-1"""").toOption.value.asInstanceOf[BedrockConfig]
      config.region shouldBe "us-east-1"
      config.credentials shouldBe None
      config.contextWindow should be > 8192 // the registry's figure, not the unknown-model default
    }

    "read the region from AWS_REGION when the section binds it" in {
      val config = load("region = ${?AWS_REGION}", Map("AWS_REGION" -> "ap-southeast-2")).toOption.value
        .asInstanceOf[BedrockConfig]
      config.region shouldBe "ap-southeast-2"
    }

    "fail naming the region key and the AWS_REGION binding when no region is set" in {
      val message = load("").left.toOption.value.message
      message should include("region")
      message should include("AWS_REGION")
    }

    "carry explicit credentials with the session token" in {
      val config = load(
        """region = "us-east-1", accessKeyId = "AKIDEXAMPLE", secretAccessKey = "secret", sessionToken = "tok""""
      ).toOption.value.asInstanceOf[BedrockConfig]
      config.credentials shouldBe Some(BedrockCredentials("AKIDEXAMPLE", "secret", Some("tok")))
    }

    "carry the endpoint override from baseUrl and the profile" in {
      val config =
        load("""region = "us-east-1", baseUrl = "https://vpce.example.com", profile = "work"""").toOption.value
          .asInstanceOf[BedrockConfig]
      config.endpointUrl shouldBe Some("https://vpce.example.com")
      config.profile shouldBe Some("work")
    }

    "refuse an access key without its secret, a lone session token, and credentials plus a profile" in {
      load("""region = "us-east-1", accessKeyId = "AKIDEXAMPLE"""").left.toOption.value.message should include(
        "together"
      )
      load("""region = "us-east-1", sessionToken = "tok"""").left.toOption.value.message should include(
        SESSION_TOKEN_KEY
      )
      load(
        """region = "us-east-1", accessKeyId = "a", secretAccessKey = "b", profile = "work""""
      ).left.toOption.value.message should include(
        "not both"
      )
    }
  }

  "a client built by the bedrock descriptor" should {

    "actually stream, not silently fall back to complete()" in {
      withServer("/")(sendEventStream(_, textStream("Hi", "!")))(url => assertStreams(clientAt(url)))
    }

    "return CancelledError when a call is interrupted" in {
      withServer("/")(holdOpen)(url => assertCancelsWhenInterrupted(clientAt(url)))
    }

    "return CancelledError when a stream is interrupted after its first event" in {
      withServer("/")(streamFramesThenHold(_, Seq(textDelta("first")))) { url =>
        assertCancelsStreamWhenInterrupted(clientAt(url))
      }
    }

    "complete a request" in {
      withServer("/")(sendJsonResponse(_, 200, converseResponse("pong"))) { url =>
        clientAt(url)
          .complete(org.llm4s.llmconnect.model.Conversation(Seq(org.llm4s.llmconnect.model.UserMessage("ping"))))
          .toOption
          .value
          .content shouldBe "pong"
      }
    }
  }
