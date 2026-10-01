package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor, ProviderRegistry }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Checks a named provider section against the requirements its provider declares.
 *
 * There is one validator, not one per provider: a provider states what it needs
 * in [[org.llm4s.llmconnect.spi.ProviderConfigSpec]] and this turns that into
 * the error message. Before #1131 each provider had its own
 * `NamedProviderValidator` object in `llm4s-core`, which is the file every new
 * provider had to edit.
 *
 * It also resolves the provider-specific keys (`ProviderConfigSpec.extras`, #1215):
 * required ones must be present, defaults are filled in, deprecated aliases are
 * mapped to the current name with a warning, and keys the provider does not
 * declare are reported with a warning and dropped. Unknown keys were silently
 * ignored before extras existed, so a warning rather than an error keeps existing
 * configs loading while still surfacing a typo.
 */
private[llm4s] object NamedProviderSectionValidator:

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Validates an already-normalised section against `descriptor`, logging any warnings.
   *
   *  @param providerName the user's instance name (`llm4s.providers.<name>`), used in error messages
   *  @param descriptor   the provider the section resolved to
   *  @param normalized   the normalised section
   *  @return `Right` with the section's provider-specific keys resolved, or `Left` listing what is missing
   */
  def validate(
    providerName: ProviderName,
    descriptor: ProviderDescriptor,
    normalized: NamedProviderConfig
  ): Result[NamedProviderConfig] =
    validateWithWarnings(providerName, descriptor, normalized).map { case (config, warnings) =>
      warnings.foreach(warning => logger.warn(warning))
      config
    }

  /**
   * [[validate]], returning the warnings instead of logging them: a deprecated alias in use,
   * or a key the provider does not declare.
   */
  def validateWithWarnings(
    providerName: ProviderName,
    descriptor: ProviderDescriptor,
    normalized: NamedProviderConfig
  ): Result[(NamedProviderConfig, Seq[String])] =
    val name = providerName.asName
    val id   = descriptor.id.asString
    val spec = descriptor.configSpec

    if normalized.provider != descriptor.id then
      Left(
        ConfigurationError(
          s"Configured provider '$name' resolved to unexpected provider '${normalized.provider.asString}'"
        )
      )
    else
      specError(id, spec) match
        case Some(error) => Left(error)
        case None =>
          val extras   = resolveExtras(name, id, spec, normalized)
          val problems = missingBuiltins(name, descriptor, normalized) ++ extras.problems

          if problems.nonEmpty then
            Left(
              ConfigurationError(
                s"Provider '$name' (provider = $id) is missing required fields:\n" + problems.mkString("\n")
              )
            )
          else Right((normalized.withExtras(extras.values), extras.warnings))

  /**
   * A descriptor bug rather than a user one: an extra key may not shadow a built-in field, and a
   * deprecated alias may name a built-in field only if it has a string form to carry over.
   */
  private def specError(id: String, spec: ProviderConfigSpec): Option[ConfigurationError] =
    val clashing = spec.extras.map(_.name).filter(ProviderConfigSpec.BuiltinKeys.contains)
    val badAliases = spec.extras
      .flatMap(_.deprecatedAliases)
      .filter(alias =>
        ProviderConfigSpec.BuiltinKeys.contains(alias) && !ProviderConfigSpec.BuiltinAliasKeys.contains(alias)
      )
    val problems =
      Option.when(clashing.nonEmpty)(
        s"declares provider-specific key(s) ${clashing.mkString(", ")}, which are built-in named-provider fields; " +
          "rename them in its ProviderConfigSpec.extras"
      ) ++ Option.when(badAliases.nonEmpty)(
        s"declares built-in field(s) ${badAliases.distinct.mkString(", ")} as deprecated aliases, which cannot " +
          s"be; only ${ProviderConfigSpec.BuiltinAliasKeys.toSeq.sorted.mkString(", ")} can"
      )
    Option.when(problems.nonEmpty)(ConfigurationError(s"Provider '$id' ${problems.mkString("; and ")}"))

  private def missingBuiltins(
    name: String,
    descriptor: ProviderDescriptor,
    normalized: NamedProviderConfig
  ): Seq[String] =
    val spec    = descriptor.configSpec
    val missing = Seq.newBuilder[String]

    if spec.requiresApiKey && normalized.apiKey.isEmpty then
      // By now the section has already fallen back to the vendor's shared key, so both places are
      // empty. Name the variable the provider's module binds to that shared key - declared by the
      // provider, never guessed from its id - and the section's own key, for a second account.
      missing += s"  - apiKey: ${SharedCredentials.missingKeyHint(spec.apiKeyEnv, descriptor.id, s"llm4s.providers.$name")}"

    if spec.requiresBaseUrl && normalized.baseUrl.isEmpty then
      // Name an environment variable only when the provider says one is actually read (#1215). This
      // used to tell every provider's users to "set <PROVIDER>_BASE_URL", and nothing read any of them.
      missing += s"  - baseUrl: set it in application.conf under llm4s.providers.$name.baseUrl (${spec.baseUrlExample}" +
        envHint("baseUrl", spec.baseUrlEnv) + ")"

    missing.result()

  // Named sections read no environment variable by themselves, so a declared variable is shown as the
  // HOCON binding that makes llm4s read it - accurate whether or not the user has written it yet.
  private def envHint(key: String, env: Option[String]): String =
    env.fold("")(variable => s"; to read it from $variable, add $key = $${?$variable} to the section")

  /** The provider-specific keys after defaults and aliases, and what is wrong with them. */
  final private case class ResolvedExtras(values: Map[String, String], problems: Seq[String], warnings: Seq[String])

  /** One declared key's outcome: its value, if any, plus problems and warnings. */
  final private case class KeyOutcome(
    value: Option[String],
    problems: Seq[String] = Nil,
    warnings: Seq[String] = Nil
  )

  private def resolveExtras(
    name: String,
    id: String,
    spec: ProviderConfigSpec,
    normalized: NamedProviderConfig
  ): ResolvedExtras =
    val section = s"llm4s.providers.$name"

    // A deprecated alias may be a built-in field or a former key read from `extras` - as Vertex
    // AI's `endpoint` and `organization` are, now that neither is built in. Every built-in in
    // `BuiltinAliasKeys` is read here; `specError` rejects the others.
    def aliasValue(alias: String): Option[String] =
      alias match
        case "baseUrl" => normalized.baseUrl.map(_.asUrl)
        case "apiKey"  => normalized.apiKey.map(_.asKey)
        case other     => normalized.extras.get(other)

    def resolve(key: ProviderConfigKey): KeyOutcome =
      val aliasHits = key.deprecatedAliases.flatMap(alias => aliasValue(alias).map(alias -> _))

      def deprecated(alias: String): String =
        s"$section.$alias is deprecated for provider = $id; rename it to $section.${key.name}. " +
          "The old name will stop working in a future release."

      normalized.extras.get(key.name) match
        case Some(value) =>
          aliasHits.find(_._2 != value) match
            case Some((alias, _)) =>
              KeyOutcome(
                None,
                problems = Seq(
                  s"  - ${key.name}: also set, to a different value, as its deprecated alias `$alias`; remove $section.$alias"
                )
              )
            case None => KeyOutcome(Some(value), warnings = aliasHits.map((alias, _) => deprecated(alias)))
        case None =>
          aliasHits.map(_._2).distinct match
            case Seq(value) => KeyOutcome(Some(value), warnings = aliasHits.map((alias, _) => deprecated(alias)))
            case _ if aliasHits.nonEmpty =>
              // Several old spellings with different values: which was meant cannot be known.
              val set = aliasHits.map((alias, value) => s"`$alias` = \"$value\"").mkString(", ")
              KeyOutcome(
                None,
                problems = Seq(
                  s"  - ${key.name}: set through several deprecated aliases with different values ($set); " +
                    s"replace them with $section.${key.name}"
                )
              )
            case _ =>
              key.default match
                case some @ Some(_) => KeyOutcome(some)
                case None if key.required =>
                  KeyOutcome(
                    None,
                    problems = Seq(
                      s"  - ${key.name}: ${key.description} (set it in application.conf under llm4s.providers.$name.${key.name}" +
                        s"${envHint(key.name, key.env)})"
                    )
                  )
                case None => KeyOutcome(None)

    val outcomes = spec.extras.map(key => key -> resolve(key))
    val values   = outcomes.flatMap((key, outcome) => outcome.value.map(key.name -> _)).toMap

    val claimed = spec.extras.flatMap(key => key.name +: key.deprecatedAliases).toSet
    val unknown = normalized.extras.keys.filterNot(claimed.contains).toSeq.sorted
    val unknownWarning = Option.when(unknown.nonEmpty) {
      val accepted =
        if spec.extras.isEmpty then s"provider = $id declares no provider-specific keys"
        else s"provider = $id also accepts ${spec.extras.map(_.name).mkString(", ")}"
      s"$section has unknown key(s) ${unknown.mkString(", ")}, which are ignored. Besides the built-in fields " +
        s"(${ProviderConfigSpec.BuiltinKeys.toSeq.sorted.mkString(", ")}), $accepted."
    }

    ResolvedExtras(
      values,
      outcomes.flatMap(_._2.problems),
      outcomes.flatMap(_._2.warnings) ++ unknownWarning
    )

/** Normalises a raw named provider section and validates it against its registered provider. */
private[llm4s] object NamedProviderConfigValidator:

  /**
   * Validates a raw provider section by normalising it and checking it against the
   * registered provider it names.
   *
   *  A section that sets no `apiKey` takes its vendor's shared `llm4s.credentials.<id>.apiKey`
   *  from `credentials`, and where the key came from is logged at INFO - the path, never the
   *  value.
   *
   *  @param providerName the logical name of the provider entry, used in error messages
   *  @param section      the raw unvalidated provider section
   *  @param credentials  the `llm4s.credentials` block; empty by default, so a section validated
   *                      on its own uses only its own key
   *  @return `Right(NamedProviderConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def validate(
    providerName: ProviderName,
    section: RawNamedProviderSection,
    credentials: SharedCredentials = SharedCredentials.empty
  )(using registry: ProviderRegistry): Result[NamedProviderConfig] =
    val sectionPath = s"llm4s.providers.${providerName.asName}"
    for
      normalized <- NamedProviderConfigNormalizer.normalize(providerName, section)
      descriptor <- registry.resolve(normalized.provider, Some(s"$sectionPath.provider"))
      // `normalized.provider` is already canonical - an alias such as `google` has become
      // `gemini` - so an aliased section finds its vendor's shared key.
      resolved <- credentials.resolve(normalized.apiKey.map(_.asKey), s"$sectionPath.apiKey", normalized.provider)
      validated <- NamedProviderSectionValidator.validate(
        providerName,
        descriptor,
        normalized.withApiKey(resolved.map(key => ApiKey(key.value)))
      )
    yield
      resolved.foreach(SharedCredentials.logSource(sectionPath, _))
      validated
