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
 * [[org.llm4s.capability.require]] and [[Capable.assume]].
 *
 * @tparam C the capability set
 * @param capabilities the markers' names, each once, in one fixed order: `Vision`, `Tools`, `ParallelTools`,
 *   `StructuredOutput`, `Reasoning`, `PromptCaching`, `SystemMessages` (the compiler may reorder an intersection,
 *   so the order it is written in is not kept)
 */
@Experimental
final class CapabilityCheck[C] private (val capabilities: List[String]) {

  /** The capabilities that `metadata` does not mark as supported, in the order of [[capabilities]]. */
  def missing(metadata: ModelMetadata): List[String] =
    capabilities.filterNot(name => metadata.supports(CapabilityCheck.SupportsKeys(name)))

  override def toString: String = capabilities.mkString("CapabilityCheck(", " & ", ")")
}

object CapabilityCheck {

  /** The check of the capability set `C`, built by the compiler. */
  inline given derived[C]: CapabilityCheck[C] = ${ CapabilityMacros.derive[C] }

  /** Each marker's name, and the key of `ModelMetadata.supports` that says a model has it. */
  private[capability] val SupportsKeys: Map[String, String] = Map(
    "Vision"           -> "vision",
    "Tools"            -> "function_calling",
    "ParallelTools"    -> "parallel_function_calling",
    "StructuredOutput" -> "response_schema",
    "Reasoning"        -> "reasoning",
    "PromptCaching"    -> "prompt_caching",
    "SystemMessages"   -> "system_messages"
  )

  /** The order in which a set's capabilities are listed. */
  private[capability] val Order: List[String] =
    List("Vision", "Tools", "ParallelTools", "StructuredOutput", "Reasoning", "PromptCaching", "SystemMessages")

  /** Called only by the code [[derived]] expands to, with names it has checked against [[SupportsKeys]]. */
  private[capability] def unsafeOf[C](capabilities: List[String]): CapabilityCheck[C] =
    new CapabilityCheck[C](capabilities)
}
