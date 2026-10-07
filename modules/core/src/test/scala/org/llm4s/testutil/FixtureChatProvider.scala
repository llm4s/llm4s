package org.llm4s.testutil

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ DiscoveredModel, ProviderModelLister }
import org.llm4s.error.ValidationError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, ProviderTimeouts }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.llmconnect.spi.{ Llm4sProviderModule, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.types.Result

/**
 * The chat provider tests use when they need "some provider" and not a particular one.
 *
 * Core's config-loading, registry and client-construction specs need a provider that
 * resolves, validates an API key, has a default base URL, lists models and builds a client.
 * They used to borrow whichever real provider was still in core, and had to be rewritten
 * each time slice 5 of #1126 carved that provider out (#1132). This one
 * never leaves: it lives in core's test sources, and core itself ships no provider.
 *
 * It has the common shape, an API key and a default base URL, and makes no network calls:
 * its client answers every request with [[FixtureChatClient.Reply]], and its base URL is on
 * the reserved `.invalid` domain. Its model lister, [[FixtureModelLister]], reads the ids out of
 * a `GET <baseUrl>/models`, so a test drives it with a `MockHttpClient`.
 *
 * It is registered through `FixtureChatProviderModule` in core's test
 * `META-INF/services`, exactly as a provider module outside core is, so
 * `ProviderRegistry.default` resolves `provider = "fixturechat"` on core's test classpath and
 * on that of every module depending on `core % "test->test"`. Where a spec builds its own
 * registry, pass [[FixtureChatProvider]] to `ProviderRegistry.of` or `.withProvider`.
 */
object FixtureChatProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("fixturechat")

  val DefaultBaseUrl: String = "https://fixturechat.invalid/v1"

  val configSpec: ProviderConfigSpec = ProviderConfigSpec.apiKeyAndDefaultBaseUrl(DefaultBaseUrl)

  override val modelLister: Option[ProviderModelLister] =
    Some(FixtureModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- FixtureChatConfig.fromValues(section.model.asString, apiKey, baseUrl)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor.expectConfig[FixtureChatConfig](id, config).map(FixtureChatClient(_))

/**
 * The config [[FixtureChatProvider]] builds, and the "config belonging to another provider" that
 * routing specs hand a real provider's factory. A module spec proves its descriptor refuses one
 * with `llm4s-provider-testkit`'s `assertRefusesForeignConfig` instead.
 */
final case class FixtureChatConfig(
  apiKey: String,
  model: String,
  baseUrl: String = FixtureChatProvider.DefaultBaseUrl,
  contextWindow: Int = 8192,
  reserveCompletion: Int = 1024,
  override val timeouts: ProviderTimeouts = ProviderTimeouts.default
) extends ProviderConfig:
  override val providerId: ProviderId                      = FixtureChatProvider.id
  override def endpointUrl: Option[String]                 = Some(baseUrl)
  override def withModel(model: String): FixtureChatConfig = copy(model = model)
  override def withTimeouts(timeouts: ProviderTimeouts): FixtureChatConfig = copy(timeouts = timeouts)

object FixtureChatConfig:

  /** Validates the fields a real provider's `fromValues` does, and nothing else. */
  def fromValues(model: String, apiKey: String, baseUrl: String): Result[FixtureChatConfig] =
    for
      _ <- ProviderConfig.nonEmpty("FixtureChat", "apiKey", apiKey)
      _ <- ProviderConfig.nonEmpty("FixtureChat", "baseUrl", baseUrl)
    yield FixtureChatConfig(apiKey, model, baseUrl)

/** The client [[FixtureChatProvider]] builds: a canned reply, and no network. */
final case class FixtureChatClient(config: FixtureChatConfig) extends LLMClient:

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    Right(
      Completion(
        id = "fixturechat-completion",
        created = 0L,
        content = FixtureChatClient.Reply,
        model = config.model,
        message = AssistantMessage(FixtureChatClient.Reply)
      )
    )

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    complete(conversation, options)

  override def getContextWindow(): Int     = config.contextWindow
  override def getReserveCompletion(): Int = config.reserveCompletion

object FixtureChatClient:
  val Reply: String = "fixture reply"

/** The services entry point for [[FixtureChatProvider]]; a `class`, as `ServiceLoader` requires. */
final class FixtureChatProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(FixtureChatProvider)

/**
 * [[FixtureChatProvider]]'s model lister: `GET <baseUrl>/models` through the HTTP client it is
 * given, and the `id` of each entry under `data`.
 *
 * Core holds no real lister - the OpenAI-format factory, `ProviderModelListers.openAICompatible`,
 * is in `llm4s-openai-compatible` - so this is just enough for core's specs to prove that
 * `Llm4sConfig.listModels` resolves a section, reaches its descriptor's lister and passes the
 * HTTP client through. Tests hand it a `MockHttpClient`; it never touches the network.
 */
object FixtureModelLister extends ProviderModelLister:
  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    for
      section  <- config.requireProvider(FixtureChatProvider.id)
      _        <- section.requireApiKey
      response <- httpClient.get(s"${section.baseUrlOrDefault(FixtureChatProvider.DefaultBaseUrl).asUrl}/models")
      ids <- scala.util
        .Try(ujson.read(response.body)("data").arr.toList.flatMap(_.obj.get("id").flatMap(_.strOpt)))
        .toOption
        .toRight(ValidationError("data", "Missing or invalid models payload"))
    yield ids.map(id => DiscoveredModel(ModelName(id), FixtureChatProvider.id))
