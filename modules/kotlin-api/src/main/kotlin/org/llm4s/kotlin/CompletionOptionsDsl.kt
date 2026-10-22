package org.llm4s.kotlin

import org.llm4s.javaapi.JCompletionOptions
import org.llm4s.javaapi.JReasoningEffort

/**
 * The options of a completion request, declared in Kotlin (#1495): a [JCompletionOptions] of the Java facade, for
 * [LLMClientKt.complete].
 *
 * ```kotlin
 * val options = completionOptions {
 *     temperature = 0.2
 *     maxTokens = 200
 *     reasoning = JReasoningEffort.HIGH
 * }
 * val reply = client.complete("Summarise this", options)
 * ```
 *
 * A setting left unset keeps [base]'s value, or core's default without one (temperature `0.7`, top-p `1.0`, no
 * penalties, no token limit, reasoning level or thinking budget). A value no provider accepts - a negative
 * temperature, a top-p outside `0..1`, a token count below `1` - throws `IllegalArgumentException`, as the Java builder
 * does.
 */
fun completionOptions(base: JCompletionOptions? = null, configure: CompletionOptionsSpec.() -> Unit): JCompletionOptions =
    CompletionOptionsSpec().apply(configure).build(base)

/** The receiver of [completionOptions]: each setting is `null` until set, and an unset one is left as it was. */
@Llm4sDsl
class CompletionOptionsSpec internal constructor() {
    /** The sampling temperature, at least `0`: higher is more random. Reasoning models may ignore it. */
    var temperature: Double? = null

    /** Nucleus sampling, from `0` to `1`: only tokens within this cumulative probability are considered. */
    var topP: Double? = null

    /** The most tokens the completion may contain, at least `1`. */
    var maxTokens: Int? = null

    /** The penalty for a token that has appeared at all, encouraging new topics. */
    var presencePenalty: Double? = null

    /** The penalty for a token in proportion to how often it has appeared, discouraging repetition. */
    var frequencyPenalty: Double? = null

    /** The reasoning level to ask for. */
    var reasoning: JReasoningEffort? = null

    /** An explicit thinking-token budget, at least `1`, overriding the one [reasoning] implies. */
    var budgetTokens: Int? = null

    internal fun build(base: JCompletionOptions?): JCompletionOptions {
        var builder = base?.toBuilder() ?: JCompletionOptions.builder()
        temperature?.let { builder = builder.temperature(it) }
        topP?.let { builder = builder.topP(it) }
        maxTokens?.let { builder = builder.maxTokens(it) }
        presencePenalty?.let { builder = builder.presencePenalty(it) }
        frequencyPenalty?.let { builder = builder.frequencyPenalty(it) }
        reasoning?.let { builder = builder.reasoning(it) }
        budgetTokens?.let { builder = builder.budgetTokens(it) }
        return builder.build()
    }
}
