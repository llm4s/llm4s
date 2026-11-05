package org.llm4s.capability

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, ContentPart, Conversation, UserMessage }
import org.llm4s.model.{ ModelMetadata, ModelRegistryService }
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result

/**
 * A client whose model has the capabilities `C`, a set such as `Vision & Tools`.
 *
 * One is made at the application's edge, by [[org.llm4s.capability.requireCapabilities]], which checks the model's metadata, or
 * by [[Capable.assume]], which does not. From then on the compiler tracks the set: an operation that needs vision
 * takes a `Capable[Vision]`, and since `Capable` is covariant a `Capable[Vision & Tools]` is one, while a
 * `Capable[Tools]` is a compile error.
 *
 * @tparam C the capability set
 * @param client the client, unchanged
 * @param capabilities the capabilities this value was checked (or assumed) for, which may be more
 *   than its static type shows
 * @param metadata the model metadata the capabilities were checked against; `None` when they were assumed
 */
@Experimental
final class Capable[+C] private (
  val client: LLMClient,
  val capabilities: List[Capability],
  val metadata: Option[ModelMetadata]
) {

  override def toString: String = {
    val source = metadata.fold("assumed")(m => s"checked against ${m.provider}/${m.modelId}")
    s"Capable(${capabilities.mkString(" & ")}, $source)"
  }
}

object Capable {

  /**
   * A `Capable[C]` with no check, for a model the registry does not know, such as a local or fine-tuned one.
   * Written in the code, so the assumption is visible in review; the provider still refuses a request the model
   * cannot serve.
   */
  def assume[C](client: LLMClient)(using check: CapabilityCheck[C]): Capable[C] =
    new Capable[C](client, check.capabilities, None)

  private[capability] def checked[C](
    client: LLMClient,
    check: CapabilityCheck[C],
    metadata: ModelMetadata
  ): Capable[C] =
    new Capable[C](client, check.capabilities, Some(metadata))

  extension (capable: Capable[Vision]) {

    /**
     * Asks the model about one image: a conversation of one user message holding the image and then the question,
     * sent with `client.complete`.
     */
    def describe(
      image: ContentPart.Image,
      question: String,
      options: CompletionOptions = CompletionOptions()
    ): Result[Completion] =
      capable.client.complete(Conversation(Seq(UserMessage(Vector(image, ContentPart.Text(question))))), options)
  }

  extension (capable: Capable[Tools]) {

    /** `client.complete` with `tools` offered to the model, added to any tools `options` already has. */
    def completeWithTools(
      conversation: Conversation,
      tools: Seq[ToolFunction[?, ?]],
      options: CompletionOptions = CompletionOptions()
    ): Result[Completion] =
      capable.client.complete(conversation, options.withTools(options.tools ++ tools))
  }
}

extension (client: LLMClient) {

  /**
   * Checks once, here, that `provider`'s `model` has the capabilities `C`, by the registry's metadata, and returns
   * the client typed with them: `client.requireCapabilities[Vision & Tools]("openai", "gpt-4o")`.
   *
   * Fails with the registry's error when it does not know the model (see [[Capable.assume]] for that case), and
   * with a [[org.llm4s.error.ValidationError]] on the field `capabilities` that names every capability the model
   * lacks.
   */
  def requireCapabilities[C](provider: String, model: String)(using
    registry: ModelRegistryService,
    check: CapabilityCheck[C]
  ): Result[Capable[C]] =
    registry.lookup(provider, model).flatMap { metadata =>
      check.missing(metadata) match {
        case Nil => Right(Capable.checked(client, check, metadata))
        case missing =>
          Left(ValidationError("capabilities", missing.map(name => s"$provider/$model does not support $name")))
      }
    }
}
