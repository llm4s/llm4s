package org.llm4s.agent.graph.middleware

import org.llm4s.agent.{ ContextPruning, ContextWindowConfig }
import org.llm4s.llmconnect.model.{ Message, MessageRole }
import org.llm4s.types.Result

/**
 * Prunes the messages each model call is sent to `config`, leaving the stored history whole. The
 * system prompt, which reaches the model call first, is kept out of pruning and stays first; it
 * is not counted against `maxMessages` or `maxTokens`. A tool call is never sent without its
 * results, nor a result without its call; the last `config.minRecentTurns` turns (at least the
 * current one, from the latest user message) are never pruned - the strategy runs on the history
 * before them, with what they leave of both limits - and the pruned history starts with a user
 * message, so a send exceeds a limit only when those turns alone do. [[ContextWindowConfig]] has the
 * order in which these hold.
 */
final class ContextWindowMiddleware(
  config: ContextWindowConfig,
  tokenCounter: Message => Int = ContextPruning.defaultTokenCounter,
  val id: MiddlewareId = MiddlewareId("context-window")
) extends AgentMiddleware:

  override def wrapModelCall(request: ModelRequest, context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] =
    val (system, others) = request.messages.partition(_.role == MessageRole.System)
    next(request.withMessages(system ++ ContextPruning.prune(others, config, tokenCounter).toVector))
