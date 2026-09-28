package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, ProviderDescriptor, ProviderRegistry }
import org.llm4s.testutil.ReferenceConfig
import org.llm4s.types.Result

/**
 * Proves a provider module's `reference.conf` binds what its descriptor declares.
 *
 * A descriptor's `apiKeyEnv` names the variables its module binds to
 * `llm4s.credentials.<id>.apiKey`, and the binding itself is a `reference.conf` line: nothing
 * but a test keeps the two in step. Each module's `Llm4s<Name>ModuleSpec` calls these with its
 * own descriptors, against every `reference.conf` on its classpath and an injected environment
 * (the real one switched off), so a module proves its own binding - invariant 8 of #1126.
 *
 * It lives in `org.llm4s.config` for the package-private loaders, and is public so a module's
 * spec in any package can use it.
 */
object CredentialsRoundTrip:

  /** The key a chat section with no `apiKey` of its own resolves to under `env`. */
  def chatSectionKey(
    provider: String,
    env: Map[String, String],
    extraFields: String = ""
  )(using ProviderRegistry): Result[Option[String]] =
    val hocon =
      s"""llm4s.providers.roundtrip {
         |  provider = "$provider"
         |  model    = "round-trip-model"
         |  $extraFields
         |}
         |""".stripMargin
    ProvidersConfigLoader
      .loadSections(ReferenceConfig.withEnv(hocon, env))
      .flatMap(_.validated(ProviderName("roundtrip")))
      .map(_.apiKey.map(_.asKey))

  /**
   * For each variable `descriptor` declares, the key a section without one resolves to when
   * only that variable is set. Every entry should be `Right(Some(value))`.
   */
  def chatBindings(
    descriptor: ProviderDescriptor,
    extraFields: String = ""
  )(using ProviderRegistry): Map[String, Result[Option[String]]] =
    descriptor.configSpec.apiKeyEnv.map { variable =>
      variable -> chatSectionKey(descriptor.id.asString, Map(variable -> s"key-from-$variable"), extraFields)
    }.toMap

  /** The key an embeddings block with no `apiKey` of its own resolves to under `env`. */
  def embeddingsKey(provider: String, model: String, env: Map[String, String])(using
    ProviderRegistry
  ): Result[String] =
    EmbeddingsConfigLoader
      .loadProvider(ReferenceConfig.withEnv(s"""llm4s.embeddings.model = "$provider/$model"""", env))
      .map(_._2.apiKey)

  /** [[chatBindings]] for an embedding descriptor. */
  def embeddingBindings(descriptor: EmbeddingProviderDescriptor, model: String)(using
    ProviderRegistry
  ): Map[String, Result[String]] =
    descriptor.configSpec.apiKeyEnv.map { variable =>
      variable -> embeddingsKey(descriptor.id.asString, model, Map(variable -> s"key-from-$variable"))
    }.toMap
