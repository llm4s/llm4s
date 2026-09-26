package org.llm4s.llmconnect.provider

import com.openai.core.ObjectMappers
import com.openai.core.http.StreamResponse
import com.openai.models.chat.completions.{ ChatCompletion, ChatCompletionChunk, ChatCompletionCreateParams }

import java.util.stream.Stream
import scala.jdk.CollectionConverters._

/**
 * Builds `openai-java` response objects from JSON, as the SDK itself does, and stub
 * transports over them, so `OpenAIClient` specs never touch the network.
 */
object OpenAISdkFixtures {

  def completion(json: String): ChatCompletion =
    ObjectMappers.jsonMapper().readValue(json, classOf[ChatCompletion])

  def chunk(json: String): ChatCompletionChunk =
    ObjectMappers.jsonMapper().readValue(json, classOf[ChatCompletionChunk])

  /** A stream over `chunks` that records whether it was closed. */
  final class StubStream(chunks: Seq[ChatCompletionChunk]) extends StreamResponse[ChatCompletionChunk] {
    @volatile var closed: Boolean = false

    override def stream(): Stream[ChatCompletionChunk] = chunks.asJava.stream()
    override def close(): Unit                         = closed = true
  }

  def stream(chunks: ChatCompletionChunk*): StubStream = new StubStream(chunks)

  /** A transport answering both calls from the given functions; either defaults to failing. */
  def transport(
    complete: ChatCompletionCreateParams => ChatCompletion = _ =>
      throw new UnsupportedOperationException("not used in this test"),
    streaming: ChatCompletionCreateParams => StreamResponse[ChatCompletionChunk] = _ =>
      throw new UnsupportedOperationException("not used in this test")
  ): OpenAIClientTransport =
    new OpenAIClientTransport {
      override def createChatCompletion(params: ChatCompletionCreateParams): ChatCompletion = complete(params)
      override def createChatCompletionStream(params: ChatCompletionCreateParams): StreamResponse[ChatCompletionChunk] =
        streaming(params)
    }
}
