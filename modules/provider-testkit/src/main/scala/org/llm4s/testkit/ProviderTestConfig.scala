package org.llm4s.testkit

import org.llm4s.config.{ Llm4sConfig, ReferenceConfig }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result

/**
 * Loads provider configuration the way an application does, with the environment supplied by
 * the test.
 *
 * Each method reads `hocon` - what the application would write in its `application.conf` - over
 * every `reference.conf` on the test classpath, your module's included, and resolves `${?VAR}`
 * substitutions against `env` alone. The real process environment is switched off, so a
 * developer machine with `ACME_API_KEY` exported cannot make a test pass that fails in CI, and a
 * test can prove your `reference.conf` binds a variable without setting it.
 *
 * {{{
 * given ProviderRegistry = ProviderRegistry.default
 *
 * ProviderTestConfig.loadProvider(
 *   "acme-main",
 *   """llm4s.providers.acme-main { provider = "acme", model = "acme-large" }""",
 *   Map("ACME_API_KEY" -> "test-key")
 * ) // Right(AcmeConfig(...)), the key taken from llm4s.credentials.acme.apiKey
 * }}}
 *
 * The provider a section names is resolved through the `ProviderRegistry` in scope:
 * `ProviderRegistry.default` for what discovery finds on the classpath, or
 * `ProviderRegistry.ofModules(new MyModule)` to pin it.
 */
object ProviderTestConfig:

  /**
   * The chat section `llm4s.providers.<name>` as validation leaves it, before your descriptor
   * sees it: the key resolved (the section's own `apiKey`, else
   * `llm4s.credentials.<provider id>.apiKey`), extras defaulted, aliases canonicalised. Only
   * this section is validated.
   *
   * @param name  the section name under `llm4s.providers`
   * @param hocon the application config; may hold other sections, which are not validated
   * @param env   the environment `${?VAR}` substitutions see
   */
  def loadSection(name: String, hocon: String, env: Map[String, String] = Map.empty)(using
    ProviderRegistry
  ): Result[NamedProviderConfig] =
    Llm4sConfig.providerSection(ReferenceConfig.withEnv(hocon, env), name)

  /**
   * The `ProviderConfig` your descriptor builds from `llm4s.providers.<name>` - the full path
   * `Llm4sConfig.provider(name)` takes in an application, model registry included.
   *
   * @param name  the section name under `llm4s.providers`
   * @param hocon the application config
   * @param env   the environment `${?VAR}` substitutions see
   */
  def loadProvider(name: String, hocon: String, env: Map[String, String] = Map.empty)(using
    ProviderRegistry
  ): Result[ProviderConfig] =
    Llm4sConfig.provider(ReferenceConfig.withEnv(hocon, env), name)

  /**
   * The embedding provider `llm4s.embeddings.model` selects, and the config its descriptor
   * builds from `llm4s.embeddings.<id>` - what `Llm4sConfig.embeddings()` returns in an
   * application.
   *
   * @param hocon the application config; must set `llm4s.embeddings.model = "<provider>/<model>"`
   * @param env   the environment `${?VAR}` substitutions see
   * @return the canonical provider id and its config
   */
  def loadEmbeddings(hocon: String, env: Map[String, String] = Map.empty)(using
    ProviderRegistry
  ): Result[(String, EmbeddingProviderConfig)] =
    Llm4sConfig.embeddings(ReferenceConfig.withEnv(hocon, env))
