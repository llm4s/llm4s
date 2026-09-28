package org.llm4s.llmconnect.config

import org.llm4s.trace.TracingMode
import org.llm4s.util.Redaction

/**
 * Connection and metadata settings for the Langfuse tracing backend.
 *
 * Read from the `llm4s.tracing.langfuse` block, which `llm4s-observability`'s
 * `reference.conf` binds to `LANGFUSE_URL`, `LANGFUSE_PUBLIC_KEY`,
 * `LANGFUSE_SECRET_KEY`, `LANGFUSE_ENV`, `LANGFUSE_RELEASE` and `LANGFUSE_VERSION`.
 * With `TRACING_MODE=langfuse` that block reaches the backend as
 * `TracingSettings.extras` ([[LangfuseConfig.fromExtras]]); to read it whatever the
 * mode, use `org.llm4s.config.LangfuseConfigLoader`.
 *
 * This lived in `llm4s-core` until slice 6 (#1133), as `TracingSettings.langfuse`.
 * The package is unchanged.
 *
 * `toString` redacts both keys so that the config can be safely logged.
 *
 * @param url       Langfuse ingestion endpoint; defaults to [[LangfuseConfig.DEFAULT_URL]]
 * @param publicKey Langfuse public API key; the backend refuses to start without it
 * @param secretKey Langfuse secret API key; redacted in `toString`
 * @param env       deployment environment tag attached to every trace; defaults to `"production"`
 * @param release   application release identifier forwarded to Langfuse; defaults to `"1.0.0"`
 * @param version   SDK/integration version forwarded to Langfuse; defaults to `"1.0.0"`
 */
case class LangfuseConfig(
  url: String = LangfuseConfig.DEFAULT_URL,
  publicKey: Option[String] = None,
  secretKey: Option[String] = None,
  env: String = LangfuseConfig.DEFAULT_ENV,
  release: String = LangfuseConfig.DEFAULT_RELEASE,
  version: String = LangfuseConfig.DEFAULT_VERSION
) {
  override def toString: String =
    s"LangfuseConfig(url=$url, publicKey=${Redaction.secretOpt(publicKey)}, secretKey=${Redaction.secretOpt(secretKey)}, " +
      s"env=$env, release=$release, version=$version)"
}

object LangfuseConfig {

  /** The mode Langfuse is registered under: `TRACING_MODE=langfuse`. */
  val Mode: TracingMode = TracingMode.Named("langfuse")

  /** Langfuse Cloud's ingestion endpoint. This was `DefaultConfig.DEFAULT_LANGFUSE_URL` in `llm4s-core`. */
  val DEFAULT_URL = "https://cloud.langfuse.com/api/public/ingestion"

  /** This was `DefaultConfig.DEFAULT_LANGFUSE_ENV` in `llm4s-core`. */
  val DEFAULT_ENV = "production"

  /** This was `DefaultConfig.DEFAULT_LANGFUSE_RELEASE` in `llm4s-core`. */
  val DEFAULT_RELEASE = "1.0.0"

  /** This was `DefaultConfig.DEFAULT_LANGFUSE_VERSION` in `llm4s-core`. */
  val DEFAULT_VERSION = "1.0.0"

  /**
   * Builds the config from the `llm4s.tracing.langfuse` block, as
   * `TracingSettings.extras` carries it when `TRACING_MODE=langfuse`.
   *
   * Values are trimmed, and a blank one counts as unset, so that an empty
   * `LANGFUSE_PUBLIC_KEY=` does not pass for a key. Unset values take the defaults above.
   */
  def fromExtras(extras: Map[String, String]): LangfuseConfig = {
    def value(key: String): Option[String] = extras.get(key).map(_.trim).filter(_.nonEmpty)
    LangfuseConfig(
      url = value("url").getOrElse(DEFAULT_URL),
      publicKey = value("publicKey"),
      secretKey = value("secretKey"),
      env = value("env").getOrElse(DEFAULT_ENV),
      release = value("release").getOrElse(DEFAULT_RELEASE),
      version = value("version").getOrElse(DEFAULT_VERSION)
    )
  }
}
