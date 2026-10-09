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
 *    [[fingerprint]] of the request that produced it and of the [[ReplayOrigin]] that produced it;
 *  - [[replayable]], when a conversation is sent, unseals every assistant message whose binding does
 *    not match the fingerprint of the conversation before it now, sent to the origin about to be
 *    called, and any sealed message with no binding.
 *
 * Both take the origin, so a client cannot bind or check without one. A signature, a redacted block
 * or an opaque reasoning item is meaningful only to the provider and model that produced it: a
 * conversation produced by one client and continued with another (Bedrock then Anthropic, or one
 * OpenRouter model then another) has the same messages and options, so without the origin in the
 * fingerprint the target would be sent a foreign signature. With it, a change of provider or model
 * unseals every earlier turn.
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
 * rejected request. Everything else on the request - effort, token limits, sampling, the endpoint -
 * is outside what the providers check, and outside the fingerprint.
 *
 * Every client serialises a conversation as a deterministic function of exactly these inputs, so
 * an unchanged fingerprint means an unchanged wire prefix.
 */
private[llm4s] object ThinkingReplay {

  /**
   * `message` with its sealed thinking bound to `origin`, the provider and model that produced it,
   * and to `request`, the conversation it answers, as it was sent: the fingerprint is taken over
   * `replayable(origin, request, options)`, the form the client sent.
   *
   * `origin` is the client's configured provider and model, the same origin [[replayable]] is
   * given when the next request is sent - never the model a response reports. See [[ReplayOrigin]].
   */
  def bind(
    origin: ReplayOrigin,
    message: AssistantMessage,
    request: Seq[Message],
    options: CompletionOptions
  ): AssistantMessage =
    if (!message.hasSealedThinking) message.withThinkingBinding(None)
    else message.withThinkingBinding(Some(fingerprint(origin, replayable(origin, request, options), options)))

  /**
   * `messages` as they may be sent to `origin`, the provider and model about to be called: every
   * assistant message whose sealed thinking is bound to that origin and to the conversation before
   * it as it is now kept as it is, every other sealed one unsealed (see
   * [[AssistantMessage.unsealed]]). Indices are unchanged.
   */
  def replayable(origin: ReplayOrigin, messages: Seq[Message], options: CompletionOptions): Seq[Message] =
    if (!messages.exists { case am: AssistantMessage => am.hasSealedThinking; case _ => false }) messages
    else {
      val digest = header(origin, messages, options)
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

  /**
   * `message` with its sealed thinking bound to `origin` alone, for a provider whose signatures
   * belong to the part that carries them rather than to the conversation prefix. Google's guidance
   * for Gemini thought signatures is the reverse of Anthropic's prefix rule: modified or trimmed
   * history must PRESERVE the signatures, and Gemini 3 answers HTTP 400 when the current turn's
   * required function-call signature is missing - so pruning or compressing earlier turns must not
   * unseal them. What still unseals: a change of provider or model ([[replayableOrigin]]), and any
   * edit to the carrying message itself ([[AssistantMessage.withContent]] and
   * [[AssistantMessage.withToolCalls]] unseal, and the client attaches a signature only to a part
   * that still spells what it did).
   */
  def bindOrigin(origin: ReplayOrigin, message: AssistantMessage): AssistantMessage =
    if (!message.hasSealedThinking) message.withThinkingBinding(None)
    else message.withThinkingBinding(Some(originFingerprint(origin)))

  /**
   * `messages` as they may be sent to `origin` under the origin-only binding of [[bindOrigin]]:
   * an assistant message keeps its sealed thinking exactly when its binding is `origin`'s own
   * fingerprint - wherever the conversation around it has gone - and is unsealed otherwise,
   * including when it carries a prefix binding from [[bind]]. Indices are unchanged.
   */
  def replayableOrigin(origin: ReplayOrigin, messages: Seq[Message]): Seq[Message] =
    if (!messages.exists { case am: AssistantMessage => am.hasSealedThinking; case _ => false }) messages
    else {
      val current = originFingerprint(origin)
      messages.map {
        case am: AssistantMessage if am.hasSealedThinking && !am.thinkingBinding.contains(current) => am.unsealed
        case other                                                                                 => other
      }
    }

  // domain-separated from the prefix fingerprint: an origin-only binding never equals a prefix one
  private def originFingerprint(origin: ReplayOrigin): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    update(digest, "\u0000origin-only")
    update(digest, origin.provider)
    update(digest, origin.model)
    hex(digest)
  }

  /** The fingerprint of `messages`, as sent, as the history before a new assistant message. */
  private def fingerprint(origin: ReplayOrigin, messages: Seq[Message], options: CompletionOptions): String = {
    val digest = header(origin, messages, options)
    messages.foreach(feed(digest, _))
    hex(digest)
  }

  // the origin, every system message (the lifted system prompt), the tools and the response format,
  // then a separator
  private def header(origin: ReplayOrigin, messages: Seq[Message], options: CompletionOptions): MessageDigest = {
    val digest = MessageDigest.getInstance("SHA-256")
    update(digest, "\u0000origin")
    update(digest, origin.provider)
    update(digest, origin.model)
    update(digest, "\u0000system")
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

/**
 * Who sealed thinking belongs to: the provider whose signature or reasoning format it is, and the
 * model that produced it. Part of every [[ThinkingReplay]] binding, so sealed thinking is replayed
 * only to the provider and model that produced it.
 *
 * `provider` is the client's provider id (`anthropic`, `bedrock`, `openrouter`): the authority that
 * checks the signature or reads the opaque data. Anthropic and Bedrock both serve Claude, but each
 * is its own authority here, so a conversation moved between them unseals.
 *
 * `model` is the configured model id: the one the client asks for, both when it binds a turn and
 * when it checks one before sending - never the model a response reports. Anthropic and Bedrock may
 * report an alias's resolved snapshot, and a router (OpenRouter's `openrouter/auto`, model
 * fallbacks) the model it chose for that request; binding on either would unseal every turn of a
 * tool loop, which Anthropic rejects when thinking is on and OpenRouter answers without the
 * reasoning it requires. The provider that resolved the alias or chose the route is the one the
 * replay goes back to. Changing the configured model unseals every earlier turn.
 *
 * The endpoint is deliberately not part of the origin: a proxy, gateway or regional endpoint in
 * front of the same provider forwards to the same signing authority, and moving between them
 * changes nothing the provider checks.
 */
final private[llm4s] case class ReplayOrigin(provider: String, model: String)
