package org.llm4s.llmconnect.spi

/**
 * A provider-specific key in a named provider section: one the fixed fields of
 * `NamedProviderConfig` do not cover.
 *
 * Declaring the key is what makes it part of the section. The section validator
 * checks required keys, fills in defaults and resolves deprecated aliases before
 * the descriptor sees the section, and the descriptor reads the result from
 * `NamedProviderConfig.extras`. A key in a section that no descriptor declares
 * is reported as unknown and not passed on.
 *
 * Values are strings, as they are in HOCON; a descriptor that needs a number or a
 * boolean parses it in `buildConfig` and reports a malformed value there.
 *
 * @param name              the key as written in `llm4s.providers.<name>`, e.g. `"region"`.
 *                          Must not be one of [[ProviderConfigSpec.BuiltinKeys]].
 * @param description       what the key means, shown when a required one is missing -
 *                          e.g. `"the GCP project ID that owns your Vertex AI resources"`.
 * @param required          a section without this key (and without a default) is invalid.
 * @param default           value used when the section omits the key. A key with a default
 *                          is never missing, so `required` is then moot.
 * @param env               an environment variable that llm4s itself reads for this key, if
 *                          one is: named in the missing-key message. Leave it `None` unless the
 *                          variable really is read - named sections only see what the user's
 *                          config sets, so most keys have none.
 * @param deprecatedAliases older names for this key, oldest spelling first. A section that
 *                          sets an alias instead of `name` still works, with a deprecation
 *                          warning; one that sets both to different values is an error. An
 *                          alias may be a built-in field name, which is how a provider moves off
 *                          a repurposed field (Vertex AI's `endpoint` became `project`).
 */
final case class ProviderConfigKey(
  name: String,
  description: String,
  required: Boolean = false,
  default: Option[String] = None,
  env: Option[String] = None,
  deprecatedAliases: Seq[String] = Seq.empty
)

object ProviderConfigKey:

  /** A key the section must set. */
  def required(name: String, description: String): ProviderConfigKey =
    ProviderConfigKey(name, description, required = true)

  /** A key the section may set. */
  def optional(name: String, description: String, default: Option[String] = None): ProviderConfigKey =
    ProviderConfigKey(name, description, default = default)

/**
 * The shape of a provider's `llm4s.providers.<name>` section: which fields it
 * requires, which provider-specific keys it accepts, and what to tell the user
 * when one is missing.
 *
 * This replaces the per-provider `NamedProviderValidator` implementations that
 * `llm4s-core` used to hold, one object per provider in a shared file. A
 * provider now declares its own requirements, and a single generic validator
 * turns that declaration into the same error messages as before.
 *
 * == Why defaults live here rather than in HOCON ==
 * Chat-provider config is keyed by the user's ''instance'' name
 * (`llm4s.providers.my-openai.baseUrl`), so a `reference.conf` fragment shipped
 * by a provider module cannot express "the default `baseUrl` for anything whose
 * `provider = "openai"`" — it does not know the instance name. `defaultBaseUrl`
 * is therefore code, and is the single source for a provider's default endpoint.
 * The same holds for the defaults of `extras`.
 *
 * == Provider-specific keys ==
 * The built-in fields ([[ProviderConfigSpec.BuiltinKeys]]) are shared by every
 * provider. Anything else a provider needs - a cloud region, a project id - is
 * declared in `extras` rather than smuggled through a built-in field
 * ([[https://github.com/llm4s/llm4s/issues/1215 #1215]]):
 *
 * {{{
 * val configSpec = ProviderConfigSpec(
 *   extras = Seq(
 *     ProviderConfigKey.required("project", "the GCP project ID that owns your Vertex AI resources"),
 *     ProviderConfigKey.optional("location", "the GCP region", default = Some("us-central1"))
 *   )
 * )
 * }}}
 *
 * @param requiresApiKey       the section must carry a non-empty `apiKey`.
 * @param requiresBaseUrl      the section must carry a non-empty `baseUrl`;
 *                             set this only when there is no `defaultBaseUrl`.
 * @param requiresEndpoint     the section must carry a non-empty `endpoint`.
 * @param defaultBaseUrl       base URL used when the section omits one.
 * @param baseUrlExample       example shown when a required `baseUrl` is missing.
 * @param endpointDescription  what this provider means by `endpoint`, shown when
 *                             a required one is missing (Azure: the deployment name).
 * @param baseUrlEnv           an environment variable that llm4s itself reads for `baseUrl`,
 *                             if one is: named in the missing-`baseUrl` message. `None` - the
 *                             usual case, since named sections read only what the user's config
 *                             sets - means no variable is suggested.
 * @param extras               the provider-specific keys this section accepts.
 */
final case class ProviderConfigSpec(
  requiresApiKey: Boolean = false,
  requiresBaseUrl: Boolean = false,
  requiresEndpoint: Boolean = false,
  defaultBaseUrl: Option[String] = None,
  baseUrlExample: String = "e.g. https://api.example.com/",
  endpointDescription: String = "the provider endpoint",
  baseUrlEnv: Option[String] = None,
  extras: Seq[ProviderConfigKey] = Seq.empty
):

  /** The declared provider-specific key called `name`, if any. */
  def extra(name: String): Option[ProviderConfigKey] = extras.find(_.name == name)

object ProviderConfigSpec:

  /**
   * The fields every named provider section may carry, whatever its provider.
   * An [[ProviderConfigKey]] cannot reuse one of these names.
   */
  val BuiltinKeys: Set[String] = Set(
    "provider",
    "model",
    "baseUrl",
    "apiKey",
    "organization",
    "endpoint",
    "apiVersion",
    "contextWindow",
    "reserveCompletion",
    "headers"
  )

  /**
   * The common shape: an API key, and a base URL that defaults to the
   * provider's public endpoint.
   */
  def apiKeyAndDefaultBaseUrl(defaultBaseUrl: String): ProviderConfigSpec =
    ProviderConfigSpec(requiresApiKey = true, defaultBaseUrl = Some(defaultBaseUrl))
