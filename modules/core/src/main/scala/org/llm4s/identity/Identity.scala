package org.llm4s.identity

/**
 * Identifies an LLM runtime (provider backend) by its canonical name.
 *
 * Used to associate runtime-specific configuration and behaviour with a
 * named backend, e.g. for tokenizer selection. Constants for all supported
 * runtimes are defined on the companion object.
 *
 * @param name Canonical name string as expected by provider APIs (lower-case).
 */
case class RuntimeId(name: String)

//noinspection TypeAnnotation,ScalaUnusedSymbol
object RuntimeId {
  val AZURE     = RuntimeId("azure")
  val OPEN_AI   = RuntimeId("openai")
  val ANTHROPIC = RuntimeId("anthropic")
}

/**
 * Identifies a specific LLM model by its API name string.
 *
 * Model identifiers are forwarded verbatim to the provider's API. The
 * companion object lists constants for known OpenAI models; other models
 * (Anthropic, Gemini, Ollama, etc.) are referenced by passing the API
 * name string directly to [[RuntimeId]] and the relevant
 * [[org.llm4s.llmconnect.config.ProviderConfig]].
 *
 * @param name Model identifier as expected by the provider API
 *             (e.g. `"gpt-4o"`, `"o3-mini"`).
 */
case class ModelId(name: String)

//noinspection TypeAnnotation,ScalaUnusedSymbol
object ModelId {
  val GPT_4_5                    = ModelId("gpt-4.5")
  val GPT_4_5_PREVIEW_2025_02_27 = ModelId("gpt-4.5-preview-2025-02-27")
  val O3_MINI                    = ModelId("o3-mini")
  val O3_MINI_2025_01_31         = ModelId("o3-mini-2025-01-31")
  val GPT_4o                     = ModelId("gpt-4o")
  val GPT_4o_2024_11_20          = ModelId("gpt-4o-2024-11-20")
  val GPT_4o_2024_08_06          = ModelId("gpt-4o-2024-08-06")
  val GPT_4o_2024_05_13          = ModelId("gpt-4o-2024-05-13")
}

/**
 * Identifies a BPE tokenizer vocabulary by its canonical name.
 *
 * Tokenizer IDs are used by context-window estimation logic to select the
 * correct byte-pair-encoding vocabulary for a given model, so that prompt
 * and completion token counts are accurate without calling the provider API.
 * The mapping from [[ModelId]] to [[TokenizerId]] is maintained by the
 * context package.
 *
 * @param name Tokenizer vocabulary name as used by tiktoken and related
 *             libraries (e.g. `"cl100k_base"` for GPT-4 / GPT-3.5).
 */
case class TokenizerId(name: String)

//noinspection TypeAnnotation,ScalaUnusedSymbol
object TokenizerId {
  val R50K_BASE   = TokenizerId("r50k_base")   // gpt-3
  val P50K_BASE   = TokenizerId("p50k_base")
  val P50K_EDIT   = TokenizerId("p50k_edit")
  val CL100K_BASE = TokenizerId("cl100k_base") // gpt-4, gpt-3.5
  val O200K_BASE  = TokenizerId("o200k_base")  // gpt-4o
}
