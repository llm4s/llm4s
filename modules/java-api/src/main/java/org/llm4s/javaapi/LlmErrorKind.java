package org.llm4s.javaapi;

/**
 * What kind of failure an {@link LlmException} reports, as {@link LlmException#getKind()} reads it: one constant for
 * each way a Java caller is likely to branch, so a {@code switch} decides between retrying, reporting and fixing a
 * configuration without importing {@code org.llm4s.error}.
 *
 * <p>Each of the error classes in {@code org.llm4s.error} maps to exactly one kind, and a test fails when a class is
 * added there without a kind. An embedding provider's error response ({@code EmbeddingError}, from
 * {@link JEmbeddingClient}) has the kind of its HTTP status, as a {@code ServiceError} does, and is {@link #OTHER} when
 * it has none. An error the library does not know - one from another llm4s module, such as a speech error, or one
 * your own code implements - is {@link #OTHER}.
 *
 * <p>The kind says what went wrong; {@link LlmException#isRecoverable()} says separately whether trying again may
 * help, so a {@link #SERVICE} error can be either.
 *
 * <p>A Java enum, not a Scala 3 one: Java reads a Scala 3 enum's cases through its companion object, and a
 * {@code switch} that runs before that object is initialised finds them {@code null}.
 */
public enum LlmErrorKind {
  /** The provider rejected the credentials: a missing, invalid or unauthorised API key ({@code AuthenticationError}). */
  AUTHENTICATION,

  /** The provider, or a local limiter, refused the call for now ({@code RateLimitError}); see {@code getRetryAfter()}. */
  RATE_LIMIT,

  /** The call did not finish in time ({@code TimeoutError}). */
  TIMEOUT,

  /** The provider could not be reached, so no response arrived ({@code NetworkError}). */
  NETWORK,

  /**
   * The provider answered with an error ({@code ServiceError}, {@code APIError}, or an {@code EmbeddingError} with a
   * status); see {@code getStatusCode()}.
   */
  SERVICE,

  /** The request itself is wrong: a missing or invalid argument or field ({@code ValidationError}, {@code InvalidInputError}). */
  VALIDATION,

  /** The library is configured wrongly: a missing or invalid setting ({@code ConfigurationError}). */
  CONFIGURATION,

  /** The call was cancelled, for example because its thread was interrupted ({@code CancelledError}). */
  CANCELLED,

  /** Any other failure: a processing, context, tokenizer, execution or system error, or an error the library does not know. */
  OTHER
}
