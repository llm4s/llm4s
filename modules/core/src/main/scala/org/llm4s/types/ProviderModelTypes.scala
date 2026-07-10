package org.llm4s.types

/**
 * Type-safe identifier types for the multi-provider configuration system.
 *
 * Each identifier is a value class over `String`, giving distinct compile-time
 * types to values that are otherwise interchangeable raw strings. This makes it a
 * compile error to pass, for example, an `ApiKey` where a `ModelName` is
 * expected, at zero runtime cost.
 */
object ProviderModelTypes {

  /** A model identifier, e.g. `"gpt-4o"` or `"claude-sonnet-4-5"`. */
  case class ModelName(value: String) extends AnyVal {
    def asString: String = value
  }

  /** The base URL of a provider's API endpoint, e.g. `"https://api.openai.com/v1"`. */
  case class BaseUrl(value: String) extends AnyVal {
    def asUrl: String = value
  }

  /** A secret key used to authenticate with a provider. */
  case class ApiKey(value: String) extends AnyVal {
    def asKey: String = value
  }

  /** A provider identifier, e.g. `"openai"` or `"anthropic"`. */
  case class ProviderName(value: String) extends AnyVal {
    def asName: String = value
  }

  /** Enumeration of all supported LLM provider kinds. */
  sealed trait ProviderKind {
    def name: String = ProviderKind.name(this)
  }
  
  /** Companion object with lookup utilities for `ProviderKind`. */
  object ProviderKind {
    case object OpenAI extends ProviderKind
    case object OpenRouter extends ProviderKind
    case object Requesty extends ProviderKind
    case object Azure extends ProviderKind
    case object Anthropic extends ProviderKind
    case object Ollama extends ProviderKind
    case object Zai extends ProviderKind
    case object Gemini extends ProviderKind
    case object DeepSeek extends ProviderKind
    case object Cohere extends ProviderKind
    case object Mistral extends ProviderKind
    case object VertexAI extends ProviderKind

    /** All known provider kinds in a fixed sequence. */
    val all: Seq[ProviderKind] = Seq(
      OpenAI,
      OpenRouter,
      Requesty,
      Azure,
      Anthropic,
      Ollama,
      Zai,
      Gemini,
      DeepSeek,
      Cohere,
      Mistral,
      VertexAI
    )

    /**
     * Parses a `ProviderKind` from a case-insensitive provider name string.
     *
     * @param value The provider name string (e.g. `"openai"`, `"anthropic"`, `"google"`).
     * @return `Some(ProviderKind)` if recognised, `None` otherwise.
     */
    def fromString(value: String): Option[ProviderKind] = {
      value.trim.toLowerCase match {
        case "openai"              => Some(OpenAI)
        case "openrouter"          => Some(OpenRouter)
        case "requesty"            => Some(Requesty)
        case "azure"               => Some(Azure)
        case "anthropic"           => Some(Anthropic)
        case "ollama"              => Some(Ollama)
        case "zai"                 => Some(Zai)
        case "gemini"              => Some(Gemini)
        case "google"              => Some(Gemini)
        case "deepseek"            => Some(DeepSeek)
        case "cohere"              => Some(Cohere)
        case "mistral"             => Some(Mistral)
        case "vertex" | "vertexai" => Some(VertexAI)
        case _                     => None
      }
    }

    /**
     * Alias for `fromString` — parses a `ProviderKind` from a provider name string.
     *
     * @param value The provider name string.
     * @return `Some(ProviderKind)` if recognised, `None` otherwise.
     */
    def fromName(value: String): Option[ProviderKind] = fromString(value)

    /** Returns the canonical lowercase name string for this `ProviderKind`. */
    def name(kind: ProviderKind): String = kind match {
      case OpenAI     => "openai"
      case OpenRouter => "openrouter"
      case Requesty   => "requesty"
      case Azure      => "azure"
      case Anthropic  => "anthropic"
      case Ollama     => "ollama"
      case Zai        => "zai"
      case Gemini     => "gemini"
      case DeepSeek   => "deepseek"
      case Cohere     => "cohere"
      case Mistral    => "mistral"
      case VertexAI   => "vertexai"
    }
  }
}
