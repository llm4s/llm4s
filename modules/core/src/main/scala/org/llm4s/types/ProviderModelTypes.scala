package org.llm4s.types

/** Opaque newtypes and enumerations for type-safe representation of provider and model identifiers. */
object ProviderModelTypes:

  /** Opaque type wrapping a model name string. */
  opaque type ModelName = String

  /** Opaque type wrapping a base URL string for an LLM provider endpoint. */
  opaque type BaseUrl = String

  /** Opaque type wrapping an API key string. */
  opaque type ApiKey = String

  /** Opaque type wrapping a provider name string. */
  opaque type ProviderName = String

  /** Companion for the `ModelName` opaque type. */
  object ModelName:
    /**
     * Wraps a raw string as a `ModelName`.
     *
     * @param value The model name string.
     * @return The wrapped `ModelName`.
     */
    def apply(value: String): ModelName = value

  /** Companion for the `BaseUrl` opaque type. */
  object BaseUrl:
    /**
     * Wraps a raw string as a `BaseUrl`.
     *
     * @param value The base URL string.
     * @return The wrapped `BaseUrl`.
     */
    def apply(value: String): BaseUrl = value

  /** Companion for the `ApiKey` opaque type. */
  object ApiKey:
    /**
     * Wraps a raw string as an `ApiKey`.
     *
     * @param value The API key string.
     * @return The wrapped `ApiKey`.
     */
    def apply(value: String): ApiKey = value

  /** Companion for the `ProviderName` opaque type. */
  object ProviderName:
    /**
     * Wraps a raw string as a `ProviderName`.
     *
     * @param value The provider name string.
     * @return The wrapped `ProviderName`.
     */
    def apply(value: String): ProviderName = value

  /**
   * Unwraps a `ModelName` to its underlying string.
   *
   * @return The raw model name string.
   */
  extension (value: ModelName) def asString: String = value

  /**
   * Unwraps a `BaseUrl` to its underlying URL string.
   *
   * @return The raw base URL string.
   */
  extension (value: BaseUrl) def asUrl: String = value

  /**
   * Unwraps an `ApiKey` to its underlying key string.
   *
   * @return The raw API key string.
   */
  extension (value: ApiKey) def asKey: String = value

  /**
   * Unwraps a `ProviderName` to its underlying name string.
   *
   * @return The raw provider name string.
   */
  extension (value: ProviderName) def asName: String = value

  /** Enumeration of all supported LLM provider kinds. */
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

  /** Companion object with lookup utilities for `ProviderKind`. */
  object ProviderKind:
    /** All known provider kinds in a fixed sequence. */
    val all: Seq[ProviderKind] = Seq(
      ProviderKind.OpenAI,
      ProviderKind.OpenRouter,
      ProviderKind.Azure,
      ProviderKind.Anthropic,
      ProviderKind.Ollama,
      ProviderKind.Zai,
      ProviderKind.Gemini,
      ProviderKind.DeepSeek,
      ProviderKind.Cohere,
      ProviderKind.Mistral
    )

    /**
     * Parses a `ProviderKind` from a case-insensitive provider name string.
     *
     * @param value The provider name string (e.g. `"openai"`, `"anthropic"`, `"google"`).
     * @return `Some(ProviderKind)` if recognised, `None` otherwise.
     */
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
        case ProviderKind.Azure      => "azure"
        case ProviderKind.Anthropic  => "anthropic"
        case ProviderKind.Ollama     => "ollama"
        case ProviderKind.Zai        => "zai"
        case ProviderKind.Gemini     => "gemini"
        case ProviderKind.DeepSeek   => "deepseek"
        case ProviderKind.Cohere     => "cohere"
        case ProviderKind.Mistral    => "mistral"
