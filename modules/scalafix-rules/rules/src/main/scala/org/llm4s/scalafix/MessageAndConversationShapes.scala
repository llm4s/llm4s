package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * The renames and removed overloads of `AssistantMessage`, `Conversation` and `ProviderExchangeLogging` (#2809):
 *
 *   - `AssistantMessage`'s `contentOpt` is `text`: a read, `message.contentOpt`, and the named argument,
 *     `AssistantMessage(contentOpt = Some(t))`, are renamed.
 *   - `AssistantMessage`'s text has no default: `AssistantMessage()` becomes `AssistantMessage(None)` and
 *     `AssistantMessage(toolCalls = calls)` `AssistantMessage(text = None, toolCalls = calls)`.
 *   - `Conversation.empty()` is the value `Conversation.empty`.
 *   - `Conversation.create` takes one `Seq`: `create(a)` and `create(a, b)` become `create(Seq(a))` and
 *     `create(Seq(a, b))`, and `create(messages*)` `create(messages)`. The overload that took a `List` is the one that
 *     stays, so a call to it is left alone.
 *   - `ProviderExchangeLogging.enabled(sink)` is the case itself, `ProviderExchangeLogging.Enabled(sink)`.
 */
class MessageAndConversationShapes extends SemanticRule("MessageAndConversationShapes") {

  override def description: String =
    "Renames AssistantMessage.contentOpt to text and rewrites the removed Conversation.create and empty() forms (llm4s 0.6.0)"

  import MessageAndConversationShapes._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case n: Term.Name =>
        Migration.symbolOf(n).map(_.value) match {
          case Some(s) if ContentOpt(s) => Patch.replaceTree(n, "text")
          case Some(Enabled)            => Patch.replaceTree(n, "Enabled")
          case _                        => Patch.empty
        }
      case t: Term.Apply if !t.fun.is[Term.Apply] => withoutText(t) + empty(t) + create(t)
    }.asPatch

  /** `AssistantMessage()` and `AssistantMessage(toolCalls = ...)`, which relied on the text's default. */
  private def withoutText(t: Term.Apply)(implicit doc: SemanticDocument): Patch =
    if (!Migration.callee(t).exists(Migration.constructs(_, Message))) Patch.empty
    else
      t.argClause.values match {
        case Nil => Patch.replaceTree(t, s"${t.fun.pos.text}(None)")
        case args @ (first :: _) if args.forall {
              case Term.Assign(Term.Name("toolCalls" | "thinking"), _) => true
              case _                                                   => false
            } =>
          Patch.addLeft(first, "text = None, ")
        case _ => Patch.empty
      }

  /** `Conversation.empty()` as the value. */
  private def empty(t: Term.Apply)(implicit doc: SemanticDocument): Patch =
    if (Migration.symbolOf(t.fun).exists(_.value == Empty) && t.argClause.values.isEmpty)
      Patch.replaceTree(t, t.fun.pos.text)
    else Patch.empty

  /** The single-message and varargs `Conversation.create`, as a call of the `Seq` one. */
  private def create(t: Term.Apply)(implicit doc: SemanticDocument): Patch =
    (Migration.symbolOf(t.fun).map(_.value), t.argClause.values) match {
      case (Some(CreateVarargs), List(r: Term.Repeated)) => Patch.replaceTree(r, r.expr.pos.text)
      case (Some(CreateOne | CreateVarargs), args) if args.nonEmpty =>
        Patch.addLeft(args.head, "Seq(") + Patch.addRight(args.last, ")")
      case _ => Patch.empty
    }
}

object MessageAndConversationShapes {
  private val Message = "org/llm4s/llmconnect/model/AssistantMessage"

  /** `contentOpt` as the field, and as the parameter of `apply` and of the constructor. */
  private val ContentOpt = Set(
    Message + "#contentOpt.",
    Message + ".apply().(contentOpt)",
    Message + "#`<init>`().(contentOpt)"
  )

  private val Conversation  = "org/llm4s/llmconnect/model/Conversation."
  private val Empty         = Conversation + "empty()."
  private val CreateOne     = Conversation + "create()."
  private val CreateVarargs = Conversation + "create(+1)."

  private val Enabled = "org/llm4s/llmconnect/ProviderExchangeLogging.enabled()."
}
