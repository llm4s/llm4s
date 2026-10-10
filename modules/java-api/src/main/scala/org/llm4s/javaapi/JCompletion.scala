package org.llm4s.javaapi

import org.llm4s.llmconnect.model.{ Completion, TokenUsage }

import java.util.{ Objects, Optional }
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * One model reply with what came with it, as `JLlmClient.completion` returns it: the text, the model that answered,
 * the tool calls it asked for, the tokens it used and its estimated cost.
 *
 * {{{
 * JCompletion reply = client.completion("What is 2+2?").get();
 * System.out.println(reply.model() + ": " + reply.content());
 * reply.usage().ifPresent(u -> System.out.println(u.promptTokens() + " in, " + u.completionTokens() + " out"));
 * reply.estimatedCost().ifPresent(cost -> System.out.println("$" + cost.toPlainString()));
 * reply.toolCalls().forEach(c -> System.out.println(c.name() + " " + c.argumentsJson()));
 * }}}
 *
 * A value: two are equal when every field is. `toString` prints the full text, as the Scala `Completion` does, so mind
 * what you log. Not `Serializable`.
 *
 * @param id the provider's id for this reply
 * @param content the reply's text; empty, never `null`, when the model only asked for tool calls
 * @param model the model that answered, as the provider names it - which may be more specific than the one configured
 * @param toolCalls the tool calls the model asked for, in order, as the agent's [[JMessage.toolCalls]] lists them; empty
 *                  when it asked for none. Unmodifiable.
 * @param usage the tokens the call used, or empty when the provider did not report them
 * @param estimatedCost the estimated cost of the call in USD, or empty when it is not known (the provider reported no
 *                      usage, or the model has no known pricing). The same figure an agent turn adds to its
 *                      [[JUsageSummary.totalCost]].
 * @param thinking the reasoning text the model reported, or empty when there was none
 */
final class JCompletion private (
  val id: String,
  val content: String,
  val model: String,
  val toolCalls: java.util.List[JToolCall],
  val usage: Optional[JTokenUsage],
  val estimatedCost: Optional[java.math.BigDecimal],
  val thinking: Optional[String]
) {

  private def fields: List[Any] = List(id, content, model, toolCalls, usage, estimatedCost, thinking)

  override def equals(other: Any): Boolean = other match {
    case that: JCompletion => fields == that.fields
    case _                 => false
  }

  override def hashCode: Int = Objects.hash(fields.map(_.asInstanceOf[AnyRef])*)

  override def toString: String = s"JCompletion($id, $model, ${toolCalls.size} tool calls: $content)"
}

object JCompletion {

  /** `completion` as Java and Kotlin callers read it. */
  private[javaapi] def of(completion: Completion): JCompletion =
    new JCompletion(
      completion.id,
      Option(completion.content).getOrElse(""),
      completion.model,
      java.util.List.copyOf(completion.toolCalls.map(JToolCall.of).asJava),
      completion.usage.map(JTokenUsage.of).toJava,
      // as core's `ModelUsage.add` converts it, so a reply's cost and an agent turn's total agree; a cost that is not a
      // number has no decimal value and reads as unknown
      completion.estimatedCost.filter(_.isFinite).map(BigDecimal.decimal(_).bigDecimal).toJava,
      completion.thinking.toJava
    )
}

/**
 * The tokens one model call used, as [[JCompletion.usage]] reports them. An agent turn's [[JUsageSummary]] adds these
 * up across its calls: its `inputTokens` are the sum of `promptTokens`, its `outputTokens` of `completionTokens`.
 *
 * A value: two are equal when every field is.
 *
 * @param promptTokens tokens sent: the conversation, the tools and the instructions
 * @param completionTokens tokens received in the reply
 * @param totalTokens the total the provider reported, normally `promptTokens + completionTokens`
 * @param thinkingTokens extended-thinking tokens, billed as output but counted apart from `completionTokens`; zero
 *                       where the provider reports none, as in [[JUsageSummary.thinkingTokens]]
 * @param cachedTokens input tokens served from the provider's prompt cache (a cache read), billed at the cheaper
 *                     cache-read rate; zero where the provider reports none
 * @param cacheCreationTokens input tokens written into the provider's prompt cache, billed at the cache-creation rate,
 *                            which is typically higher than the normal input rate; zero where the provider reports none
 */
final class JTokenUsage private (
  val promptTokens: Int,
  val completionTokens: Int,
  val totalTokens: Int,
  val thinkingTokens: Int,
  val cachedTokens: Int,
  val cacheCreationTokens: Int
) {

  private def fields: List[Int] =
    List(promptTokens, completionTokens, totalTokens, thinkingTokens, cachedTokens, cacheCreationTokens)

  override def equals(other: Any): Boolean = other match {
    case that: JTokenUsage => fields == that.fields
    case _                 => false
  }

  override def hashCode: Int = Objects.hash(fields.map(Int.box)*)

  override def toString: String =
    s"JTokenUsage($promptTokens prompt, $completionTokens completion, $totalTokens total, $thinkingTokens thinking, " +
      s"$cachedTokens cached, $cacheCreationTokens cache creation)"
}

object JTokenUsage {

  /** `usage` as Java and Kotlin callers read it: a count the provider did not report is zero. */
  private[javaapi] def of(usage: TokenUsage): JTokenUsage =
    new JTokenUsage(
      usage.promptTokens,
      usage.completionTokens,
      usage.totalTokens,
      usage.thinkingTokens.getOrElse(0),
      usage.cachedTokens.getOrElse(0),
      usage.cacheCreationTokens.getOrElse(0)
    )
}
