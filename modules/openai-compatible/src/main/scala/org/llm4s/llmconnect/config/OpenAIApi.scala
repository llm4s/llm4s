package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

/** The OpenAI HTTP API a client speaks: `/chat/completions` or `/responses`. */
@Stable
enum OpenAIApi:
  case ChatCompletions, Responses

object OpenAIApi:
  private val ChatCompletionsValue = "chat-completions"
  private val ResponsesValue       = "responses"

  /** Reads a config value, exactly `chat-completions` or `responses`. */
  private[llm4s] def parse(value: String): Result[OpenAIApi] =
    value match
      case ChatCompletionsValue => Right(ChatCompletions)
      case ResponsesValue       => Right(Responses)
      case other =>
        Left(
          ConfigurationError(
            s"Unknown OpenAI api '$other': expected '$ResponsesValue' or '$ChatCompletionsValue'",
            List(OpenAIConfig.ApiKey)
          )
        )

  /** The config value [[parse]] reads back. */
  private[llm4s] def configValue(api: OpenAIApi): String =
    api match
      case ChatCompletions => ChatCompletionsValue
      case Responses       => ResponsesValue

/** The reasoning summary the Responses API is asked to return. */
@Stable
enum OpenAIReasoningSummary:
  case Auto, Concise, Detailed

object OpenAIReasoningSummary:
  /** Reads a config value: `auto`, `concise` or `detailed`. */
  private[llm4s] def parse(value: String): Result[OpenAIReasoningSummary] =
    value match
      case "auto"     => Right(Auto)
      case "concise"  => Right(Concise)
      case "detailed" => Right(Detailed)
      case other =>
        Left(
          ConfigurationError(
            s"Unknown OpenAI reasoningSummary '$other': expected 'auto', 'concise' or 'detailed'",
            List(OpenAIConfig.ReasoningSummaryKey)
          )
        )
