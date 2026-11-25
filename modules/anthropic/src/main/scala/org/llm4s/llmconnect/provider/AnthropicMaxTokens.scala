package org.llm4s.llmconnect.provider

/**
 * The `max_tokens` an Anthropic request carries when the caller set no `CompletionOptions.maxTokens`.
 *
 * The Messages API requires `max_tokens`, so there has to be a default, and whatever it is, it is a limit the caller
 * did not choose: the model stops there, mid-answer, with `FinishReason.Length`. Until 0.7.0 it was 2048 for every
 * model, which cut off ordinary answers, and on the models that think by default could be spent on thinking before
 * a word of the answer was written. The default now follows the model.
 *
 * '''A streamed request''' asks for the model's maximum output, from the model registry
 * (`ModelMetadata.maxOutputTokens`: 128,000 for the current models). A limit above what the answer needs costs
 * nothing: output is billed, and counted against the output rate limit, as it is produced, never by `max_tokens`.
 * On a model older than Claude 4.5 the API refuses a request whose prompt plus `max_tokens` exceeds the context
 * window (a newer one accepts it and stops at the window); set `maxTokens` for a prompt that long on such a model.
 *
 * '''A non-streamed request''' asks for the same, capped at [[NonStreamingCap]], because nothing of a non-streamed
 * reply can be read until all of it has been generated, inside one request timeout. With no `timeouts.request` in
 * the provider's section that timeout is the Anthropic Java SDK's, a flat 10 minutes (`Timeout.request`, SDK
 * 2.65.0). At 30 ms a token - the rate the SDK's own rule for sizing a non-streamed timeout assumes
 * (`RequestOptions.timeoutFromMaxTokensNonStreaming`) - 16,000 tokens take 8 minutes and 20,000 take all 10, so a
 * larger default would ask for a reply the call might not wait for, and fail with a timeout after paying for it.
 * Stream for a longer answer, or set `maxTokens` and a `timeouts.request` long enough for it.
 *
 * That SDK also has a check that refuses, with an `IllegalArgumentException` and before sending anything, a
 * non-streamed request it expects to run past 10 minutes, or one for more than 8,192 tokens from Claude Opus 4 or
 * 4.1 (`RequestOptions.applyDefaultTimeoutFromMaxTokens`). In 2.65.0 it never runs for a client built the way this
 * one is: it is skipped when the request already has a timeout, and the SDK client always has one, its default.
 * So no value of `max_tokens`, default or explicit, is refused today, and the cap above does not depend on the
 * check. `AnthropicMaxTokensSpec` sends such requests through the SDK and fails if an upgrade starts refusing them.
 *
 * '''A model the registry does not know''' gets [[UnknownModelDefault]], streamed or not: the largest value every
 * Claude model since Claude 3.5 accepts. A larger guess risks a 400 from a model whose maximum is smaller.
 *
 * An explicit `CompletionOptions.maxTokens` is always sent as given, on both kinds of request: none of this applies
 * to it.
 */
private[provider] object AnthropicMaxTokens {

  /** The default for a model whose maximum output the registry does not state. */
  val UnknownModelDefault: Int = 8192

  /** The most a non-streamed request asks for by default. */
  val NonStreamingCap: Int = 16000

  /**
   * The default `max_tokens`.
   *
   * @param modelMaxOutput the registry's maximum output tokens for the model, `None` when it does not know the
   *                       model or states none
   * @param streaming      whether the request is streamed
   */
  def default(modelMaxOutput: Option[Int], streaming: Boolean): Int = {
    val model = modelMaxOutput.filter(_ > 0).getOrElse(UnknownModelDefault)
    if (streaming) model else math.min(model, NonStreamingCap)
  }
}
