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
 * @param env               the conventional environment variable for this key, if it has one.
 *                          Named sections read no variable by themselves, so the missing-key
 *                          message shows the binding that reads it - `key = ${?VAR}` - rather
 *                          than telling the user to set a variable nothing would read.
 * @param deprecatedAliases older names for this key. A section that sets an alias instead of
 *                          `name` still works, with a deprecation warning per alias used; one
 *                          that sets `name` and an alias, or two aliases, to different values is
 *                          an error. An alias may be any key the section could otherwise carry
 *                          as an extra - which is how a provider renames one (Vertex AI's
 *                          `endpoint` became `project`) - or one of the built-in fields in
 *                          [[ProviderConfigSpec.BuiltinAliasKeys]]. `provider`, `model` and
 *                          `headers` cannot be aliases, and a spec naming one fails validation.
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
 * @param defaultBaseUrl       base URL used when the section omits one.
 * @param baseUrlExample       example shown when a required `baseUrl` is missing.
 * @param baseUrlEnv           the conventional environment variable for `baseUrl`, if the
 *                             provider has one. The missing-`baseUrl` message then shows the
 *                             binding that reads it (`baseUrl = ${?VAR}`); `None` means no
 *                             variable is suggested.
 * @param extras               the provider-specific keys this section accepts.
 * @param apiKeyEnv            the variables the provider's module binds to its vendor's shared key,
 *                             `llm4s.credentials.<id>.apiKey`, highest precedence first - e.g.
 *                             `Seq("OPENAI_API_KEY")`, or Gemini's `Seq("GOOGLE_API_KEY",
 *                             "GEMINI_API_KEY")`. Named in the missing-`apiKey` error. This is a
 *                             declaration for the message only: the binding itself is a line in
 *                             the module's `reference.conf`, and the module's round-trip spec
 *                             proves the two agree. Empty when the module binds no variable; the
 *                             error then names `llm4s.credentials.<id>.apiKey` itself.
 *
 * == The API key ==
 * A section's key is its own `apiKey` when it sets one, and otherwise the vendor's shared
 * `llm4s.credentials.<id>.apiKey` - keyed by provider id, which a `reference.conf` ''can''
 * know, unlike the instance name. So with `OPENAI_API_KEY` set, a section needs only
 * `provider` and `model`; a section for a second account sets `apiKey` itself.
 */
final case class ProviderConfigSpec private (
  requiresApiKey: Boolean,
  requiresBaseUrl: Boolean,
  defaultBaseUrl: Option[String],
  baseUrlExample: String,
  baseUrlEnv: Option[String],
  extras: Seq[ProviderConfigKey],
  apiKeyEnv: Seq[String]
):
  def withRequiresApiKey(requiresApiKey: Boolean): ProviderConfigSpec   = copy(requiresApiKey = requiresApiKey)
  def withRequiresBaseUrl(requiresBaseUrl: Boolean): ProviderConfigSpec = copy(requiresBaseUrl = requiresBaseUrl)
  def withDefaultBaseUrl(defaultBaseUrl: String): ProviderConfigSpec    = copy(defaultBaseUrl = Some(defaultBaseUrl))
  def withDefaultBaseUrl(defaultBaseUrl: Option[String]): ProviderConfigSpec = copy(defaultBaseUrl = defaultBaseUrl)
  def withBaseUrlExample(baseUrlExample: String): ProviderConfigSpec         = copy(baseUrlExample = baseUrlExample)
  def withBaseUrlEnv(baseUrlEnv: String): ProviderConfigSpec                 = copy(baseUrlEnv = Some(baseUrlEnv))
  def withBaseUrlEnv(baseUrlEnv: Option[String]): ProviderConfigSpec         = copy(baseUrlEnv = baseUrlEnv)
  def withExtras(extras: Seq[ProviderConfigKey]): ProviderConfigSpec         = copy(extras = extras)
  def withApiKeyEnv(apiKeyEnv: Seq[String]): ProviderConfigSpec              = copy(apiKeyEnv = apiKeyEnv)

  /** The declared provider-specific key called `name`, if any. */
  def extra(name: String): Option[ProviderConfigKey] = extras.find(_.name == name)

object ProviderConfigSpec:

  /** Creates a [[ProviderConfigSpec]]. Named arguments are the supported way to construct one. */
  def apply(
    requiresApiKey: Boolean = false,
    requiresBaseUrl: Boolean = false,
    defaultBaseUrl: Option[String] = None,
    baseUrlExample: String = "e.g. https://api.example.com/",
    baseUrlEnv: Option[String] = None,
    extras: Seq[ProviderConfigKey] = Seq.empty,
    apiKeyEnv: Seq[String] = Seq.empty
  ): ProviderConfigSpec =
    new ProviderConfigSpec(
      requiresApiKey,
      requiresBaseUrl,
      defaultBaseUrl,
      baseUrlExample,
      baseUrlEnv,
      extras,
      apiKeyEnv
    )

  /**
   * The fields every named provider section may carry, whatever its provider.
   * An [[ProviderConfigKey]] cannot reuse one of these names.
   *
   * Anything vendor-specific - Azure's `endpoint` and `apiVersion`, OpenAI's `organization`, the
   * generic `openai-compatible` provider's `contextWindow` - is a [[ProviderConfigKey]] its
   * provider declares, not a built-in, and is unknown to every provider that does not declare it.
   */
  val BuiltinKeys: Set[String] = Set("provider", "model", "baseUrl", "apiKey", "headers")

  /**
   * The built-in fields that can be a deprecated alias (`ProviderConfigKey.deprecatedAliases`):
   * those with a string form. `provider` and `model` select and drive every provider, and
   * `headers` is a map, so none of them can stand in for a provider-specific key.
   */
  val BuiltinAliasKeys: Set[String] = Set("baseUrl", "apiKey")

  /**
   * The common shape: an API key, and a base URL that defaults to the
   * provider's public endpoint.
   *
   * @param apiKeyEnv the variables the module binds to `llm4s.credentials.<id>.apiKey`; see
   *                  [[ProviderConfigSpec.apiKeyEnv]].
   */
  def apiKeyAndDefaultBaseUrl(defaultBaseUrl: String, apiKeyEnv: Seq[String] = Seq.empty): ProviderConfigSpec =
    ProviderConfigSpec(requiresApiKey = true, defaultBaseUrl = Some(defaultBaseUrl), apiKeyEnv = apiKeyEnv)
