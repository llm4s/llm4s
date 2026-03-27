package org.llm4s.types

object ProviderModelTypes:

  opaque type ModelName    = String
  opaque type BaseUrl      = String
  opaque type ApiKey       = String
  opaque type ProviderName = String

  object ModelName:
    def apply(value: String): ModelName = value

  object BaseUrl:
    def apply(value: String): BaseUrl = value

  object ApiKey:
    def apply(value: String): ApiKey = value

  object ProviderName:
    def apply(value: String): ProviderName = value

  extension (value: ModelName) def asString: String  = value
  extension (value: BaseUrl) def asUrl: String       = value
  extension (value: ApiKey) def asKey: String        = value
  extension (value: ProviderName) def asName: String = value

  enum ProviderKind:
    case OpenAI
    case OpenRouter
    case Azure
    case Anthropic
    case Ollama
    case Zai
    case Gemini
    case DeepSeek
    case Cohere
    case Mistral

  object ProviderKind:
    def fromString(value: String): Option[ProviderKind] =
      value.trim.toLowerCase match
        case "openai"     => Some(ProviderKind.OpenAI)
        case "openrouter" => Some(ProviderKind.OpenRouter)
        case "azure"      => Some(ProviderKind.Azure)
        case "anthropic"  => Some(ProviderKind.Anthropic)
        case "ollama"     => Some(ProviderKind.Ollama)
        case "zai"        => Some(ProviderKind.Zai)
        case "gemini"     => Some(ProviderKind.Gemini)
        case "google"     => Some(ProviderKind.Gemini)
        case "deepseek"   => Some(ProviderKind.DeepSeek)
        case "cohere"     => Some(ProviderKind.Cohere)
        case "mistral"    => Some(ProviderKind.Mistral)
        case _            => None
