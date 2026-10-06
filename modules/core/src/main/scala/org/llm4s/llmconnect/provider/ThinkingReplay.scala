package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ AssistantMessage, CompletionOptions, Message, SystemMessage }
import upickle.default.write

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Binds sealed thinking to the request it was produced for, and replays it only while that request
 * is unchanged - for providers that sign thinking (Anthropic, Bedrock Converse).
 *
 * Anthropic validates a thinking block against everything sent before it: the top-level system
 * prompt, the tools and every earlier message; if any of them changes, that block and every later
 * one are invalid and the request is rejected. Bedrock documents its reasoning signature as a hash
 * of all the messages in the conversation. A conversation in llm4s can be rewritten in many places
 * before it is sent - pruned, compressed, summarised, edited, a handoff's view of it - so rather than
 * have each of them remember to unseal later turns, the client checks at the point of sending:
 *
 *  - [[bind]], when a completion arrives, records in [[AssistantMessage.thinkingBinding]] a
 *    [[fingerprint]] of the request that produced it;
 *  - [[replayable]], when a conversation is sent, unseals every assistant message whose binding does
 *    not match the fingerprint of the conversation before it now, and any sealed message with no
 *    binding.
 *
 * The fingerprint covers every system message in the conversation, wherever it sits (Anthropic and
 * Bedrock lift them all into one top-level system prompt, sent before every message), then every
 * message before the assistant message in order, system messages included in their positions
 * (OpenAI-compatible clients such as OpenRouter send them inline, so `[system, user]` and
 * `[user, system]` are different prefixes), each with its own thinking and binding, so unsealing an
 * earlier turn changes the history of every later one and the replayed blocks never have a gap;
 * then the tools offered and the response format (which the Anthropic client writes into the system
 * prompt). One definition serves both layouts: for a client that lifts system messages, a system
 * message that only moves unseals turns it need not have, which costs only the replay, never a
 * rejected request. Everything else on the request - model, effort, token limits, sampling - is
 * outside what the providers check, and outside the fingerprint.
 *
 * Every client serialises a conversation as a deterministic function of exactly these inputs, so
 * an unchanged fingerprint means an unchanged wire prefix.
 */
private[llm4s] object ThinkingReplay {

  /**
   * `message` with its sealed thinking bound to `request`, the conversation it answers, as it was
   * sent: the fingerprint is taken over `replayable(request, options)`, the form the client sent.
   */
  def bind(message: AssistantMessage, request: Seq[Message], options: CompletionOptions): AssistantMessage =
    if (!message.hasSealedThinking) message.withThinkingBinding(None)
    else message.withThinkingBinding(Some(fingerprint(replayable(request, options), options)))

  /**
   * `messages` as they may be sent: every assistant message whose sealed thinking is bound to the
   * conversation before it as it is now kept as it is, every other sealed one unsealed (see
   * [[AssistantMessage.unsealed]]). Indices are unchanged.
   */
  def replayable(messages: Seq[Message], options: CompletionOptions): Seq[Message] =
    if (!messages.exists { case am: AssistantMessage => am.hasSealedThinking; case _ => false }) messages
    else {
      val digest = header(messages, options)
      messages.map { m =>
        val sent = m match {
          case am: AssistantMessage if am.hasSealedThinking =>
            val current = hex(digest.clone().asInstanceOf[MessageDigest])
            if (am.thinkingBinding.contains(current)) am else am.unsealed
          case other => other
        }
        // the history of later messages is what was sent, so an unsealed turn changes it
        feed(digest, sent)
        sent
      }
    }

  /** The fingerprint of `messages`, as sent, as the history before a new assistant message. */
  private def fingerprint(messages: Seq[Message], options: CompletionOptions): String = {
    val digest = header(messages, options)
    messages.foreach(feed(digest, _))
    hex(digest)
  }

  // every system message (the lifted system prompt), the tools and the response format, then a separator
  private def header(messages: Seq[Message], options: CompletionOptions): MessageDigest = {
    val digest = MessageDigest.getInstance("SHA-256")
    messages.foreach { case s: SystemMessage => feed(digest, s); case _ => () }
    update(digest, "\u0000tools")
    options.tools.foreach(t => update(digest, t.toOpenAITool(strict = false).render()))
    update(digest, "\u0000format")
    options.responseFormat.foreach(f => update(digest, f.toString))
    update(digest, "\u0000messages")
    digest
  }

  private def feed(digest: MessageDigest, message: Message): Unit = update(digest, write(message))

  // each part length-prefixed, so no two different sequences of parts hash alike
  private def update(digest: MessageDigest, part: String): Unit = {
    val bytes = part.getBytes(StandardCharsets.UTF_8)
    digest.update(s"${bytes.length}:".getBytes(StandardCharsets.UTF_8))
    digest.update(bytes)
  }

  private def hex(digest: MessageDigest): String = digest.digest().map(b => f"$b%02x").mkString
}
