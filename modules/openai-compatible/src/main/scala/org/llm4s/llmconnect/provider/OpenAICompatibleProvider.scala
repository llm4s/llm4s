package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ OpenAICompatibleConfigKeys, OpenAICompatibleModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAICompatibleConfig, ProviderConfig }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.util.Locale

/**
 * Registration for the generic `openai-compatible` provider: any endpoint
 * speaking the OpenAI `/chat/completions` API, with no module and no code.
 *
 * A named provider section with `provider = "openai-compatible"` needs a
 * `baseUrl` and a `model`; `apiKey` is optional (local servers need none), and
 * `contextWindow`, `reserveCompletion` and `headers` may be set. The
 * provider-specific key `streamUsage` (default `true`) controls whether a streaming
 * request asks for token usage with `stream_options.include_usage`; set it to
 * `false` for a server that rejects that field. Several sections can use it side
 * by side:
 *
 * {{{
 * llm4s.providers {
 *   groq-main {
 *     provider = "openai-compatible"
 *     baseUrl  = "https://api.groq.com/openai/v1"
 *     model    = "openai/gpt-oss-120b"
 *     apiKey   = ${?GROQ_API_KEY}
 *     contextWindow = 131072
 *   }
 *   local-vllm {
 *     provider = "openai-compatible"
 *     baseUrl  = "http://localhost:8000/v1"
 *     model    = "Qwen/Qwen2.5-7B-Instruct"
 *   }
 * }
 * }}}
 *
 * The client is [[OpenAICompatibleClient]] with the standard dialect: no
 * reasoning parameters and no provider-specific decoding. A provider that needs
 * those gets its own [[OpenAICompatibleDialect]] and descriptor in this module.
 */
object OpenAICompatibleProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId(OpenAICompatibleConfig.ProviderIdName)

  /** The key that turns `stream_options.include_usage` off for a server that rejects it. */
  val StreamUsageKey: String = "streamUsage"

  // `baseUrlEnv` makes a missing-baseUrl error show `baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL}`,
  // the binding that reads the conventional variable. The generic provider has no vendor, so no
  // `llm4s.credentials` block binds its key either: a section sets its own `apiKey`, if any.
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec(
      requiresBaseUrl = true,
      baseUrlExample = "e.g. http://localhost:8000/v1",
      baseUrlEnv = Some(OpenAICompatibleConfigKeys.OPENAI_COMPATIBLE_BASE_URL),
      extras = Seq(
        ProviderConfigKey.optional(
          StreamUsageKey,
          "whether a streaming request asks for token usage (stream_options.include_usage); " +
            "false for a server that rejects the field",
          default = Some("true")
        )
      )
    )

  override val modelLister: Option[ProviderModelLister] = Some(OpenAICompatibleModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      baseUrl     <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      streamUsage <- parseStreamUsage(providerName, section.extra(StreamUsageKey))
      config <- OpenAICompatibleConfig.fromValues(
        model = section.model.asString,
        baseUrl = baseUrl,
        apiKey = section.apiKey.map(_.asKey),
        contextWindow = section.contextWindow,
        reserveCompletion = section.reserveCompletion,
        headers = section.headers,
        streamUsage = streamUsage
      )
    yield config

  // Extras arrive as strings. HOCON's own boolean spellings are accepted (true/yes/on,
  // false/no/off), so `streamUsage = off` means what it would for any HOCON boolean. A section
  // built in code, not through validation, may lack the key; it then takes the declared default.
  private def parseStreamUsage(providerName: String, value: Option[String]): Result[Boolean] =
    value.map(_.trim.toLowerCase(Locale.ROOT)) match
      case None                         => Right(true)
      case Some("true" | "yes" | "on")  => Right(true)
      case Some("false" | "no" | "off") => Right(false)
      case Some(_) =>
        Left(
          ConfigurationError(
            s"Configured provider '$providerName' has an invalid $StreamUsageKey: " +
              s"llm4s.providers.$providerName.$StreamUsageKey must be true or false, got '${value.getOrElse("")}'"
          )
        )

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[OpenAICompatibleConfig](id, config)
      .flatMap(OpenAICompatibleClient(_, options.metrics, options.exchangeLogging))
