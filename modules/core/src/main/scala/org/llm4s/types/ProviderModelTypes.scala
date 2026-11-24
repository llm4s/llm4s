package org.llm4s.types

import org.llm4s.annotation.Stable

import java.util.Locale

/**
 * Type-safe identifier types for the multi-provider configuration system.
 *
 * Each identifier is an opaque type over `String`, giving distinct compile-time
 * types to values that are otherwise interchangeable raw strings. This makes it a
 * compile error to pass, for example, an [[ApiKey]] where a [[ModelName]] is
 * expected, at zero runtime cost.
 */
@Stable
object ProviderModelTypes:

  /** A model identifier, e.g. `"gpt-4o"` or `"claude-sonnet-4-5"`. */
  opaque type ModelName = String

  /** The base URL of a provider's API endpoint, e.g. `"https://api.openai.com/v1"`. */
  opaque type BaseUrl = String

  /** A secret key used to authenticate with a provider. Prints as `***`; registered with llm4s for redaction. */
  opaque type ApiKey = org.llm4s.security.Secret

  /** A provider identifier, e.g. `"openai"` or `"anthropic"`. */
  opaque type ProviderName = String

  /** Companion for the [[ModelName]] opaque type. */
  object ModelName:
    /** Wraps a raw string as a [[ModelName]]. */
    def apply(value: String): ModelName = value

    /** Writes a [[ModelName]] as its JSON string and reads one back, so a stored model id is a plain string. */
    given upickle.default.ReadWriter[ModelName] = IdCodecs.modelName

    /** Orders model names as their strings do. */
    given Ordering[ModelName] = Ordering.String

  /** Companion for the [[BaseUrl]] opaque type. */
  object BaseUrl:
    /** Wraps a raw string as a [[BaseUrl]]. */
    def apply(value: String): BaseUrl = value

  /** Companion for the [[ApiKey]] opaque type. */
  object ApiKey:
    /** Wraps a raw string as an [[ApiKey]], registering it for redaction. */
    def apply(value: String): ApiKey = org.llm4s.security.Secret(value)

  /** Companion for the [[ProviderName]] opaque type. */
  object ProviderName:
    /** Wraps a raw string as a [[ProviderName]]. */
    def apply(value: String): ProviderName = value

  /** Returns the underlying model name string. */
  extension (value: ModelName) def asString: String = value

  /** Returns the underlying base URL string. */
  extension (value: BaseUrl) def asUrl: String = value

  /** Returns the underlying key string. Use only where the key is sent: a header, a URL, an SDK builder. */
  extension (value: ApiKey) def asKey: String = value.reveal

  /** Returns the underlying provider name string. */
  extension (value: ProviderName) def asName: String = value

  /**
   * The canonical identifier of an LLM provider implementation, e.g. `"openai"`.
   *
   * This is an open vocabulary, not an enumeration: any string names a provider,
   * and whether that provider can actually be resolved is decided at resolution
   * time, not at parse time. That is what allows a provider to be supplied by a
   * module `llm4s-core` has never heard of — see
   * [[https://github.com/llm4s/llm4s/issues/1131 #1131]].
   *
   * Values are canonicalised on construction to trimmed lowercase, so
   * `ProviderId(" OpenAI ")` and `ProviderId("openai")` are equal and share a
   * single spelling in error messages and config.
   */
  opaque type ProviderId = String

  /**
   * Companion for the [[ProviderId]] opaque type.
   *
   * The `asString` extension lives here rather than alongside the other
   * `as*` extensions above because every newtype in this object erases to
   * `String`: a second `asString` at that level would be a double definition
   * after erasure. Companion-scoped extensions are found through the opaque
   * type's implicit scope, so `id.asString` resolves without an extra import.
   */
  object ProviderId:
    /**
     * Canonicalises a raw provider string to a [[ProviderId]] (trimmed, lowercased).
     *
     * The fold is `Locale.ROOT`, not the default locale: under a Turkish or
     * Azerbaijani default, `"OpenAI".toLowerCase` yields `openaı` (dotless i),
     * which would break canonical equality against the registered `openai` in
     * exactly those environments and nowhere else.
     */
    def apply(raw: String): ProviderId = raw.trim.toLowerCase(Locale.ROOT)

    /**
     * Writes a [[ProviderId]] as its JSON string, and reads one back canonicalised, as [[apply]] does: a stored
     * provider is a plain string.
     */
    given upickle.default.ReadWriter[ProviderId] = IdCodecs.providerId

    /** Orders provider ids as their strings do. */
    given Ordering[ProviderId] = Ordering.String

    /** Returns the canonical provider identifier string, e.g. `"openai"`. */
    extension (id: ProviderId) def asString: String = id

/**
 * The ids' JSON codecs, built where the opaque types are opaque: inside [[ProviderModelTypes]] a `ModelName` is a
 * `String`, so `readwriter[String]` there would find the id's own codec and loop.
 *
 * An id is written as the JSON string it wraps, and is a JSON object key in a map keyed by it, as a `String` key is:
 * a `Map[ModelName, V]` writes as `{"gpt-4o": ...}`, never as an array of pairs.
 */
private[types] object IdCodecs:
  import ProviderModelTypes.*

  private def stringId[T](wrap: String => T, unwrap: T => String): upickle.default.ReadWriter[T] =
    val base = upickle.default.readwriter[String].bimap[T](unwrap, wrap)
    new upickle.default.ReadWriter.Delegate[T](base):
      def write0[V](out: upickle.core.Visitor[?, V], v: T): V = base.write0(out, v)
      override def isJsonDictKey: Boolean                     = true

  val modelName: upickle.default.ReadWriter[ModelName] = stringId(ModelName(_), _.asString)

  val providerId: upickle.default.ReadWriter[ProviderId] = stringId(ProviderId(_), _.asString)
