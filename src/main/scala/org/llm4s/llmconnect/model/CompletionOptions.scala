package org.llm4s.llmconnect.model

import org.llm4s.toolapi.ToolFunction

/**
 * Represents options for a completion request.
 *
 * @param temperature Controls the randomness of the output. Higher values make the output more random.
 * @param topP Controls the diversity of the output. Lower values make the output more focused.
 * @param maxTokens Optional maximum number of tokens to generate in the completion.
 * @param presencePenalty Penalizes new tokens based on whether they appear in the text so far, encouraging new topics.
 * @param frequencyPenalty Penalizes new tokens based on their existing frequency in the text so far, discouraging repetition.
 * @param tools Optional sequence of tool function definitions that can be requested by the LLM during a completion.
 */
case class CompletionOptions(
  temperature: Double = 0.7,
  topP: Double = 1.0,
  maxTokens: Option[Int] = None,
  presencePenalty: Double = 0.0,
  frequencyPenalty: Double = 0.0,
  tools: Seq[ToolFunction[_, _]] = Seq.empty
)
