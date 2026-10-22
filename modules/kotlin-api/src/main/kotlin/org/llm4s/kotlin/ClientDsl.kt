package org.llm4s.kotlin

import org.llm4s.javaapi.AnthropicClientBuilder
import org.llm4s.javaapi.AzureOpenAiClientBuilder
import org.llm4s.javaapi.GeminiClientBuilder
import org.llm4s.javaapi.OllamaClientBuilder
import org.llm4s.javaapi.OpenAiClientBuilder
import org.llm4s.javaapi.OpenAiCompatibleClientBuilder
import org.llm4s.metrics.MetricsCollector
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import org.llm4s.javaapi.Llm4s as JLlm4s

/**
 * What every provider's client block of [Llm4s] sets (#1495): the receiver of [Llm4s.openAi], [Llm4s.anthropic],
 * [Llm4s.gemini], [Llm4s.ollama], [Llm4s.openAiCompatible] and [Llm4s.azureOpenAi]. Each block is turned into the Java
 * facade's builder for that provider, so a field means what it means there; a field left `null` keeps the default the
 * library's own configuration gives it.
 */
@Llm4sDsl
sealed class ClientSpec {
    /** The model to call - required. */
    var model: String? = null

    /** The model's context window in tokens, at least 1, instead of the one the library knows for the model. */
    var contextWindow: Int? = null

    /** The tokens of the context window kept for the reply, at least 0, instead of the library's figure. */
    var reserveCompletion: Int? = null

    /** How long a call may take, more than zero; `null` for the provider's default. */
    var requestTimeout: Duration? = null

    /** How long a streamed call may go without a chunk, more than zero; `null` for the provider's default. */
    var streamTimeout: Duration? = null

    /** Where the client reports every call - outcome, duration, tokens and cost; `null` records nothing. */
    var metrics: MetricsCollector? = null

    internal val requestTimeoutJava: java.time.Duration? get() = requestTimeout?.toJavaDuration()
    internal val streamTimeoutJava: java.time.Duration? get() = streamTimeout?.toJavaDuration()
}

/** The receiver of [Llm4s.openAi]: `apiKey` and `model` are required. */
class OpenAiSpec internal constructor() : ClientSpec() {
    /** The API key - required. */
    var apiKey: String? = null

    /** The API's base URL, when not OpenAI's own `https://api.openai.com/v1`. */
    var baseUrl: String? = null

    /** The OpenAI organization ID, sent as the `OpenAI-Organization` header. */
    var organization: String? = null

    internal fun toBuilder(): OpenAiClientBuilder {
        var builder = JLlm4s.openAi().apiKey(apiKey).model(model).baseUrl(baseUrl).organization(organization)
            .requestTimeout(requestTimeoutJava).streamTimeout(streamTimeoutJava).metrics(metrics)
        contextWindow?.let { builder = builder.contextWindow(it) }
        reserveCompletion?.let { builder = builder.reserveCompletion(it) }
        return builder
    }
}

/** The receiver of [Llm4s.anthropic]: `apiKey` and `model` are required. */
class AnthropicSpec internal constructor() : ClientSpec() {
    /** The API key - required. */
    var apiKey: String? = null

    /** The API's base URL, when not Anthropic's own. */
    var baseUrl: String? = null

    internal fun toBuilder(): AnthropicClientBuilder {
        var builder = JLlm4s.anthropic().apiKey(apiKey).model(model).baseUrl(baseUrl)
            .requestTimeout(requestTimeoutJava).streamTimeout(streamTimeoutJava).metrics(metrics)
        contextWindow?.let { builder = builder.contextWindow(it) }
        reserveCompletion?.let { builder = builder.reserveCompletion(it) }
        return builder
    }
}

/** The receiver of [Llm4s.gemini]: `apiKey` and `model` are required. */
class GeminiSpec internal constructor() : ClientSpec() {
    /** The API key - required. */
    var apiKey: String? = null

    /** The API's base URL, when not Google's own. */
    var baseUrl: String? = null

    internal fun toBuilder(): GeminiClientBuilder {
        var builder = JLlm4s.gemini().apiKey(apiKey).model(model).baseUrl(baseUrl)
            .requestTimeout(requestTimeoutJava).streamTimeout(streamTimeoutJava).metrics(metrics)
        contextWindow?.let { builder = builder.contextWindow(it) }
        reserveCompletion?.let { builder = builder.reserveCompletion(it) }
        return builder
    }
}

/** The receiver of [Llm4s.ollama]: `model` is required, and the server is `http://localhost:11434` unless set. */
class OllamaSpec internal constructor() : ClientSpec() {
    /** The Ollama server, when not `http://localhost:11434`. */
    var baseUrl: String? = null

    internal fun toBuilder(): OllamaClientBuilder {
        var builder = JLlm4s.ollama().model(model).baseUrl(baseUrl)
            .requestTimeout(requestTimeoutJava).streamTimeout(streamTimeoutJava).metrics(metrics)
        contextWindow?.let { builder = builder.contextWindow(it) }
        reserveCompletion?.let { builder = builder.reserveCompletion(it) }
        return builder
    }
}

/** The receiver of [Llm4s.openAiCompatible]: a server speaking OpenAI's chat completions API; see its builder. */
class OpenAiCompatibleSpec internal constructor() : ClientSpec() {
    /** The API key, when the server needs one. */
    var apiKey: String? = null

    /** The server's base URL - required. */
    var baseUrl: String? = null

    private val headers = linkedMapOf<String, String>()

    /** A header sent with every request, such as a gateway's tenant id; setting one again replaces it. */
    fun header(name: String, value: String) {
        headers[name] = value
    }

    internal fun toBuilder(): OpenAiCompatibleClientBuilder {
        var builder = JLlm4s.openAiCompatible().apiKey(apiKey).model(model).baseUrl(baseUrl)
            .requestTimeout(requestTimeoutJava).streamTimeout(streamTimeoutJava).metrics(metrics)
        contextWindow?.let { builder = builder.contextWindow(it) }
        reserveCompletion?.let { builder = builder.reserveCompletion(it) }
        headers.forEach { (name, value) -> builder = builder.header(name, value) }
        return builder
    }
}

/** The receiver of [Llm4s.azureOpenAi]: `endpoint`, `apiKey` and `model` (the deployment) are required. */
class AzureOpenAiSpec internal constructor() : ClientSpec() {
    /** The Azure OpenAI resource's endpoint, such as `https://my-resource.openai.azure.com` - required. */
    var endpoint: String? = null

    /** The API key - required. */
    var apiKey: String? = null

    /** The Azure OpenAI API version, when not the library's default. */
    var apiVersion: String? = null

    internal fun toBuilder(): AzureOpenAiClientBuilder {
        var builder = JLlm4s.azureOpenAi().endpoint(endpoint).apiKey(apiKey).model(model).apiVersion(apiVersion)
            .requestTimeout(requestTimeoutJava).streamTimeout(streamTimeoutJava).metrics(metrics)
        contextWindow?.let { builder = builder.contextWindow(it) }
        reserveCompletion?.let { builder = builder.reserveCompletion(it) }
        return builder
    }
}
