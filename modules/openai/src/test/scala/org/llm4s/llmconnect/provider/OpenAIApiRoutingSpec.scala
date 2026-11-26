package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIApi, OpenAIConfig }
import org.llm4s.model.{ ModelRegistryService, ModelRegistryTestSupport }
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

final class OpenAIApiRoutingSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val registry: ModelRegistryService = ModelRegistryTestSupport.defaultService()

  private val openai   = ProviderId("openai")
  private val requesty = ProviderId("requesty")
  private val gateway  = BaseUrl("https://gateway.example/v1")

  private def config(model: String, baseUrl: BaseUrl = OpenAIConfig.DEFAULT_BASE_URL): OpenAIConfig =
    OpenAIConfig(Secret("sk-test"), ModelName(model)).withBaseUrl(baseUrl)

  private def resolve(
    cfg: org.llm4s.llmconnect.config.ProviderConfig,
    provider: ProviderId = openai,
    reg: ModelRegistryService = registry
  ): OpenAIApiRouting.Choice =
    OpenAIApiRouting.resolve(cfg, provider, cfg.model.asString, reg)

  private val azure = AzureConfig(
    BaseUrl("https://x.openai.azure.com"),
    Secret("k"),
    ModelName("gpt-4o"),
    "2025-01-01-preview",
    128000,
    4096
  )

  private def requestyConfig(model: String): OpenAIConfig =
    config(model, BaseUrl("https://router.requesty.ai/v1")).withExplicitProviderId(requesty)

  // In the registry (the bundled data with llm4s's patches), gpt-4o, gpt-4o-mini, gpt-4.1, gpt-5, gpt-6.1-terra and o3
  // list /v1/responses; gpt-4-turbo, o4-mini and gpt-4o-search-preview list no endpoints; gpt-audio-1.5 lists only
  // /v1/chat/completions, and the gpt-audio models list other endpoints but not /v1/responses.
  "OpenAIApiRouting" should "use Responses on OpenAI's own base URL for a model the registry lists for /v1/responses" in {
    Seq("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-5", "gpt-6.1-terra", "o3").foreach { model =>
      withClue(model)(resolve(config(model)).api shouldBe OpenAIApi.Responses)
    }
  }

  it should "use Chat Completions on OpenAI's own base URL for a known model the registry does not list for /v1/responses" in {
    Seq("gpt-4-turbo", "o4-mini", "gpt-4o-search-preview", "gpt-audio-1.5", "gpt-audio").foreach { model =>
      withClue(model)(resolve(config(model)).api shouldBe OpenAIApi.ChatCompletions)
    }
  }

  it should "use Chat Completions for an openai client on any other base URL" in {
    resolve(config("gpt-4o", gateway)).api shouldBe OpenAIApi.ChatCompletions
    resolve(config("gpt-4o", BaseUrl("http://127.0.0.1:8080/v1"))).api shouldBe OpenAIApi.ChatCompletions
  }

  it should "do what the config says, either way" in {
    resolve(config("gpt-4o").withApi(OpenAIApi.ChatCompletions)).api shouldBe OpenAIApi.ChatCompletions
    resolve(config("gpt-4o", gateway).withApi(OpenAIApi.Responses)).api shouldBe OpenAIApi.Responses
    resolve(requestyConfig("gpt-4o").withApi(OpenAIApi.Responses), requesty).api shouldBe OpenAIApi.Responses
    // an explicit setting beats the registry, both ways
    resolve(config("o1-pro").withApi(OpenAIApi.ChatCompletions)).api shouldBe OpenAIApi.ChatCompletions
    resolve(config("gpt-audio-1.5").withApi(OpenAIApi.Responses)).api shouldBe OpenAIApi.Responses
    resolve(config("gpt-4o").withApi(OpenAIApi.Responses)).api shouldBe OpenAIApi.Responses
  }

  it should "use Chat Completions for a hand-built config whose provider id is not openai" in {
    val openRouter = config("openai/o1-pro", BaseUrl("https://openrouter.ai/api/v1"))
    openRouter.providerId shouldBe ProviderId("openrouter")
    resolve(openRouter, openai).api shouldBe OpenAIApi.ChatCompletions
    resolve(openRouter, openai).reason should include("openrouter")
  }

  it should "use Chat Completions for Azure and for Requesty" in {
    resolve(azure, ProviderId("azure")).api shouldBe OpenAIApi.ChatCompletions
    resolve(requestyConfig("gpt-4o"), requesty).api shouldBe OpenAIApi.ChatCompletions
    // Requesty stays on Chat Completions even for a responses-only model, and even if pointed at OpenAI's URL
    resolve(requestyConfig("o1-pro"), requesty).api shouldBe OpenAIApi.ChatCompletions
    resolve(config("gpt-4o").withExplicitProviderId(requesty), requesty).api shouldBe OpenAIApi.ChatCompletions
  }

  it should "use Responses for a responses-only model wherever the client points" in {
    resolve(config("o1-pro", gateway)).api shouldBe OpenAIApi.Responses
    resolve(config("o1-pro")).api shouldBe OpenAIApi.Responses
  }

  it should "use Chat Completions for a model whose endpoints do not include /v1/responses" in {
    resolve(config("gpt-audio-1.5")).api shouldBe OpenAIApi.ChatCompletions
    // and for one that lists no endpoints at all: known, but not listed for /v1/responses
    resolve(config("gpt-4o-search-preview")).api shouldBe OpenAIApi.ChatCompletions
  }

  it should "use Responses for a model the registry does not know, a fine-tune of an unknown base among them" in {
    resolve(config("ft:gpt-9-nova:acme::abc")).api shouldBe OpenAIApi.Responses
    resolve(config("gpt-9-nova-2031")).api shouldBe OpenAIApi.Responses
    // and Chat Completions for it elsewhere: an unknown model is not responses-only
    resolve(config("ft:gpt-9-nova:acme::abc", gateway)).api shouldBe OpenAIApi.ChatCompletions
  }

  it should "route a fine-tune by the registry's ft: entry for its base, which lists no endpoints" in {
    val choice = resolve(config("ft:gpt-4o-mini-2024-07-18:acme::x"))
    choice.api shouldBe OpenAIApi.ChatCompletions
    choice.reason should include("ft:gpt-4o-mini-2024-07-18")
    resolve(config("openai/ft:gpt-4.1-2025-04-14:acme:support:9AbC")).api shouldBe OpenAIApi.ChatCompletions
  }

  it should "route a fine-tune the registry has no ft: entry for by its base model" in {
    // no ft:gpt-5-mini-2025-08-07 entry; the base lists /v1/responses
    val listed = resolve(config("ft:gpt-5-mini-2025-08-07:acme::x"))
    listed.api shouldBe OpenAIApi.Responses
    listed.reason should include("the model registry lists")
    listed.reason should include("gpt-5-mini-2025-08-07")
    // no ft:gpt-4o-2024-05-13 entry; the base lists no endpoints
    resolve(config("ft:gpt-4o-2024-05-13:acme::x")).api shouldBe OpenAIApi.ChatCompletions
  }

  it should "look a model up ignoring case and a router prefix" in {
    resolve(config("GPT-6.1-TERRA")).api shouldBe OpenAIApi.Responses
    resolve(config("openai/gpt-4.1")).api shouldBe OpenAIApi.Responses
    resolve(config("openai/gpt-4-turbo")).api shouldBe OpenAIApi.ChatCompletions
    // a prefixed responses-only model is responses-only behind a gateway too
    resolve(config("openai/o1-pro", gateway)).api shouldBe OpenAIApi.Responses
  }

  it should "treat a registry that fails as one that does not know the model" in {
    val failing = new ModelRegistryService:
      private def boom                                               = throw new IllegalStateException("registry down")
      def findByCapability(capability: String)                       = boom
      def listProviders()                                            = boom
      def listByMode(mode: org.llm4s.model.ModelMode)                = boom
      def listByProvider(provider: ProviderId)                       = boom
      def lookup(modelId: ModelName)                                 = boom
      def lookup(provider: ProviderId, modelName: ModelName)         = boom
      def search(query: String): List[org.llm4s.model.ModelMetadata] = boom
    resolve(config("gpt-4o"), reg = failing).api shouldBe OpenAIApi.Responses
    resolve(config("gpt-4o", gateway), reg = failing).api shouldBe OpenAIApi.ChatCompletions
  }

  it should "follow a registry override" in {
    val file = Files.createTempFile("llm4s-registry-override", ".json")
    file.toFile.deleteOnExit()
    Files.writeString(
      file,
      """{"gpt-4o": {"litellm_provider": "openai", "mode": "responses",
        |  "supported_endpoints": ["/v1/responses"], "max_input_tokens": 128000}}""".stripMargin,
      StandardCharsets.UTF_8
    ): Unit
    val overridden = ModelRegistryService.fromFile(file.toString).value
    // the bundled entry: Chat Completions on a gateway; the override: Responses
    resolve(config("gpt-4o", gateway)).api shouldBe OpenAIApi.ChatCompletions
    resolve(config("gpt-4o", gateway), reg = overridden).api shouldBe OpenAIApi.Responses
    Files.deleteIfExists(file): Unit
  }

  it should "follow a registry override that lists a model for /v1/responses" in {
    val file = Files.createTempFile("llm4s-registry-override", ".json")
    file.toFile.deleteOnExit()
    Files.writeString(
      file,
      """{"gpt-4-turbo": {"litellm_provider": "openai", "mode": "chat",
        |  "supported_endpoints": ["/v1/chat/completions", "/v1/responses"], "max_input_tokens": 128000}}""".stripMargin,
      StandardCharsets.UTF_8
    ): Unit
    val overridden = ModelRegistryService.fromFile(file.toString).value
    // the bundled entry lists no endpoints: Chat Completions; the override opts gpt-4-turbo in
    resolve(config("gpt-4-turbo")).api shouldBe OpenAIApi.ChatCompletions
    resolve(config("gpt-4-turbo"), reg = overridden).api shouldBe OpenAIApi.Responses
    Files.deleteIfExists(file): Unit
  }

  it should "recognise OpenAI's base URL however it is written" in {
    Seq(
      "https://api.openai.com/v1",
      "https://api.openai.com/v1/",
      "https://API.OpenAI.com/v1",
      "https://api.openai.com:443/v1"
    ).foreach(u => withClue(u)(OpenAIApiRouting.isOpenAI(BaseUrl(u)) shouldBe true))
    Seq(
      "https://api.openai.com.evil.example/v1",
      "http://api.openai.com/v1",
      "https://api.openai.com:8443/v1",
      "https://user@api.openai.com/v1",
      "not a url",
      ""
    ).foreach(u => withClue(u)(OpenAIApiRouting.isOpenAI(BaseUrl(u)) shouldBe false))
  }

  it should "say which rule applied" in {
    resolve(azure, ProviderId("azure")).reason should include("Chat Completions only")
    resolve(config("gpt-4o").withApi(OpenAIApi.ChatCompletions)).reason should include("api = chat-completions")
    resolve(config("gpt-4o", gateway).withApi(OpenAIApi.Responses)).reason should include("api = responses")
    resolve(requestyConfig("gpt-4o"), requesty).reason should include("requesty")
    resolve(config("o1-pro", gateway)).reason should include("o1-pro is served only on the Responses API")
    resolve(config("gpt-4o", gateway)).reason should include("not OpenAI's (https://gateway.example/v1)")
    resolve(config("gpt-4-turbo")).reason shouldBe
      "the model registry does not list gpt-4-turbo for /v1/responses; set api = responses to use it there"
    resolve(config("gpt-6.1-terra")).reason shouldBe "the model registry lists gpt-6.1-terra for /v1/responses"
    resolve(config("gpt-9-nova-2031")).reason shouldBe
      "the model registry does not know gpt-9-nova-2031, and this is OpenAI's own API"
  }

  it should "never repeat a base URL's user info, query or fragment in its reason" in {
    val reason = resolve(config("gpt-4o", BaseUrl("https://alice:s3cret@gateway.example:8443/v1?key=k1#frag"))).reason
    reason should include("https://gateway.example:8443/v1")
    (reason should not).include("alice")
    (reason should not).include("s3cret")
    (reason should not).include("k1")
    (reason should not).include("frag")
    val unparseable = resolve(config("gpt-4o", BaseUrl("https://alice:s3cret@exa mple/v1"))).reason
    (unparseable should not).include("s3cret")
    (unparseable should not).include("alice")
  }
