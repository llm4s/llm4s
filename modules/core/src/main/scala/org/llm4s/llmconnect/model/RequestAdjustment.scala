package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import upickle.default.ReadWriter

/**
 * A [[CompletionOptions]] field that a provider or model may not honour as requested, named by
 * [[RequestAdjustment.option]]. Matching on it is checked by the compiler, so a match survives a
 * field rename. A field that becomes adjustable later adds a case.
 *
 * `Thinking` and `PromptCache` name the `CompletionOptions` fields RFC 0008 adds in its PRs 4 and 6. Until those
 * land no field is behind them, so no adjustment names them yet.
 */
@Stable
enum RequestOption derives ReadWriter {
  case Temperature, TopP, MaxTokens, PresencePenalty, FrequencyPenalty, Tools, ResponseFormat, Reasoning, Thinking,
    PromptCache
}

/**
 * What happened to one requested option that was not sent as asked (RFC 0008, #2115).
 *
 * A client sends what the provider and model can take and reports the rest on
 * [[Completion.adjustments]], instead of dropping it silently. A caller that must not run without a
 * particular option looks for that option in the list and treats its [[RequestAdjustment.Dropped]] as a
 * failure; `temperature` always has a value, so it is reported whenever it is not sent as given.
 *
 * Reported today: every option the [[org.llm4s.model.RequestTransformer]] drops or adjusts (Anthropic,
 * OpenAI, Gemini, Vertex AI, Bedrock); a `reasoning` that Gemini, Vertex AI, Bedrock, Ollama or the
 * standard OpenAI-compatible dialect does not send; the sampling options OpenAI leaves out of a reasoning
 * model's request; and the penalties xAI leaves out of one. Other options a client leaves out, such as
 * Anthropic's `top_p` and penalties, are not reported yet and follow under RFC 0008, so an empty list
 * does not prove every option was sent.
 *
 * `reason`, `requested` and `sent` are for people and logs; match on [[option]] and the case.
 */
@Stable
enum RequestAdjustment derives ReadWriter {

  /** The option was not sent: the provider or model cannot honour it. */
  case Dropped(option: RequestOption, reason: String)

  /** The option was sent with a different value, e.g. a temperature clamped to the model's range. */
  case Adjusted(option: RequestOption, requested: String, sent: String, reason: String)

  /** The option was not sent because the provider already does it, e.g. OpenAI's automatic prompt caching. */
  case NotNeeded(option: RequestOption, reason: String)

  /** The option this adjustment is about. */
  def option: RequestOption
}
