package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, Llm4sProviderModule, ProviderDescriptor }

/**
 * The providers `llm4s-core` ships.
 *
 * This list is the only place core enumerates providers, and it exists because
 * every client still lives in core. It reaches the registry the same way a
 * third-party module does - through `BuiltinProviderModule` and core's own
 * `META-INF/services` entry - so the built-ins have no privileged path. Slice 5 of
 * [[https://github.com/llm4s/llm4s/issues/1126 #1126]] moves each client into
 * its own module with its own `Llm4sProviderModule`, and this list shrinks
 * to whatever is left. Ollama has gone: it is `llm4s-ollama`'s
 * `Llm4sOllamaModule` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 * So have Gemini and Vertex AI: they are `llm4s-gemini`'s `Llm4sGeminiModule`.
 * And Anthropic: it is `llm4s-anthropic`'s `Llm4sAnthropicModule`. And OpenAI,
 * Azure and Requesty, with OpenAI embeddings: they are `llm4s-openai`'s
 * `Llm4sOpenAIModule`.
 *
 * Nothing else should grow a provider list: a new provider is a new
 * `ProviderDescriptor` (or `EmbeddingProviderDescriptor`) plus an entry here,
 * and after slice 5 not even that.
 */
object BuiltinProviders extends Llm4sProviderModule:

  override val chatProviders: Seq[ProviderDescriptor] = Seq(
    OpenRouterProvider,
    ZaiProvider,
    DeepSeekProvider,
    CohereProvider,
    MistralProvider
  )

  override val embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(
    VoyageAIEmbeddingProvider
  )
