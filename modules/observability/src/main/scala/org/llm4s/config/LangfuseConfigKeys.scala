package org.llm4s.config

/**
 * Environment-variable names recognised by `llm4s-observability`, each bound under
 * `llm4s.tracing.langfuse` by this module's `reference.conf`.
 *
 * These were `ConfigKeys.LANGFUSE_*` in `llm4s-core` until Langfuse moved to this
 * module ([[https://github.com/llm4s/llm4s/issues/1133 #1133]]): a key belongs with
 * the code that reads it, so that `ConfigKeys` does not name variables for a
 * backend that may not be on the classpath.
 */
object LangfuseConfigKeys {

  /** Langfuse ingestion URL; bound to `llm4s.tracing.langfuse.url`. Defaults to Langfuse Cloud. */
  val LANGFUSE_URL = "LANGFUSE_URL"

  /** Langfuse public key (`pk-lf-...`); bound to `llm4s.tracing.langfuse.publicKey`. */
  val LANGFUSE_PUBLIC_KEY = "LANGFUSE_PUBLIC_KEY"

  /** Langfuse secret key (`sk-lf-...`); bound to `llm4s.tracing.langfuse.secretKey`. */
  val LANGFUSE_SECRET_KEY = "LANGFUSE_SECRET_KEY"

  /** Optional environment tag (e.g. `"production"`, `"staging"`); bound to `llm4s.tracing.langfuse.env`. */
  val LANGFUSE_ENV = "LANGFUSE_ENV"

  /** Optional release version tag; bound to `llm4s.tracing.langfuse.release`. */
  val LANGFUSE_RELEASE = "LANGFUSE_RELEASE"

  /** Optional SDK version override; bound to `llm4s.tracing.langfuse.version`. */
  val LANGFUSE_VERSION = "LANGFUSE_VERSION"
}
