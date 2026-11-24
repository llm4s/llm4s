package org.llm4s.rag

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.trace.Tracing
import org.llm4s.types.Result

/**
 * Turns a chat follow-up into the standalone question a `RAG` searches with (#2733), so that "what about the second
 * one?" retrieves what it refers to. Set it with [[RAGConfig.withQuestionCondenser]]; the pipeline calls it only when
 * the history passed to `queryWithAnswer` (or its permission and streaming variants) is non-empty, and the model still
 * answers the original question. A `Left` fails the query.
 *
 * A function is one, so `withQuestionCondenser((question, history) => ...)` takes a lambda;
 * [[QuestionCondenser.llm]] is the built-in condenser that asks a model.
 */
@Experimental
trait QuestionCondenser {

  /**
   * The standalone question to search with.
   *
   * @param question the question asked
   * @param history  the conversation before it, oldest first; never empty when the pipeline calls it
   */
  def condense(question: String, history: Seq[Message]): Result[String]

  /**
   * This condenser with its LLM calls traced to `tracing`, as a `RAG` configured with `withTracing` uses it. The
   * default returns it unchanged; [[QuestionCondenser.llm]]'s calls are traced, tagged `rag.condense`.
   */
  def withTracing(tracing: Tracing): QuestionCondenser = this
}

object QuestionCondenser {

  /** The built-in condenser's system prompt: the standard condense-question instruction. */
  val DefaultSystemPrompt: String =
    """You rewrite the latest question in a conversation as a standalone question for a search engine.
      |Given the conversation and the follow-up question, return one question that can be understood without the
      |conversation: replace pronouns and references such as "it", "that" or "the second one" with what they refer
      |to. If the question is already standalone, return it unchanged.
      |
      |Rules:
      |- Return ONLY the standalone question, nothing else
      |- Do not answer the question
      |- Do not add information the conversation does not contain""".stripMargin

  /** How many of the latest history messages the built-in condenser sends, by default. */
  val DefaultMaxHistoryMessages: Int = 10

  /** The built-in condenser's completion options, by default: temperature 0, for a stable rewrite. */
  val DefaultOptions: CompletionOptions = CompletionOptions(temperature = Some(0.0))

  /**
   * The built-in condenser: one `client` call that rewrites the question as a standalone one, given the history.
   *
   * The history is sent as a transcript inside a single user message, after `systemPrompt`: the latest
   * `maxHistoryMessages` user and assistant messages with text (tool messages and tool-call-only assistant messages
   * are left out, as is anything older), each as `User:` or `Assistant:`, then the follow-up question.
   *
   * Failure: a failed call fails the query with the client's error, unchanged, so a rate limit is still a recoverable
   * `RateLimitError`; a blank reply falls back to the original question. To answer from the original question whenever the call
   * fails, wrap it: `QuestionCondenser((q, h) => condenser.condense(q, h).orElse(Right(q)))`.
   *
   * With a tracer on the `RAG` (`RAGConfig.withTracing`), each call is traced and its usage tagged `rag.condense`.
   *
   * @param client             the model to ask; a small, fast one is usually enough
   * @param options            the completion options; temperature 0 by default
   * @param systemPrompt       the instruction; [[DefaultSystemPrompt]] by default
   * @param maxHistoryMessages how many of the latest history messages to send; a value below 1 makes every call a
   *                           `ValidationError`
   */
  def llm(
    client: LLMClient,
    options: CompletionOptions = DefaultOptions,
    systemPrompt: String = DefaultSystemPrompt,
    maxHistoryMessages: Int = DefaultMaxHistoryMessages
  ): QuestionCondenser =
    new LLMQuestionCondenser(client, options, systemPrompt, maxHistoryMessages)

  /** A condenser from a function, for Java callers and where a lambda's type is not inferred. */
  def apply(condense: (String, Seq[Message]) => Result[String]): QuestionCondenser =
    (question, history) => condense(question, history)

  final private class LLMQuestionCondenser(
    client: LLMClient,
    options: CompletionOptions,
    systemPrompt: String,
    maxHistoryMessages: Int
  ) extends QuestionCondenser {

    override def withTracing(tracing: Tracing): QuestionCondenser =
      new LLMQuestionCondenser(
        RAGTracing.llm(client, tracing, RAGTracing.Condense),
        options,
        systemPrompt,
        maxHistoryMessages
      )

    def condense(question: String, history: Seq[Message]): Result[String] =
      if (maxHistoryMessages < 1)
        Left(ValidationError("maxHistoryMessages", s"must be at least 1, was $maxHistoryMessages"))
      else
        client
          .complete(
            Conversation(Seq(SystemMessage(systemPrompt), UserMessage(prompt(question, history)))),
            options
          )
          .map(_.content.trim)
          .map(standalone => if (standalone.isEmpty) question else standalone)

    private def prompt(question: String, history: Seq[Message]): String = {
      val turns = history
        .collect {
          case m: UserMessage if m.content.trim.nonEmpty      => s"User: ${m.content.trim}"
          case m: AssistantMessage if m.content.trim.nonEmpty => s"Assistant: ${m.content.trim}"
        }
        .takeRight(maxHistoryMessages)
      s"""Conversation:
         |${turns.mkString("\n")}
         |
         |Follow-up question: $question
         |
         |Standalone question:""".stripMargin
    }
  }
}
