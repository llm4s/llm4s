package org.llm4s.testkit.client

import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

/**
 * A scriptable [[LLMClient]] test double for unit-testing agent and tool code without a provider:
 * each call to [[complete]] or [[streamComplete]] consumes the next scripted result, in the order
 * given to [[ScriptedLLMClient.returning]] or [[ScriptedLLMClient.respondingWith]], regardless of
 * which of the two methods it is called through. A call past the end of the script fails with
 * `Left(SimpleError(...))` rather than looping back to the start or repeating the last result - a
 * silent fallback would hide an agent-loop bug instead of failing the test that depends on it.
 *
 * `streamComplete` emits the scripted [[Completion]]'s content as a single [[StreamedChunk]], not
 * token-by-token: this double scripts an agent's or tool's turn-taking, not incremental-chunk
 * handling. A test that needs chunk-level control implements [[LLMClient]] directly.
 *
 * Every conversation passed to either method is recorded, in call order, and readable via
 * [[requests]]. Safe to call from multiple threads: the script position and the request log are
 * both tracked with concurrency-safe primitives, so streaming the same client from parallel fibers
 * never serves one scripted result twice.
 */
final class ScriptedLLMClient private (
  script: IndexedSeq[Result[Completion]],
  contextWindow: Int,
  reserveCompletion: Int
) extends LLMClient {

  private val position = new AtomicInteger(0)
  private val recorded  = new CopyOnWriteArrayList[Conversation]()

  /** Every conversation passed to [[complete]] or [[streamComplete]] so far, in call order. */
  def requests: List[Conversation] = recorded.asScala.toList

  /** How many scripted results remain unconsumed. */
  def remaining: Int = math.max(0, script.length - position.get())

  def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
    recorded.add(conversation)
    next()
  }

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    recorded.add(conversation)
    next().map { completion =>
      onChunk(StreamedChunk(id = completion.id, content = Some(completion.content)))
      completion
    }
  }

  def getContextWindow(): Int     = contextWindow
  def getReserveCompletion(): Int = reserveCompletion

  private def next(): Result[Completion] = {
    val index = position.getAndIncrement()
    if (index < script.length) script(index)
    else
      Left(
        SimpleError(s"ScriptedLLMClient: no scripted response for call ${index + 1}; script has ${script.length}")
      )
  }
}

object ScriptedLLMClient {

  private val DefaultContextWindow     = 4096
  private val DefaultReserveCompletion = 256

  /** A [[ScriptedLLMClient]] answering each call with the next completion, in order. */
  def returning(completions: Completion*): ScriptedLLMClient =
    respondingWith(completions.map(Right(_)): _*)

  /**
   * A [[ScriptedLLMClient]] whose completions are plain text: each string becomes an
   * [[AssistantMessage]] with that content, under id `scripted-<n>`.
   */
  def returningText(contents: String*): ScriptedLLMClient =
    returning(contents.zipWithIndex.map { case (content, i) =>
      Completion(id = s"scripted-$i", created = 0L, content = content, model = "scripted", message = AssistantMessage(content))
    }: _*)

  /**
   * A [[ScriptedLLMClient]] answering each call with the next scripted result, in order - a
   * `Left` scripts a failed call, for testing an agent's or tool's error-handling and retry paths.
   */
  def respondingWith(results: Result[Completion]*): ScriptedLLMClient =
    new ScriptedLLMClient(results.toIndexedSeq, DefaultContextWindow, DefaultReserveCompletion)
}
