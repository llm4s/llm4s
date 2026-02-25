package org.llm4s.llmconnect.middleware

import org.llm4s.error.InvalidInputError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import org.llm4s.types.Result

/**
 * Middleware that sanitizes and validates input prompts.
 *
 * Can enforce max length limits and reject inputs containing forbidden patterns.
 */
class InputSanitizationMiddleware(
  maxTotalCharacters: Int = 100_000,
  forbiddenPatterns: Seq[scala.util.matching.Regex] = Seq.empty
) extends LLMMiddleware {

  override def name: String = "input-sanitization"

  override def wrap(client: LLMClient): LLMClient = new MiddlewareClient(client) {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      validate(conversation) match {
        case Right(_) => next.complete(conversation, options)
        case Left(e)  => Left(e)
      }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: org.llm4s.llmconnect.model.StreamedChunk => Unit
    ): Result[Completion] =
      validate(conversation) match {
        case Right(_) => next.streamComplete(conversation, options, onChunk)
        case Left(e)  => Left(e)
      }

    private def validate(conversation: Conversation): Result[Unit] = {
      val allContent = conversation.messages.map(_.content).mkString

      for {
        _ <- Either.cond(
          allContent.length <= maxTotalCharacters,
          (),
          InvalidInputError("prompt", s"${allContent.length} chars", s"exceeds maximum allowed $maxTotalCharacters")
        )
        _ <- Either.cond(
          !forbiddenPatterns.exists(_.findFirstIn(allContent).isDefined),
          (),
          InvalidInputError("prompt", "content", "contains forbidden patterns/content.")
        )
      } yield ()
    }
  }
}
