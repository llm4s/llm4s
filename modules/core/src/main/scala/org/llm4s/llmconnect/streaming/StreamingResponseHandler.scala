package org.llm4s.llmconnect.streaming

import org.llm4s.llmconnect.model._
import org.llm4s.error.{ LLMError, ServiceError }
import org.llm4s.types.Result
import ujson.Value

import scala.util.Try

/**
 * Handles streaming responses from LLM providers.
 * Manages the lifecycle of streaming, chunk accumulation, and error handling.
 */
trait StreamingResponseHandler {

  /**
   * Process a streaming chunk
   */
  def processChunk(chunk: String): Result[Option[StreamedChunk]]

  /**
   * Get the final completion after streaming is done
   */
  def getCompletion: Result[Completion]

  /**
   * Check if streaming is complete
   */
  def isComplete: Boolean

  /**
   * Handle error during streaming
   */
  def handleError(error: LLMError): Unit

  /**
   * Clean up resources
   */
  def cleanup(): Unit
}

/**
 * Base implementation of StreamingResponseHandler with common functionality
 */
abstract class BaseStreamingResponseHandler extends StreamingResponseHandler {

  protected val accumulator: StreamingAccumulator = StreamingAccumulator.create()
  protected var streamingError: Option[LLMError]  = None
  protected var complete                          = false

  override def isComplete: Boolean = complete

  override def handleError(error: LLMError): Unit = {
    streamingError = Some(error)
    complete = true
  }

  override def getCompletion: Result[Completion] =
    streamingError match {
      case Some(error) => Left(error)
      case None =>
        if (!complete) {
          Left(ServiceError(500, "streaming", "Streaming not yet complete"))
        } else {
          accumulator.toCompletion
        }
    }

  override def cleanup(): Unit = {
    // Base cleanup - can be overridden
    accumulator.clear()
    streamingError = None
    complete = false
  }

  protected def markComplete(): Unit =
    complete = true
}

/**
 * OpenAI-specific streaming response handler
 */
class OpenAIStreamingHandler extends BaseStreamingResponseHandler {

  private val sseParser = SSEParser.createStreamingParser()

  override def processChunk(chunk: String): Result[Option[StreamedChunk]] =
    scala.util
      .Try {
        sseParser.addChunk(chunk)

        var latestChunk: Option[StreamedChunk] = None
        while (sseParser.hasEvents)
          sseParser.nextEvent().foreach { event =>
            event.data.foreach { data =>
              if (data == "[DONE]") markComplete()
              else {
                Try(ujson.read(data)).toOption.foreach { json =>
                  val streamedChunk = parseOpenAIChunk(json)
                  streamedChunk.foreach { c =>
                    accumulator.addChunk(c)
                    latestChunk = Some(c)
                  }
                }
              }
            }
          }
        latestChunk
      }
      .toEither
      .left
      .map { e =>
        val error = ServiceError(500, "openai", s"Error processing OpenAI stream: ${e.getMessage}")
        handleError(error)
        error
      }

  private def parseOpenAIChunk(json: Value): Option[StreamedChunk] =
    Try {
      val choices = json("choices").arr
      if (choices.nonEmpty) {
        val choice = choices(0)
        val delta  = choice("delta")

        val content      = delta.obj.get("content").flatMap(_.strOpt)
        val finishReason = choice.obj.get("finish_reason").flatMap(_.strOpt)

        // Handle tool calls if present
        val toolCall = delta.obj.get("tool_calls").flatMap { toolCalls =>
          val calls = toolCalls.arr
          if (calls.nonEmpty) {
            val call = calls(0)
            Some(
              ToolCall(
                id = call.obj.get("id").flatMap(_.strOpt).getOrElse(""),
                name = call.obj.get("function").flatMap(_("name").strOpt).getOrElse(""),
                arguments = call.obj
                  .get("function")
                  .flatMap(_("arguments").strOpt)
                  .map(args => ujson.read(args))
                  .getOrElse(ujson.Null)
              )
            )
          } else None
        }

        // Mark complete if we have a finish reason
        if (finishReason.isDefined) {
          markComplete()
        }

        Some(
          StreamedChunk(
            id = json.obj.get("id").flatMap(_.strOpt).getOrElse(""),
            content = content,
            toolCall = toolCall,
            finishReason = finishReason
          )
        )
      } else {
        None
      }
    }.toOption.flatten
}

/**
 * Anthropic-specific streaming response handler
 */
class AnthropicStreamingHandler extends BaseStreamingResponseHandler {

  private val sseParser                        = SSEParser.createStreamingParser()
  private var currentMessageId: Option[String] = None

  override def processChunk(chunk: String): Result[Option[StreamedChunk]] =
    scala.util
      .Try {
        sseParser.addChunk(chunk)

        var latestChunk: Option[StreamedChunk] = None
        while (sseParser.hasEvents)
          sseParser.nextEvent().foreach { event =>
            event.event match {
              case Some("message_start") =>
                event.data.foreach { data =>
                  Try(ujson.read(data)).toOption.foreach { json =>
                    currentMessageId = json.obj.get("message").flatMap(_("id").strOpt)
                  }
                }
              case Some("content_block_delta") =>
                event.data.foreach { data =>
                  Try(ujson.read(data)).toOption.foreach { json =>
                    val chunk = parseAnthropicDelta(json)
                    chunk.foreach { c =>
                      accumulator.addChunk(c)
                      latestChunk = Some(c)
                    }
                  }
                }
              case Some("message_stop") => markComplete()
              case _                    =>
            }
          }
        latestChunk
      }
      .toEither
      .left
      .map { e =>
        val error = ServiceError(500, "anthropic", s"Error processing Anthropic stream: ${e.getMessage}")
        handleError(error)
        error
      }

  private def parseAnthropicDelta(json: Value): Option[StreamedChunk] =
    Try {
      val delta     = json("delta")
      val deltaType = delta("type").str

      deltaType match {
        case "text_delta" =>
          Some(
            StreamedChunk(
              id = currentMessageId.getOrElse(""),
              content = Some(delta("text").str),
              toolCall = None,
              finishReason = None
            )
          )

        case "input_json_delta" =>
          // Handle tool use deltas
          // This would accumulate JSON for tool calls
          None // Simplified for now

        case _ =>
          None
      }
    }.toOption.flatten
}

/**
 * Factory for creating provider-specific handlers
 */
object StreamingResponseHandler {

  def forProvider(provider: String): StreamingResponseHandler =
    provider.toLowerCase match {
      case "openai" | "azure" => new OpenAIStreamingHandler()
      case "anthropic"        => new AnthropicStreamingHandler()
      case "openrouter"       => new OpenAIStreamingHandler() // OpenRouter uses OpenAI format
      case _                  => throw new IllegalArgumentException(s"Unsupported streaming provider: $provider")
    }
}
