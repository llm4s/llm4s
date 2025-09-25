package org.llm4s.llmconnect.config

import org.llm4s.config.DefaultConfig
import org.llm4s.trace.TracingMode

case class LangfuseConfig(
  url: String = DefaultConfig.DEFAULT_LANGFUSE_URL,
  publicKey: Option[String] = None,
  secretKey: Option[String] = None,
  env: String = DefaultConfig.DEFAULT_LANGFUSE_ENV,
  release: String = DefaultConfig.DEFAULT_LANGFUSE_RELEASE,
  version: String = DefaultConfig.DEFAULT_LANGFUSE_VERSION
)

case class TracingSettings(
  mode: TracingMode,
  langfuse: LangfuseConfig
)
