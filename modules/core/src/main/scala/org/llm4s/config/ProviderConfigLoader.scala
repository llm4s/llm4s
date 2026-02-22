package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config._
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

private[config] object ProviderConfigLoader {

  final private case class LlmSection(model: Option[String])

  final private case class OpenAISection(
    baseUrl: Option[String],
    apiKey: Option[String],
    organization: Option[String]
  )

  final private case class AzureSection(
    endpoint: Option[String],
    apiKey: Option[String],
    apiVersion: Option[String]
  )

  final private case class AnthropicSection(
    baseUrl: Option[String],
    apiKey: Option[String]
  )

  final private case class OllamaSection(
    baseUrl: Option[String]
  )

  final private case class ZaiSection(
    baseUrl: Option[String],
    apiKey: Option[String]
  )

  final private case class GeminiSection(
    baseUrl: Option[String],
    apiKey: Option[String]
  )

  final private case class DeepSeekSection(
    baseUrl: Option[String],
    apiKey: Option[String]
  )

  final private case class CohereSection(
    baseUrl: Option[String],
    apiKey: Option[String]
  )

  final private case class ProviderRoot(
    llm: LlmSection,
    openai: Option[OpenAISection],
    azure: Option[AzureSection],
    anthropic: Option[AnthropicSection],
    ollama: Option[OllamaSection],
    zai: Option[ZaiSection],
    gemini: Option[GeminiSection],
    deepseek: Option[DeepSeekSection],
    cohere: Option[CohereSection]
  )

  implicit private val llmSectionReader: PureConfigReader[LlmSection] =
    PureConfigReader.forProduct1("model")(LlmSection.apply)

  implicit private val openAISectionReader: PureConfigReader[OpenAISection] =
    PureConfigReader.forProduct3("baseUrl", "apiKey", "organization")(OpenAISection.apply)

  implicit private val azureSectionReader: PureConfigReader[AzureSection] =
    PureConfigReader.forProduct3("endpoint", "apiKey", "apiVersion")(AzureSection.apply)

  implicit private val anthropicSectionReader: PureConfigReader[AnthropicSection] =
    PureConfigReader.forProduct2("baseUrl", "apiKey")(AnthropicSection.apply)

  implicit private val ollamaSectionReader: PureConfigReader[OllamaSection] =
    PureConfigReader.forProduct1("baseUrl")(OllamaSection.apply)

  implicit private val zaiSectionReader: PureConfigReader[ZaiSection] =
    PureConfigReader.forProduct2("baseUrl", "apiKey")(ZaiSection.apply)

  implicit private val geminiSectionReader: PureConfigReader[GeminiSection] =
    PureConfigReader.forProduct2("baseUrl", "apiKey")(GeminiSection.apply)

  implicit private val deepseekSectionReader: PureConfigReader[DeepSeekSection] =
    PureConfigReader.forProduct2("baseUrl", "apiKey")(DeepSeekSection.apply)

  implicit private val cohereSectionReader: PureConfigReader[CohereSection] =
    PureConfigReader.forProduct2("baseUrl", "apiKey")(CohereSection.apply)

  implicit private val providerRootReader: PureConfigReader[ProviderRoot] =
    PureConfigReader.forProduct9(
      "llm",
      "openai",
      "azure",
      "anthropic",
      "ollama",
      "zai",
      "gemini",
      "deepseek",
      "cohere"
    )(
      ProviderRoot.apply
    )

  /**
   * Loads a [[ProviderConfig]] from `source`.
   *
   * Reads the `llm4s` config tree from `source`, parses `llm4s.llm.model`
   * to determine the provider prefix (format: `provider/model`, e.g.
   * `"openai/gpt-4o"`), then reads the matching provider section for
   * credentials and endpoint.
   *
   * When the model spec lacks a provider prefix (e.g. `"gpt-4o"` alone),
   * the loader infers the provider from `llm4s.openai.baseUrl`: a URL
   * containing `"openrouter.ai"` selects OpenRouter; anything else selects
   * OpenAI.
   *
   * @param source PureConfig source to read from; use `ConfigSource.default`
   *               in production to read environment variables and
   *               `application.conf`.
   * @return the typed provider config, or a [[org.llm4s.error.ConfigurationError]]
   *         when `LLM_MODEL` is absent, the provider prefix is unrecognised,
   *         or a required credential variable is missing.
   */
  def load(source: ConfigSource): Result[ProviderConfig] = {
    val rootEither = source.at("llm4s").load[ProviderRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s provider config via PureConfig: $msg")
      }
      .flatMap(buildProviderConfig)
  }

  /**
   * Reads the OpenAI API key from `source` without constructing a full provider config.
   *
   * Used by the embeddings subsystem, which shares the OpenAI API key for
   * embedding requests even when a different provider is selected for the LLM.
   *
   * @param source PureConfig source to read from.
   * @return the API key string, or a [[org.llm4s.error.ConfigurationError]]
   *         when `OPENAI_API_KEY` / `llm4s.openai.apiKey` is absent or blank.
   */
  def loadOpenAISharedApiKey(source: ConfigSource): Result[String] = {
    val rootEither = source.at("llm4s").load[ProviderRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(
          s"Failed to load llm4s provider config via PureConfig when resolving OpenAI API key: $msg"
        )
      }
      .flatMap { root =>
        val apiKeyOpt =
          root.openai.flatMap(_.apiKey).map(_.trim).filter(_.nonEmpty)
        apiKeyOpt.toRight(ConfigurationError("Missing OpenAI API key (llm4s.openai.apiKey / OPENAI_API_KEY)"))
      }
  }

  private def buildProviderConfig(root: ProviderRoot): Result[ProviderConfig] = {
    val modelSpec = root.llm.model.map(_.trim).getOrElse("")
    if (modelSpec.isEmpty)
      Left(ConfigurationError(s"Missing model spec: set ${ConfigKeys.LLM_MODEL}"))
    else {
      val parts = modelSpec.split("/", 2)
      val (prefix, modelName) =
        if (parts.length == 2) (parts(0).toLowerCase, parts(1))
        else (inferProviderFromBaseUrl(root), parts(0))

      prefix match {
        case "openai"            => buildOpenAIConfig(modelName, root.openai)
        case "openrouter"        => buildOpenAIConfig(modelName, root.openai)
        case "azure"             => buildAzureConfig(modelName, root.azure)
        case "anthropic"         => buildAnthropicConfig(modelName, root.anthropic)
        case "ollama"            => buildOllamaConfig(modelName, root.ollama)
        case "zai"               => buildZaiConfig(modelName, root.zai)
        case "gemini" | "google" => buildGeminiConfig(modelName, root.gemini)
        case "deepseek"          => buildDeepSeekConfig(modelName, root.deepseek)
        case "cohere"            => buildCohereConfig(modelName, root.cohere)
        case other if other.nonEmpty =>
          Left(ConfigurationError(s"Unknown provider prefix: $other in '$modelSpec'"))
        case _ =>
          Left(ConfigurationError(s"Unable to infer provider for model '$modelSpec'"))
      }
    }
  }

  private def inferProviderFromBaseUrl(root: ProviderRoot): String = {
    val base =
      root.openai.flatMap(_.baseUrl).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_OPENAI_BASE_URL)
    if (base.contains("openrouter.ai")) "openrouter" else "openai"
  }

  private def buildOpenAIConfig(modelName: String, section: Option[OpenAISection]): Result[ProviderConfig] =
    section match {
      case Some(openai) =>
        val apiKeyOpt = openai.apiKey.map(_.trim).filter(_.nonEmpty)
        val apiKeyResult: Result[String] =
          apiKeyOpt.toRight(
            ConfigurationError("Missing OpenAI API key (llm4s.openai.apiKey / OPENAI_API_KEY)")
          )

        apiKeyResult.map { apiKey =>
          val baseUrl =
            openai.baseUrl.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_OPENAI_BASE_URL)

          val org = openai.organization.map(_.trim).filter(_.nonEmpty)

          OpenAIConfig.fromValues(modelName, apiKey, org, baseUrl)
        }

      case None =>
        Left(
          ConfigurationError(
            "OpenAI provider selected but llm4s.openai section is missing"
          )
        )
    }

  private def buildAzureConfig(modelName: String, section: Option[AzureSection]): Result[ProviderConfig] =
    section match {
      case Some(azure) =>
        val endpointOpt = azure.endpoint.map(_.trim).filter(_.nonEmpty)
        val apiKeyOpt   = azure.apiKey.map(_.trim).filter(_.nonEmpty)

        val endpointResult: Result[String] =
          endpointOpt.toRight(
            ConfigurationError("Missing Azure endpoint (llm4s.azure.endpoint / AZURE_API_BASE)")
          )
        val apiKeyResult: Result[String] =
          apiKeyOpt.toRight(
            ConfigurationError("Missing Azure API key (llm4s.azure.apiKey / AZURE_API_KEY)")
          )

        for {
          endpoint <- endpointResult
          apiKey   <- apiKeyResult
          apiVersion = azure.apiVersion
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse(DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW)
        } yield AzureConfig.fromValues(modelName, endpoint, apiKey, apiVersion)

      case None =>
        Left(
          ConfigurationError(
            "Azure provider selected but llm4s.azure section is missing"
          )
        )
    }

  private def buildAnthropicConfig(modelName: String, section: Option[AnthropicSection]): Result[ProviderConfig] =
    section match {
      case Some(anthropic) =>
        val apiKeyOpt = anthropic.apiKey.map(_.trim).filter(_.nonEmpty)
        val apiKeyResult: Result[String] =
          apiKeyOpt.toRight(
            ConfigurationError("Missing Anthropic API key (llm4s.anthropic.apiKey / ANTHROPIC_API_KEY)")
          )

        apiKeyResult.map { apiKey =>
          val baseUrl =
            anthropic.baseUrl.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL)

          AnthropicConfig.fromValues(modelName, apiKey, baseUrl)
        }

      case None =>
        Left(
          ConfigurationError(
            "Anthropic provider selected but llm4s.anthropic section is missing"
          )
        )
    }

  private def buildOllamaConfig(modelName: String, section: Option[OllamaSection]): Result[ProviderConfig] =
    section match {
      case Some(ollama) =>
        val baseUrlOpt = ollama.baseUrl.map(_.trim).filter(_.nonEmpty)
        val baseUrlResult: Result[String] =
          baseUrlOpt.toRight(
            ConfigurationError("Missing Ollama base URL (llm4s.ollama.baseUrl / OLLAMA_BASE_URL)")
          )

        baseUrlResult.map(baseUrl => OllamaConfig.fromValues(modelName, baseUrl))

      case None =>
        Left(
          ConfigurationError(
            "Ollama provider selected but llm4s.ollama section is missing"
          )
        )
    }

  private def buildZaiConfig(modelName: String, section: Option[ZaiSection]): Result[ProviderConfig] =
    section match {
      case Some(zai) =>
        val apiKeyOpt = zai.apiKey.map(_.trim).filter(_.nonEmpty)
        val apiKeyResult: Result[String] =
          apiKeyOpt.toRight(
            ConfigurationError("Missing Z.ai API key (llm4s.zai.apiKey / ZAI_API_KEY)")
          )

        apiKeyResult.map { apiKey =>
          val baseUrl =
            zai.baseUrl.map(_.trim).filter(_.nonEmpty).getOrElse(ZaiConfig.DEFAULT_BASE_URL)

          ZaiConfig.fromValues(modelName, apiKey, baseUrl)
        }

      case None =>
        Left(
          ConfigurationError(
            "Z.ai provider selected but llm4s.zai section is missing"
          )
        )
    }

  private def buildGeminiConfig(modelName: String, section: Option[GeminiSection]): Result[ProviderConfig] =
    section match {
      case Some(gemini) =>
        val apiKeyOpt = gemini.apiKey.map(_.trim).filter(_.nonEmpty)
        val apiKeyResult: Result[String] =
          apiKeyOpt.toRight(
            ConfigurationError("Missing Gemini API key (llm4s.gemini.apiKey / GOOGLE_API_KEY)")
          )

        apiKeyResult.map { apiKey =>
          val baseUrl =
            gemini.baseUrl.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_GEMINI_BASE_URL)

          GeminiConfig.fromValues(modelName, apiKey, baseUrl)
        }

      case None =>
        Left(
          ConfigurationError(
            "Gemini provider selected but llm4s.gemini section is missing"
          )
        )
    }

  private def buildDeepSeekConfig(modelName: String, section: Option[DeepSeekSection]): Result[ProviderConfig] =
    section match {
      case Some(deepseek) =>
        val apiKeyOpt = deepseek.apiKey.map(_.trim).filter(_.nonEmpty)
        val apiKeyResult: Result[String] =
          apiKeyOpt.toRight(
            ConfigurationError("Missing DeepSeek API key (llm4s.deepseek.apiKey / DEEPSEEK_API_KEY)")
          )

        apiKeyResult.map { apiKey =>
          val baseUrl =
            deepseek.baseUrl.map(_.trim).filter(_.nonEmpty).getOrElse(DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL)

          DeepSeekConfig.fromValues(modelName, apiKey, baseUrl)
        }

      case None =>
        Left(
          ConfigurationError(
            "DeepSeek provider selected but llm4s.deepseek section is missing"
          )
        )
    }

  private def buildCohereConfig(modelName: String, section: Option[CohereSection]): Result[ProviderConfig] =
    section match {
      case Some(cohere) =>
        val apiKeyOpt = cohere.apiKey.map(_.trim).filter(_.nonEmpty)
        val apiKeyResult: Result[String] =
          apiKeyOpt.toRight(
            ConfigurationError("Missing Cohere API key (llm4s.cohere.apiKey / COHERE_API_KEY)")
          )

        apiKeyResult.map { apiKey =>
          val baseUrl =
            cohere.baseUrl.map(_.trim).filter(_.nonEmpty).getOrElse(CohereConfig.DEFAULT_BASE_URL)

          CohereConfig.fromValues(modelName, apiKey, baseUrl)
        }

      case None =>
        Left(
          ConfigurationError(
            "Cohere provider selected but llm4s.cohere section is missing"
          )
        )
    }
}
