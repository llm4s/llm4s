package org.llm4s.repl

import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.TokenUsage

import scala.concurrent.duration.FiniteDuration

/**
 * What one model call cost: the model that answered, the tokens the provider reported, how long the call took, and
 * the estimated cost in US dollars when llm4s knows the model's price. Printed as one line:
 *
 * {{{
 * gpt-4o-mini: 12 prompt + 8 completion = 20 tokens, 312 ms, $0.000007
 * }}}
 *
 * @param model         the model that answered, as the provider named it
 * @param usage         the token counts, when the provider reports them
 * @param latency       the time from the request to the last byte of the answer
 * @param estimatedCost the estimated cost in US dollars, when the model's price is known
 */
@Experimental
final class CallStats private[repl] (
  val model: String,
  val usage: Option[TokenUsage],
  val latency: FiniteDuration,
  val estimatedCost: Option[Double]
) {

  override def toString: String = {
    val tokens = usage.map(Render.usage).getOrElse("tokens not reported")
    val cost   = estimatedCost.map(Render.cost).getOrElse("cost unknown")
    s"$model: $tokens, ${Render.latency(latency)}, $cost"
  }
}
