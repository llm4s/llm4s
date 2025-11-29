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

  final private case class ProviderRoot(
    llm: LlmSection,
    openai: Option[OpenAISection],
    azure: Option[AzureSection],
    anthropic: Option[AnthropicSection],
    ollama: Option[OllamaSection]
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

  implicit private val providerRootReader: PureConfigReader[ProviderRoot] =
    PureConfigReader.forProduct5("llm", "openai", "azure", "anthropic", "ollama")(ProviderRoot.apply)

  def load(source: ConfigSource): Result[ProviderConfig] = {
    val rootEither = source.at("llm4s").load[ProviderRoot]

    rootEither.left
      .map { failures =>
        val msg = failures.toList.map(_.description).mkString("; ")
        ConfigurationError(s"Failed to load llm4s provider config via PureConfig: $msg")
      }
      .flatMap(buildProviderConfig)
  }

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
        case "openai"     => buildOpenAIConfig(modelName, root.openai)
        case "openrouter" => buildOpenAIConfig(modelName, root.openai)
        case "azure"      => buildAzureConfig(modelName, root.azure)
        case "anthropic"  => buildAnthropicConfig(modelName, root.anthropic)
        case "ollama"     => buildOllamaConfig(modelName, root.ollama)
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
}
