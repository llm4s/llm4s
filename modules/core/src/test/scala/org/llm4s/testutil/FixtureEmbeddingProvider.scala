package org.llm4s.testutil

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor, Llm4sProviderModule }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * The embedding provider tests use when they need "some embedding provider" and not a
 * particular one - the embedding counterpart of [[FixtureChatProvider]].
 *
 * Core's embedding config, registry and client specs need a provider that resolves, has an
 * alias, requires an API key, has a default base URL, names its environment variables in
 * errors and declares model dimensions. They borrowed Voyage for that, and had to change when
 * Voyage moved to `llm4s-voyage` (#1132). This one never leaves: it lives in core's test
 * sources.
 *
 * It has Voyage's shape - embeddings and no chat client, an API key and a default base URL -
 * and makes no network calls: its provider answers every request with one
 * [[FixtureEmbeddingProvider.Dimensions]]-long vector per input, and its base URL is on the
 * reserved `.invalid` domain. Its config lives under `llm4s.embeddings.fixtureembedding`,
 * which no `reference.conf` binds, so a spec supplies the block it needs.
 *
 * It is registered through [[FixtureEmbeddingProviderModule]] in core's test
 * `META-INF/services`, so `ProviderRegistry.default` resolves
 * `EMBEDDING_MODEL=fixtureembedding/<model>` on core's test classpath and on that of every
 * module depending on `core % "test->test"`.
 */
object FixtureEmbeddingProvider extends EmbeddingProviderDescriptor:
  val id: ProviderId = ProviderId("fixtureembedding")

  /** An alias, as `voyageai` is Voyage's. */
  val Alias: String = "fixture-embedding-ai"

  val DefaultBaseUrl: String = "https://fixtureembedding.invalid/v1"

  val ApiKeyEnv: String = "FIXTURE_EMBEDDING_API_KEY"
  val ModelEnv: String  = "FIXTURE_EMBEDDING_MODEL"

  /** The dimensions of every vector the fixture's provider returns. */
  val Dimensions: Int = 4

  override val aliases: Set[String] = Set(Alias)

  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some(DefaultBaseUrl),
    apiKeyEnv = Seq(ApiKeyEnv),
    modelEnv = Some(ModelEnv)
  )

  override val modelDimensions: Map[String, Int] = Map(
    "fixture-embed-small" -> 256,
    "fixture-embed-large" -> 1024
  )

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
    Right(
      new EmbeddingProvider:
        def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
          Right(
            EmbeddingResponse(
              embeddings = request.input.map(_ => Vector.fill(Dimensions)(0.5)),
              metadata = Map("provider" -> id.asString, "model" -> config.model, "baseUrl" -> config.baseUrl)
            )
          )
    )

/** The services entry point for [[FixtureEmbeddingProvider]]; a `class`, as `ServiceLoader` requires. */
final class FixtureEmbeddingProviderModule extends Llm4sProviderModule:
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(FixtureEmbeddingProvider)
