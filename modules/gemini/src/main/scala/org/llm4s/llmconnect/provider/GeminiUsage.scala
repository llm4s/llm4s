package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.TokenUsage

import scala.util.Try

/**
 * The token usage in a Gemini or Vertex AI response's `usageMetadata`, which both APIs return in the same
 * form - on a `generateContent` response and on the chunks of a `streamGenerateContent` stream.
 *
 *  - `promptTokenCount` becomes `promptTokens`. It includes the cached tokens.
 *  - `candidatesTokenCount` becomes `completionTokens`, or 0 when absent, as it is when the model wrote nothing.
 *  - `thoughtsTokenCount` (a thinking model's reasoning, billed as output but not part of
 *    `candidatesTokenCount`) becomes `thinkingTokens`.
 *  - `cachedContentTokenCount` (the prompt tokens served from implicit or explicit context caching)
 *    becomes `cachedTokens`.
 *  - `totalTokenCount` becomes `totalTokens`; when absent, it is the prompt, candidate and thought counts summed.
 *
 * There is no cache-write count: Gemini bills cache storage, not cache writes. Usage without a
 * `promptTokenCount` is not read.
 */
private[provider] object GeminiUsage {

  def fromResponse(json: ujson.Value): Option[TokenUsage] =
    Try(json("usageMetadata")).toOption.flatMap(parse)

  def parse(usage: ujson.Value): Option[TokenUsage] = {
    def count(key: String): Option[Int] =
      Try(usage(key).num).toOption.filter(n => n >= 0 && n <= Int.MaxValue).map(_.toInt)

    count("promptTokenCount").map { prompt =>
      val completion = count("candidatesTokenCount").getOrElse(0)
      val thoughts   = count("thoughtsTokenCount")
      TokenUsage(
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = count("totalTokenCount").getOrElse(prompt + completion + thoughts.getOrElse(0)),
        thinkingTokens = thoughts,
        cachedTokens = count("cachedContentTokenCount")
      )
    }
  }
}
