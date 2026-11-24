package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `.copy(field = value)` on a growth-prone type becomes `.withField(value)`: what [[CopyToWithSetters]] (0.5.0) and
 * [[CopyToWithSetters_1_0]] (the types made growth-safe since 0.5.1) share. A `Some(x)` argument loses its `Some`,
 * since each setter of an `Option` field takes the value. A `copy` with a positional argument, or a field that has no
 * setter any more, is reported instead of rewritten.
 */
abstract class CopyToSetters(name: String) extends SemanticRule(name) {

  /** The growth-prone types, as the old release named them. */
  protected def types: Set[String]

  /** The release that made `.copy` private, for the lint messages. */
  protected def release: String

  /** The guide section on private `copy`, for the positional-copy lint. */
  protected def privateCopyGuide(owner: String): String

  /** Fields with no setter, and what to do instead. */
  protected def removed: Map[(String, String), String] = Map.empty

  /** The setter of a field, by default `with` and the field's name. */
  protected def setter(owner: String, field: String): String = "with" + field.head.toUpper + field.tail

  /** Fields that were a `String` (or `Option[String]`, when `true`) and are a `Secret` now: the value is wrapped. */
  protected def secrets: Map[(String, String), Boolean] = Map.empty

  /**
   * Fields that were a `String` and are a newtype of `org.llm4s.types.ProviderModelTypes` now, with the newtype's
   * name: the value is wrapped, `withModel("gpt-4o")` being `withModel(ModelName("gpt-4o"))` (#2805).
   */
  protected def newtypes: Map[(String, String), String] = Map.empty

  /** Whether `new X(...)` is reported: its constructor is private as well. */
  protected def reportNew: Boolean = false

  override def fix(implicit doc: SemanticDocument): Patch = {
    val rewrites = doc.tree.collect {
      case t: Term.Apply =>
        t.fun match {
          case Term.Select(_, copy @ Term.Name("copy")) =>
            Migration
              .callee(t)
              .filter(_.endsWith("#copy()."))
              .map(Migration.owner)
              .filter(types.contains)
              .fold(Patch.empty)(owner => rewrite(t, copy, owner))
          case _ => Patch.empty
        }
      case n: Term.New if reportNew =>
        Migration
          .symbolOf(n.init.tpe)
          .map(Migration.owner)
          .filter(types.contains)
          .fold(Patch.empty) { owner =>
            val typeName = CopyToSetters.simpleName(owner)
            Patch.lint(
              Diagnostic(
                "privateConstructor",
                s"new $typeName is private since $release; build it with $typeName(...) and change it with the " +
                  s"with* setters: ${privateCopyGuide(owner)}",
                n.pos
              )
            )
          }
    }.asPatch
    val imports = wrappedNewtypes.foldLeft(Patch.empty)((p, newtype) =>
      p + Migration.importOnce("org.llm4s.types.ProviderModelTypes", newtype)
    )
    rewrites + (if (wrapsSecret) Migration.importOnce("org.llm4s.security", "Secret") else Patch.empty) + imports
  }

  /** The newtypes a rewritten `copy` wraps a value in, so the file needs each imported. */
  private def wrappedNewtypes(implicit doc: SemanticDocument): List[String] =
    if (newtypes.isEmpty) Nil
    else
      doc.tree
        .collect {
          case t: Term.Apply if isCopy(t.fun) && t.argClause.values.forall(_.is[Term.Assign]) =>
            Migration.callee(t).filter(_.endsWith("#copy().")).map(Migration.owner).toList.flatMap { owner =>
              val fields = t.argClause.values.collect { case Term.Assign(Term.Name(field), _) => field }
              if (fields.exists(f => removed.contains(owner -> f))) Nil
              else fields.flatMap(f => newtypes.get(owner -> f))
            }
        }
        .flatten
        .distinct

  /** Whether a rewritten `copy` sets a field that is a `Secret` now, so the file needs `Secret` imported. */
  private def wrapsSecret(implicit doc: SemanticDocument): Boolean =
    secrets.nonEmpty && doc.tree
      .collect {
        case t: Term.Apply if isCopy(t.fun) && t.argClause.values.forall(_.is[Term.Assign]) =>
          Migration.callee(t).filter(_.endsWith("#copy().")).map(Migration.owner).exists { owner =>
            val fields = t.argClause.values.collect { case Term.Assign(Term.Name(field), _) => field }
            !fields.exists(f => removed.contains(owner -> f)) && fields.exists(f => secrets.contains(owner -> f))
          }
      }
      .contains(true)

  private def isCopy(fun: Term): Boolean = fun match {
    case Term.Select(_, Term.Name("copy")) => true
    case _                                 => false
  }

  private def rewrite(t: Term.Apply, copy: Term.Name, owner: String): Patch = {
    val args     = t.argClause.values
    val named    = args.collect { case Term.Assign(Term.Name(field), rhs) => field -> rhs }
    val typeName = CopyToSetters.simpleName(owner)
    if (args.isEmpty) Patch.empty
    else if (named.size != args.size)
      Patch.lint(
        Diagnostic(
          "positionalCopy",
          s"$typeName.copy is private since $release; name each argument, or use the with* setters: " +
            privateCopyGuide(owner),
          t.pos
        )
      )
    else
      named.collectFirst { case (field, _) if removed.contains(owner -> field) => field } match {
        case Some(field) =>
          Patch.lint(
            Diagnostic(
              "fieldWithoutSetter",
              s"$typeName.$field has no setter since $release: ${removed(owner -> field)}",
              t.pos
            )
          )
        case None =>
          val setters = named.map { case (field, rhs) =>
            s"${setter(owner, field)}(${argument(owner, field, rhs)})"
          }
          Patch.replaceTree(copy, setters.mkString(".")) + Patch.removeTokens(t.argClause.tokens)
      }
  }

  private def argument(owner: String, field: String, rhs: Term): String =
    secrets.get(owner -> field) match {
      case None =>
        newtypes.get(owner -> field).fold(CopyToSetters.value(rhs))(n => s"$n(${CopyToSetters.value(rhs)})")
      case Some(optional) =>
        if (!optional) s"Secret(${rhs.pos.text})"
        else
          rhs match {
            case Term.Name("None")                        => "None"
            case a: Term.Apply if CopyToSetters.isSome(a) => s"Secret(${CopyToSetters.value(rhs)})"
            case other                                    => s"${CopyToSetters.operand(other)}.map(Secret(_))"
          }
    }
}

private[scalafix] object CopyToSetters {

  def simpleName(owner: String): String = owner.split('/').last.split('.').last

  def isSome(a: Term.Apply): Boolean = a.fun.is[Term.Name] && a.fun.syntax == "Some" && a.argClause.values.size == 1

  /** `Some(x)` is `x`: every setter of an `Option` field takes the value. */
  def value(rhs: Term): String = rhs match {
    case a: Term.Apply if isSome(a) => a.argClause.values.head.pos.text
    case other                      => other.pos.text
  }

  /** An expression as the receiver of a method call: parenthesised unless it is a name, a selection or a call. */
  def operand(t: Term): String = t match {
    case _: Term.Name | _: Term.Select | _: Term.Apply => t.pos.text
    case _                                             => s"(${t.pos.text})"
  }
}

/**
 * 0.5.0 made `.copy` private on the growth-prone types: `CompletionOptions().copy(maxTokens = Some(500),
 * temperature = 0.2)` becomes `CompletionOptions().withMaxTokens(500).withTemperature(0.2)`.
 */
class CopyToWithSetters extends CopyToSetters("CopyToWithSetters") {

  override def description: String =
    "Rewrites .copy(field = value) on the llm4s growth-prone types to .withField(value) setters"

  override protected def types: Set[String]                      = CopyToWithSetters.types
  override protected def release: String                         = "0.5.0"
  override protected def removed: Map[(String, String), String]  = CopyToWithSetters.removed
  override protected def setter(owner: String, field: String)    = CopyToWithSetters.setter(owner, field)
  override protected def newtypes: Map[(String, String), String] = CopyToWithSetters.newtypes
  override protected def privateCopyGuide(owner: String): String =
    Migration.guide05("7-errors-cancellation-and-types")
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

  /** The provider configs' `model`, a `String` in 0.4.1 and a `ModelName` now (#2805). */
  val newtypes: Map[(String, String), String] =
    Seq("OpenAIConfig", "AnthropicConfig", "OllamaConfig")
      .map(config => ("org/llm4s/llmconnect/config/" + config, "model") -> "ModelName")
      .toMap

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
