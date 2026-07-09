package org.llm4s.types

/**
 * Type-safe identifier types for the multi-provider configuration system.
 *
 * Each identifier is an opaque type over `String`, giving distinct compile-time
 * types to values that are otherwise interchangeable raw strings. This makes it a
 * compile error to pass, for example, an [[ApiKey]] where a [[ModelName]] is
 * expected, at zero runtime cost.
 */
object ProviderModelTypes:

  /** A model identifier, e.g. `"gpt-4o"` or `"claude-sonnet-4-5"`. */
  opaque type ModelName = String

  /** The base URL of a provider's API endpoint, e.g. `"https://api.openai.com/v1"`. */
  opaque type BaseUrl = String

  /** A secret key used to authenticate with a provider. */
  opaque type ApiKey = String

  /** A provider identifier, e.g. `"openai"` or `"anthropic"`. */
  opaque type ProviderName = String

  /** Companion for the [[ModelName]] opaque type. */
  object ModelName:
    /** Wraps a raw string as a [[ModelName]]. */
    def apply(value: String): ModelName = value

  /** Companion for the [[BaseUrl]] opaque type. */
  object BaseUrl:
    /** Wraps a raw string as a [[BaseUrl]]. */
    def apply(value: String): BaseUrl = value

  /** Companion for the [[ApiKey]] opaque type. */
  object ApiKey:
    /** Wraps a raw string as an [[ApiKey]]. */
    def apply(value: String): ApiKey = value

  /** Companion for the [[ProviderName]] opaque type. */
  object ProviderName:
    /** Wraps a raw string as a [[ProviderName]]. */
    def apply(value: String): ProviderName = value

  /** Returns the underlying model name string. */
  extension (value: ModelName) def asString: String = value

  /** Returns the underlying base URL string. */
  extension (value: BaseUrl) def asUrl: String = value

  /** Returns the underlying key string. */
  extension (value: ApiKey) def asKey: String = value

  /** Returns the underlying provider name string. */
  extension (value: ProviderName) def asName: String = value

  /** Enumeration of all supported LLM provider kinds. */
  enum ProviderKind:
    case OpenAI
    case OpenRouter
    case Requesty
    case Azure
    case Anthropic
    case Ollama
    case Zai
    case Gemini
    case DeepSeek
    case Cohere
    case Mistral
    case VertexAI

  /** Companion object with lookup utilities for `ProviderKind`. */
  object ProviderKind:
    /** All known provider kinds in a fixed sequence. */
    val all: Seq[ProviderKind] = Seq(
      ProviderKind.OpenAI,
      ProviderKind.OpenRouter,
      ProviderKind.Requesty,
      ProviderKind.Azure,
      ProviderKind.Anthropic,
      ProviderKind.Ollama,
      ProviderKind.Zai,
      ProviderKind.Gemini,
      ProviderKind.DeepSeek,
      ProviderKind.Cohere,
      ProviderKind.Mistral,
      ProviderKind.VertexAI
    )

    /**
     * Parses a `ProviderKind` from a case-insensitive provider name string.
     *
     * @param value The provider name string (e.g. `"openai"`, `"anthropic"`, `"google"`).
     * @return `Some(ProviderKind)` if recognised, `None` otherwise.
     */
    def fromString(value: String): Option[ProviderKind] =
      value.trim.toLowerCase match
        case "openai"              => Some(ProviderKind.OpenAI)
        case "openrouter"          => Some(ProviderKind.OpenRouter)
        case "requesty"            => Some(ProviderKind.Requesty)
        case "azure"               => Some(ProviderKind.Azure)
        case "anthropic"           => Some(ProviderKind.Anthropic)
        case "ollama"              => Some(ProviderKind.Ollama)
        case "zai"                 => Some(ProviderKind.Zai)
        case "gemini"              => Some(ProviderKind.Gemini)
        case "google"              => Some(ProviderKind.Gemini)
        case "deepseek"            => Some(ProviderKind.DeepSeek)
        case "cohere"              => Some(ProviderKind.Cohere)
        case "mistral"             => Some(ProviderKind.Mistral)
        case "vertex" | "vertexai" => Some(ProviderKind.VertexAI)
        case _                     => None

    /**
     * Alias for `fromString` — parses a `ProviderKind` from a provider name string.
     *
     * @param value The provider name string.
     * @return `Some(ProviderKind)` if recognised, `None` otherwise.
     */
    def fromName(value: String): Option[ProviderKind] =
      fromString(value)

  /**
   * Returns the canonical lowercase name string for this `ProviderKind`.
   *
   * @return The provider name string (e.g. `"openai"`, `"anthropic"`).
   */
  extension (kind: ProviderKind)
    def name: String =
      kind match
        case ProviderKind.OpenAI     => "openai"
        case ProviderKind.OpenRouter => "openrouter"
        case ProviderKind.Requesty   => "requesty"
        case ProviderKind.Azure      => "azure"
        case ProviderKind.Anthropic  => "anthropic"
        case ProviderKind.Ollama     => "ollama"
        case ProviderKind.Zai        => "zai"
        case ProviderKind.Gemini     => "gemini"
        case ProviderKind.DeepSeek   => "deepseek"
        case ProviderKind.Cohere     => "cohere"
        case ProviderKind.Mistral    => "mistral"
        case ProviderKind.VertexAI   => "vertexai"
