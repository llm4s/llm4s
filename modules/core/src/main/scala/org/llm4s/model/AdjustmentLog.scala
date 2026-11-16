package org.llm4s.model

import org.llm4s.llmconnect.model.{ CompletionOptions, RequestAdjustment, RequestOption }
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
}
