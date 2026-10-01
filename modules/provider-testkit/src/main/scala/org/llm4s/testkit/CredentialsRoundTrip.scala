package org.llm4s.testkit

import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, ProviderDescriptor, ProviderRegistry }
import org.llm4s.types.Result

/**
 * Proves a provider module's `reference.conf` binds what its descriptor declares.
 *
 * A descriptor's `configSpec.apiKeyEnv` names the variables its module binds to
 * `llm4s.credentials.<id>.apiKey`, and the binding itself is a `reference.conf` line: nothing but
 * a test keeps the two in step. These functions load a section or embeddings block that sets no
 * `apiKey` of its own - so the shared credential is the only place a key can come from - against
 * every `reference.conf` on the test classpath and an injected environment (the real one switched
 * off).
 *
 * [[ProviderModuleChecks.assertCredentialBindings]] wraps [[chatBindings]] as an assertion; use
 * these directly for the cases it does not cover - an alias, two variables in precedence order, a
 * provider that must ''not'' pick up a neighbour's variable.
 */
object CredentialsRoundTrip:

  /**
   * The key a chat section with no `apiKey` of its own resolves to under `env`.
   *
   * @param provider    the provider id or alias the section names
   * @param env         the environment `${?VAR}` substitutions see
   * @param extraFields further HOCON fields for the section - a required extra such as
   *                    `endpoint = "..."`, or an `apiKey = ${?OTHER_VAR}` to test precedence
   * @return `Right(Some(key))`, `Right(None)` for a provider that takes no key and was given
   *         none, or the validation error - for a required key, the one naming the variables
   */
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
    ProviderTestConfig.loadSection("roundtrip", hocon, env).map(_.apiKey.map(_.asKey))

  /**
   * For each variable `descriptor` declares, the key a section without one resolves to when only
   * that variable is set, to `key-from-<VARIABLE>`. Every entry should be
   * `Right(Some("key-from-<VARIABLE>"))`.
   *
   * @param descriptor  the chat descriptor whose `configSpec.apiKeyEnv` is checked
   * @param extraFields further HOCON fields the section needs to validate (required extras)
   */
  def chatBindings(
    descriptor: ProviderDescriptor,
    extraFields: String = ""
  )(using ProviderRegistry): Map[String, Result[Option[String]]] =
    descriptor.configSpec.apiKeyEnv.map { variable =>
      variable -> chatSectionKey(descriptor.id.asString, Map(variable -> s"key-from-$variable"), extraFields)
    }.toMap

  /**
   * The key an embeddings block with no `apiKey` of its own resolves to under `env`.
   *
   * @param provider the embedding provider id or alias, as written in `llm4s.embeddings.model`
   * @param model    the model half of `llm4s.embeddings.model`
   * @param env      the environment `${?VAR}` substitutions see
   */
  def embeddingsKey(provider: String, model: String, env: Map[String, String])(using
    ProviderRegistry
  ): Result[String] =
    ProviderTestConfig.loadEmbeddings(s"""llm4s.embeddings.model = "$provider/$model"""", env).map(_._2.apiKey)

  /**
   * [[chatBindings]] for an embedding descriptor: every entry should be
   * `Right("key-from-<VARIABLE>")`.
   *
   * @param descriptor the embedding descriptor whose `configSpec.apiKeyEnv` is checked
   * @param model      any model name the descriptor accepts
   */
  def embeddingBindings(descriptor: EmbeddingProviderDescriptor, model: String)(using
    ProviderRegistry
  ): Map[String, Result[String]] =
    descriptor.configSpec.apiKeyEnv.map { variable =>
      variable -> embeddingsKey(descriptor.id.asString, model, Map(variable -> s"key-from-$variable"))
    }.toMap
