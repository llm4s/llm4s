package org.llm4s.spring

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.ConfigurationError
import org.llm4s.javaapi.{ LlmResult, ProviderModules }
import org.llm4s.security.Secret
import org.llm4s.types.Result
import org.llm4s.llmconnect.config._
import org.llm4s.llmconnect.spi.ProviderRegistry

object ProviderConfigParser {

  /** Trimmed value of a property, treating `null` (possible when set programmatically) as blank. */
  private def text(value: String): String = Option(value).fold("")(_.trim)

  private def invalid(message: String, key: String): LlmResult[ProviderConfig] =
    LlmResult.failure(ConfigurationError(message, List(key)))

  def parse(properties: Llm4sProperties): LlmResult[ProviderConfig] = {
    val provider = text(properties.provider).toLowerCase
    val model    = text(properties.model)

    if (provider.isEmpty) {
      invalid("llm4s.provider is required", "llm4s.provider")
    } else if (model.isEmpty) {
      invalid("llm4s.model is required", "llm4s.model")
    } else if (properties.contextWindow <= 0) {
      invalid(s"llm4s.context-window must be positive, got ${properties.contextWindow}", "llm4s.context-window")
    } else if (properties.reserveCompletion < 0 || properties.reserveCompletion >= properties.contextWindow) {
      invalid(
        s"llm4s.reserve-completion must be >= 0 and less than llm4s.context-window " +
          s"(${properties.contextWindow}), got ${properties.reserveCompletion}",
        "llm4s.reserve-completion"
      )
    } else {
      provider match {
        // The provider modules are optional dependencies of the starter (#1496): the application declares the one
        // it uses, and a provider whose module is absent fails here with the artifact to add.
        case "openai" =>
          ProviderModules.guard(ProviderModules.OpenAI, "llm4s.provider=openai")(parseOpenAI(properties, model))
        case "anthropic" =>
          ProviderModules.guard(ProviderModules.Anthropic, "llm4s.provider=anthropic")(
            parseAnthropic(properties, model)
          )
        case "ollama" =>
          ProviderModules.guard(ProviderModules.Ollama, "llm4s.provider=ollama")(parseOllama(properties, model))
        case unknown =>
          val registered = ProviderRegistry.default.descriptors.map(_.id.asString).mkString("[", ", ", "]")
          invalid(
            s"Unknown provider: '$unknown'. The flat llm4s.provider key supports openai, anthropic and ollama; " +
              s"configure any other provider under llm4s.providers.<name> " +
              s"(llm4s.default-provider=<name>, llm4s.providers.<name>.provider=<id>). " +
              s"Providers on the classpath: $registered",
            "llm4s.provider"
          )
      }
    }
  }

  private def parseOpenAI(p: Llm4sProperties, model: String): LlmResult[ProviderConfig] = {
    val apiKey = text(p.apiKey)
    if (apiKey.isEmpty) {
      invalid("llm4s.api-key is required for OpenAI", "llm4s.api-key")
    } else {
      val baseUrl = Some(text(p.baseUrl)).filter(_.nonEmpty).map(BaseUrl(_)).getOrElse(OpenAIConfig.DEFAULT_BASE_URL)
      val org     = Some(text(p.organization)).filter(_.nonEmpty)
      val api     = optional(p.api, OpenAIApi.parse(_))
      val summary = optional(p.reasoningSummary, OpenAIReasoningSummary.parse(_))
      (api, summary) match {
        case (Left(e), _) => invalid(s"llm4s.api is invalid: ${e.message}", "llm4s.api")
        case (_, Left(e)) => invalid(s"llm4s.reasoning-summary is invalid: ${e.message}", "llm4s.reasoning-summary")
        case (Right(a), Right(r)) =>
          LlmResult.success(
            OpenAIConfig(
              apiKey = Secret(apiKey),
              model = ModelName(model),
              organization = org,
              baseUrl = baseUrl,
              contextWindow = p.contextWindow,
              reserveCompletion = p.reserveCompletion
            ).withApi(a).withReasoningSummary(r).withKeepReasoning(p.keepReasoning)
          )
      }
    }
  }

  /** `value` read by `parse` when it says something; blank is `None`. */
  private def optional[A](value: String, parse: String => Result[A]): Result[Option[A]] =
    Some(text(value)).filter(_.nonEmpty).fold[Result[Option[A]]](Right(None))(v => parse(v).map(Some(_)))

  private def parseAnthropic(p: Llm4sProperties, model: String): LlmResult[ProviderConfig] = {
    val apiKey = text(p.apiKey)
    if (apiKey.isEmpty) {
      invalid("llm4s.api-key is required for Anthropic", "llm4s.api-key")
    } else {
      val baseUrl = Some(text(p.baseUrl)).filter(_.nonEmpty).getOrElse("https://api.anthropic.com")
      LlmResult.success(
        AnthropicConfig(
          apiKey = Secret(apiKey),
          model = ModelName(model),
          baseUrl = BaseUrl(baseUrl),
          contextWindow = p.contextWindow,
          reserveCompletion = p.reserveCompletion
        )
      )
    }
  }

  private def parseOllama(p: Llm4sProperties, model: String): LlmResult[ProviderConfig] = {
    val baseUrl = Some(text(p.baseUrl)).filter(_.nonEmpty).getOrElse("http://localhost:11434")
    LlmResult.success(
      OllamaConfig(
        model = ModelName(model),
        baseUrl = BaseUrl(baseUrl),
        contextWindow = p.contextWindow,
        reserveCompletion = p.reserveCompletion
      )
    )
  }
}
