package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `.copy(field = value)` on a growth-prone type becomes `.withField(value)`.
 *
 * 0.5.0 made `.copy` private on the growth-prone types: `CompletionOptions().copy(maxTokens = Some(500),
 * temperature = 0.2)` becomes `CompletionOptions().withMaxTokens(500).withTemperature(0.2)`. A `Some(x)` argument
 * loses its `Some`, since each setter of an `Option` field takes the value. A `copy` with a positional argument,
 * or a field that has no setter any more, is reported instead of rewritten.
 */
class CopyToWithSetters extends SemanticRule("CopyToWithSetters") {

  override def description: String =
    "Rewrites .copy(field = value) on the llm4s growth-prone types to .withField(value) setters"

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect { case t: Term.Apply =>
      t.fun match {
        case Term.Select(_, copy @ Term.Name("copy")) =>
          Migration
            .callee(t)
            .filter(_.endsWith("#copy()."))
            .map(Migration.owner)
            .filter(CopyToWithSetters.types.contains)
            .fold(Patch.empty)(owner => rewrite(t, copy, owner))
        case _ => Patch.empty
      }
    }.asPatch

  private def rewrite(t: Term.Apply, copy: Term.Name, owner: String): Patch = {
    val args     = t.argClause.values
    val named    = args.collect { case Term.Assign(Term.Name(field), rhs) => field -> rhs }
    val typeName = owner.split('/').last.split('.').last
    if (args.isEmpty) Patch.empty
    else if (named.size != args.size)
      Patch.lint(
        Diagnostic(
          "positionalCopy",
          s"$typeName.copy is private since 0.5.0; name each argument, or use the with* setters: " +
            Migration.guide05("7-errors-cancellation-and-types"),
          t.pos
        )
      )
    else
      named.collectFirst { case (field, _) if CopyToWithSetters.removed.contains(owner -> field) => field } match {
        case Some(field) =>
          Patch.lint(
            Diagnostic(
              "fieldWithoutSetter",
              s"$typeName.$field has no setter since 0.5.0: ${CopyToWithSetters.removed(owner -> field)}",
              t.pos
            )
          )
        case None =>
          val setters = named.map { case (field, rhs) => s"${CopyToWithSetters.setter(owner, field)}(${value(rhs)})" }
          Patch.replaceTree(copy, setters.mkString(".")) + Patch.removeTokens(t.argClause.tokens)
      }
  }

  /** `Some(x)` is `x`: every setter of an `Option` field takes the value. */
  private def value(rhs: Term): String = rhs match {
    case a: Term.Apply =>
      (a.fun, a.argClause.values) match {
        case (Term.Name("Some"), List(single)) => single.pos.text
        case _                                 => a.pos.text
      }
    case other => other.pos.text
  }
}

object CopyToWithSetters {

  /** The growth-prone types of 0.5.0, as they were named in 0.4.1. */
  val types: Set[String] = Set(
    "org/llm4s/llmconnect/model/CompletionOptions",
    "org/llm4s/llmconnect/model/Completion",
    "org/llm4s/llmconnect/model/StreamedChunk",
    "org/llm4s/llmconnect/model/TokenUsage",
    "org/llm4s/llmconnect/model/EmbeddingRequest",
    "org/llm4s/llmconnect/model/AssistantMessage",
    "org/llm4s/model/ModelCapabilities",
    "org/llm4s/model/ModelMetadata",
    "org/llm4s/config/ProvidersConfigModel.NamedProviderConfig",
    "org/llm4s/reliability/ReliabilityConfig",
    "org/llm4s/reliability/CircuitBreakerConfig",
    "org/llm4s/context/ContextConfig",
    "org/llm4s/llmconnect/config/OpenAIConfig",
    "org/llm4s/llmconnect/config/AnthropicConfig",
    "org/llm4s/llmconnect/config/OllamaConfig"
  )

  /** A setter whose name is not `with` and the field's name. */
  private val renamed: Map[(String, String), String] = Map(
    ("org/llm4s/llmconnect/model/AssistantMessage", "contentOpt") -> "withContent"
  )

  /** Fields with no setter, and what to do instead. */
  val removed: Map[(String, String), String] = Map(
    ("org/llm4s/llmconnect/model/Completion", "thinking") ->
      "the thinking is the message's; use withMessage(completion.message.withThinking(text))",
    ("org/llm4s/config/ProvidersConfigModel.NamedProviderConfig", "organization") ->
      "it is a provider extra; use withExtras(Map(\"organization\" -> value))",
    ("org/llm4s/config/ProvidersConfigModel.NamedProviderConfig", "endpoint") ->
      "it is a provider extra; use withExtras(Map(\"endpoint\" -> value))",
    ("org/llm4s/config/ProvidersConfigModel.NamedProviderConfig", "apiVersion") ->
      "it is a provider extra; use withExtras(Map(\"apiVersion\" -> value))",
    ("org/llm4s/context/ContextConfig", "enableRollingSummary") -> "the field is removed"
  )

  def setter(owner: String, field: String): String =
    renamed.getOrElse((owner, field), "with" + field.head.toUpper + field.tail)
}
