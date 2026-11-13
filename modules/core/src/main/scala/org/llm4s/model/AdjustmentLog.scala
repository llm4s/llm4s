package org.llm4s.model

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
import org.slf4j.{ Logger, LoggerFactory }

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logs a client's [[RequestAdjustment]]s at WARN, once per (model, option), so a dropped option is
 * visible without repeating on every call (RFC 0008). One instance belongs to one client: the
 * record of what was logged goes with the client, and two clients never share it.
 *
 * The record is a thread-safe set capped at `capacity` entries, so a long-running process that sees
 * many model ids does not grow it without bound. Once it is full, one WARN says so and further first
 * sightings are logged at DEBUG. Every adjustment is still reported on its `Completion`, whatever
 * this log does.
 *
 * @param provider the client's provider name, for the log line
 */
final private[llm4s] class AdjustmentLog(
  provider: String,
  capacity: Int = AdjustmentLog.DefaultCapacity,
  logger: Logger = LoggerFactory.getLogger(classOf[AdjustmentLog])
) {

  private val seen     = ConcurrentHashMap.newKeySet[(String, RequestOption)]()
  private val fullOnce = new AtomicBoolean(false)

  /** Logs each of `adjustments` the first time this client sees its option for `model`. */
  def record(model: String, adjustments: List[RequestAdjustment]): Unit =
    adjustments.foreach { adjustment =>
      val key = (model, adjustment.option)
      if (!seen.contains(key)) {
        // check-then-add: concurrent first sightings can pass `capacity` by at most the number of racing threads
        if (seen.size < capacity) {
          if (seen.add(key)) logger.warn(s"$provider/$model: ${AdjustmentLog.describe(adjustment)}")
        } else {
          if (fullOnce.compareAndSet(false, true))
            logger.warn(
              s"$provider: logged request adjustments for $capacity (model, option) pairs; " +
                "further ones are logged at DEBUG and still reported on each Completion"
            )
          logger.debug(s"$provider/$model: ${AdjustmentLog.describe(adjustment)}")
        }
      }
    }
}

private[llm4s] object AdjustmentLog {

  /** How many (model, option) pairs one client logs at WARN. */
  val DefaultCapacity: Int = 256

  /** One adjustment as a log line. */
  def describe(adjustment: RequestAdjustment): String = adjustment match {
    case RequestAdjustment.Dropped(option, reason) => s"dropped $option: $reason"
    case RequestAdjustment.Adjusted(option, requested, sent, reason) =>
      s"adjusted $option from $requested to $sent: $reason"
    case RequestAdjustment.NotNeeded(option, reason) => s"did not send $option: $reason"
  }
}

/**
 * What a client reports for an option its own request mapping does not send, as opposed to one the
 * [[RequestTransformer]] drops (RFC 0008, #2115). The vendor logic stays in each client; this only
 * keeps the report the same everywhere.
 */
private[llm4s] object UnmappedOptions {

  /** A `Dropped` for `reasoning` when the caller set it, from a client that sends no reasoning setting. */
  def reasoning(model: String, options: CompletionOptions): List[RequestAdjustment] =
    options.reasoning.toList.map(_ =>
      RequestAdjustment.Dropped(RequestOption.Reasoning, s"reasoning effort is not sent to $model by this client")
    )

  /** A `Dropped` for `thinking` when the caller set it, from a client that sends no thinking setting. */
  def thinking(model: String, options: CompletionOptions): List[RequestAdjustment] =
    options.thinking.toList.map(_ =>
      RequestAdjustment.Dropped(RequestOption.Thinking, s"a thinking setting is not sent to $model by this client")
    )

  /** [[reasoning]] and [[thinking]]: the report of a client that maps neither. */
  def reasoningAndThinking(model: String, options: CompletionOptions): List[RequestAdjustment] =
    reasoning(model, options) ++ thinking(model, options)
}

/**
 * The shared pieces of mapping `reasoning` and `thinking` onto a provider's own levels (RFC 0008), so that every
 * client resolves a level and reports the result the same way. The levels each provider accepts stay in its client.
 */
private[llm4s] object EffortMapping {

  /** The level in `supported` nearest to `level`, the lower on a tie; `level` itself when supported. */
  def nearest(level: ReasoningEffort, supported: Set[ReasoningEffort]): ReasoningEffort = {
    val order = ReasoningEffort.values
    val at    = order.indexOf(level)
    order
      .filter(supported.contains)
      .minByOption(l => (math.abs(order.indexOf(l) - at), order.indexOf(l)))
      .getOrElse(level)
  }

  /** An `Adjusted` on `Reasoning` when `level` was sent as `sent`, a provider value with a different name. */
  def level(model: String, level: ReasoningEffort, sent: String): List[RequestAdjustment] =
    if (sent == level.name) Nil
    else
      List(RequestAdjustment.Adjusted(RequestOption.Reasoning, level.name, sent, s"$model has no ${level.name} level"))

  /**
   * An `Adjusted` on `Reasoning` for a `reasoning` that `thinking = Off` overrode, on a provider whose one control
   * serves both: the precedence rule on `CompletionOptions.withThinking`. `sent` is what went instead.
   */
  def overriddenByOff(options: CompletionOptions, sent: String): List[RequestAdjustment] =
    if (!options.thinking.contains(Thinking.Off)) Nil
    else
      options.reasoning.toList.map(level =>
        RequestAdjustment.Adjusted(RequestOption.Reasoning, level.name, sent, "thinking = Off takes precedence")
      )
}
