package org.llm4s.llmconnect.spi

/**
 * What a provider implementation can actually do, declared by the provider itself.
 *
 * This is a '''static''' declaration made by a `ProviderDescriptor`, not a
 * runtime discovery: it describes the client this build ships, so a caller can
 * ask "will streaming work here?" without making a call and reading the error.
 *
 * The flags default to `true` because a provider that omits them is claiming
 * the full interface; a provider that cannot honour part of it must say so, and
 * saying so is then visible to users rather than buried in a `Left` at call
 * time. Cohere and Mistral declared `streaming = false` until they moved onto
 * `llm4s-openai-compatible`'s shared client, which streams
 * ([[https://github.com/llm4s/llm4s/issues/925 #925]]).
 *
 * @param streaming   whether `LLMClient.streamComplete` is implemented.
 * @param toolCalling whether the provider accepts tool/function definitions.
 */
final case class ProviderFeatures(
  streaming: Boolean = true,
  toolCalling: Boolean = true
)

object ProviderFeatures:
  /** Full interface support: streaming and tool calling both implemented. */
  val default: ProviderFeatures = ProviderFeatures()
