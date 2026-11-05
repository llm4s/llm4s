package org.llm4s.llmconnect.spi

import org.llm4s.annotation.Stable

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
 * @param streaming        whether `LLMClient.streamComplete` is implemented.
 * @param toolCalling      whether the provider accepts tool/function definitions.
 * @param customHttpClient whether the client sends its requests through the HTTP client supplied with
 *                         `LlmClientOptions.withHttpClient`; `false` for a provider built on a vendor SDK,
 *                         which brings its own transport and ignores it
 *                         ([[https://github.com/llm4s/llm4s/issues/2341 #2341]]).
 */
@Stable
final case class ProviderFeatures private (
  streaming: Boolean,
  toolCalling: Boolean,
  customHttpClient: Boolean
) {
  def withStreaming(streaming: Boolean): ProviderFeatures               = copy(streaming = streaming)
  def withToolCalling(toolCalling: Boolean): ProviderFeatures           = copy(toolCalling = toolCalling)
  def withCustomHttpClient(customHttpClient: Boolean): ProviderFeatures = copy(customHttpClient = customHttpClient)
}

object ProviderFeatures:
  /** Creates a [[ProviderFeatures]]. Named arguments are the supported way to construct one. */
  def apply(
    streaming: Boolean = true,
    toolCalling: Boolean = true,
    customHttpClient: Boolean = true
  ): ProviderFeatures =
    new ProviderFeatures(streaming, toolCalling, customHttpClient)

  /** Creates a [[ProviderFeatures]] that honours a supplied HTTP client. */
  def apply(streaming: Boolean, toolCalling: Boolean): ProviderFeatures =
    new ProviderFeatures(streaming, toolCalling, true)

  /** Full interface support: streaming, tool calling and a supplied HTTP client. */
  val default: ProviderFeatures = ProviderFeatures()
