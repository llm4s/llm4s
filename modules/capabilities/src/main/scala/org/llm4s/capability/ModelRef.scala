package org.llm4s.capability

import org.llm4s.annotation.Experimental

/**
 * A model named in the code with `model"provider/model"`, typed with the capabilities the model data bundled with
 * llm4s-core marks it as having: `model"openai/gpt-4o"` is a `ModelRef[Vision & Tools & StructuredOutput & ...]`.
 *
 * The type is the compile-time view, from the bundled snapshot of the data. The check that counts is the run-time
 * one: `client.requireCapabilities(ref)` checks the model again against the registry in scope, which may differ
 * (overrides, a newer snapshot, a registry of the application's own).
 *
 * @tparam C the capabilities the bundled data marks as supported (`Any` when it marks none)
 * @param provider the provider, the part of the literal before the first `/`
 * @param model the model, the part after it
 * @param capabilities the capabilities in `C`, in the order of [[Capability.values]]
 */
@Experimental
final class ModelRef[+C] private (val provider: String, val model: String, val capabilities: List[Capability]) {

  /** `provider/model`, as written in the literal. */
  def id: String = s"$provider/$model"

  override def toString: String = s"ModelRef($id, ${capabilities.mkString(" & ")})"
}

object ModelRef {

  /** Called only by the code `model"..."` expands to, with names it has taken from [[Capability.values]]. */
  private[capability] def unsafeOf[C](provider: String, model: String, names: List[String]): ModelRef[C] =
    new ModelRef[C](provider, model, names.map(Capability.valueOf))
}

extension (inline context: StringContext) {

  /**
   * A model of the bundled model data, checked at compile time: `model"openai/gpt-4o"`. An id the data does not
   * know is a compile error that suggests the closest one it does. The result is typed with the model's
   * capabilities (see [[ModelRef]]).
   */
  transparent inline def model(inline args: Any*): ModelRef[Any] = ${ ModelRefMacros.literal('context, 'args) }
}
