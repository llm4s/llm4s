package org.llm4s.capability

import org.llm4s.annotation.Experimental
import org.llm4s.model.ModelMetadata

/**
 * The capabilities of a capability set `C`, such as `Vision & Tools`, and how to check them against a model's
 * metadata.
 *
 * There is one given for every set: the compiler takes `C` apart at its `&`s, and refuses at compile time any part
 * that is not one of the markers [[Vision]], [[Tools]], [[ParallelTools]], [[StructuredOutput]], [[Reasoning]],
 * [[PromptCaching]] and [[SystemMessages]]. A set is never written as a value; it is summoned by
 * [[org.llm4s.capability.requireCapabilities]] and [[Capable.assume]].
 *
 * @tparam C the capability set
 * @param capabilities the set's capabilities, each once, in the order of [[Capability.values]] (the compiler may
 *   reorder an intersection, so the order it is written in is not kept)
 */
@Experimental
final class CapabilityCheck[C] private (val capabilities: List[Capability]) {

  /** The capabilities that `metadata` does not mark as supported, in the order of [[capabilities]]. */
  def missing(metadata: ModelMetadata): List[Capability] =
    capabilities.filterNot(capability => metadata.supports(capability.supportsKey))

  override def toString: String = capabilities.mkString("CapabilityCheck(", " & ", ")")
}

object CapabilityCheck {

  /** The check of the capability set `C`, built by the compiler. */
  inline given derived[C]: CapabilityCheck[C] = ${ CapabilityMacros.derive[C] }

  /** Called only by the code [[derived]] expands to, with names it has checked against [[Capability.values]]. */
  private[capability] def unsafeOf[C](names: List[String]): CapabilityCheck[C] =
    new CapabilityCheck[C](names.map(Capability.valueOf))
}
