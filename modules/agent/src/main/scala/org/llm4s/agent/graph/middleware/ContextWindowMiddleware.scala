package org.llm4s.agent.graph.middleware

import org.llm4s.agent.{ ContextPruning, ContextWindowConfig }
import org.llm4s.agent.graph.RunContext
import org.llm4s.llmconnect.model.{ Completion, Message, MessageRole }
import org.llm4s.types.Result

/**
 * Prunes the messages each model call is sent to `config`, leaving the stored history whole. The
 * system prompt, which reaches the model call first, is kept out of pruning and stays first; it
 * is not counted against `maxMessages` or `maxTokens`. A tool call is never sent without its
 * results, nor a result without its call; the current turn (from the latest user message) is
 * never pruned - the strategy runs on the history before it, with the budget the turn leaves - and
 * the pruned history starts with a user message, so a send may exceed the budget when the current
 * turn alone is larger.
 */
final class ContextWindowMiddleware(
  config: ContextWindowConfig,
  tokenCounter: Message => Int = ContextPruning.defaultTokenCounter,
  val id: MiddlewareId = MiddlewareId("context-window")
) extends AgentMiddleware:

  override def wrapModelCall(request: ModelRequest, context: RunContext)(
    next: ModelRequest => Result[Completion]
  ): Result[Completion] =
    val (system, others) = request.messages.partition(_.role == MessageRole.System)
    next(request.withMessages(system ++ ContextPruning.prune(others, config, tokenCounter).toVector))
