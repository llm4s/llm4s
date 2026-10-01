package org.llm4s.testkit.fixtures

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, EmbeddingProviderConfig, ProviderConfig }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  EmbeddingRequest,
  EmbeddingResponse,
  StreamedChunk
}
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{
  EmbeddingConfigSpec,
  EmbeddingProviderDescriptor,
  Llm4sProviderModule,
  ProviderConfigSpec,
  ProviderDescriptor
}
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * A provider module written the way an external author would: a chat and an embedding
 * descriptor under one id, an alias each, a `reference.conf` binding `ACME_API_KEY`, and a
 * services entry. The testkit's own specs check it passes, and that the broken variants below
 * fail.
 */
object AcmeProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("acme")
  override val aliases: Set[String]  = Set("acme-ai")
  val DefaultBaseUrl: String         = "https://acme.invalid/v1"
  val configSpec: ProviderConfigSpec = ProviderConfigSpec.apiKeyAndDefaultBaseUrl(DefaultBaseUrl, Seq("ACME_API_KEY"))

  /** A model whose client succeeds without streaming, to prove `assertStreams` notices. */
  val NonStreamingModel: String = "acme-no-stream"

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
    yield AcmeConfig(apiKey, section.model.asString, baseUrl)

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
    ProviderDescriptor.expectConfig[AcmeConfig](id, config).map(AcmeClient(_))

final case class AcmeConfig(apiKey: String, model: String, baseUrl: String) extends ProviderConfig:
  val providerId: ProviderId                   = AcmeProvider.id
  val contextWindow: Int                       = 8192
  val reserveCompletion: Int                   = 1024
  def endpointUrl: Option[String]              = Some(baseUrl)
  def withModel(model: String): ProviderConfig = copy(model = model)
  override def toString: String                = s"AcmeConfig($model, $baseUrl)"

final case class AcmeClient(config: AcmeConfig) extends LLMClient:
  def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    Right(Completion("acme-1", 0L, "hi", config.model, AssistantMessage("hi")))

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    if config.model != AcmeProvider.NonStreamingModel then onChunk(StreamedChunk("acme-1", Some("hi")))
    complete(conversation, options)

  def getContextWindow(): Int     = config.contextWindow
  def getReserveCompletion(): Int = config.reserveCompletion

object AcmeEmbeddings extends EmbeddingProviderDescriptor:
  val id: ProviderId                = ProviderId("acme")
  override val aliases: Set[String] = Set("acme-embed")
  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some(AcmeProvider.DefaultBaseUrl),
    apiKeyEnv = Seq("ACME_API_KEY")
  )

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
    if config.model == "acme-retired" then Left(ConfigurationError("acme-retired is no longer served"))
    else
      Right(
        new EmbeddingProvider:
          def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
            Right(EmbeddingResponse(embeddings = request.input.map(_ => Vector(0.1, 0.2))))
      )

/** The well-formed module, named in the test `META-INF/services`. */
final class Llm4sAcmeModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor]               = Seq(AcmeProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(AcmeEmbeddings)

// ---- broken variants ----

/** Supplies a provider but is in no services file, so discovery cannot find it. */
final class UnlistedModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(UnlistedProvider)

object UnlistedProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("unlisted")
  val configSpec: ProviderConfigSpec = ProviderConfigSpec.apiKeyAndDefaultBaseUrl("https://unlisted.invalid")
  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] = AcmeProvider.buildConfig(providerName, section)
  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
    AcmeProvider.buildClient(config, options)

/** Lists nothing. */
final class EmptyModule extends Llm4sProviderModule

/**
 * Two listed modules each supplying a descriptor with the id `twin`: discovery keeps one, so the
 * other's descriptor is not what `twin` resolves to, and neither module is its sole supplier.
 */
class TwinProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("twin")
  val configSpec: ProviderConfigSpec = ProviderConfigSpec.apiKeyAndDefaultBaseUrl("https://twin.invalid")
  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] = AcmeProvider.buildConfig(providerName, section)
  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
    AcmeProvider.buildClient(config, options)

object TwinProviderA extends TwinProvider
object TwinProviderB extends TwinProvider

final class TwinModuleA extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(TwinProviderA)

final class TwinModuleB extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(TwinProviderB)

/**
 * Listed, but each descriptor gets something wrong: `greedy` builds a client from any config,
 * and `unbound` declares a variable no `reference.conf` binds.
 */
object GreedyProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("greedy")
  val configSpec: ProviderConfigSpec = ProviderConfigSpec.apiKeyAndDefaultBaseUrl("https://greedy.invalid")
  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] = AcmeProvider.buildConfig(providerName, section)
  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
    Right(AcmeClient(AcmeConfig("k", config.model, "https://greedy.invalid")))

object UnboundProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("unbound")
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl("https://unbound.invalid", Seq("UNBOUND_API_KEY"))
  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] = AcmeProvider.buildConfig(providerName, section)
  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
    AcmeProvider.buildClient(config, options)

object UnboundEmbeddings extends EmbeddingProviderDescriptor:
  val id: ProviderId = ProviderId("unbound")
  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://unbound.invalid"),
    apiKeyEnv = Seq("UNBOUND_API_KEY")
  )
  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = AcmeEmbeddings.build(config)

final class FlawedModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor]               = Seq(GreedyProvider, UnboundProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(UnboundEmbeddings)
