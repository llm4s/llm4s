package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * A tool call's id is a `ToolCallId` and its idempotency key an `IdempotencyKey`, opaque types in core, not
 * `String`s (#2815). The rule rewrites code compiled against 0.6.0:
 *
 *   - an id set from a `String` is wrapped: `ToolCall("c1", ...)` to `ToolCall(ToolCallId("c1"), ...)`, and likewise
 *     `ToolMessage(text, id)`, `Message.tool(text, id)`, `ToolCallInfo(id, key)` (the key in `IdempotencyKey(...)`),
 *     `withId`, `withToolCallId`, `ToolCallInfo#withIdempotencyKey` and the agent's `ToolExecuted`, `ToolCallStarted`
 *     and `ToolCallResult` events, by position or by name. An id passed on from another call or message is left
 *     as it is: it is a `ToolCallId` already;
 *   - every other read of one of those ids (`call.id`, `message.toolCallId`, `info.idempotencyKey`, ...) gets
 *     `.asString`, which keeps its type, so a comparison with a literal, a `Map[String, ...]` key or a string
 *     method still compiles and means what it did; `ToolExecuted#idempotencyKey`, an `Option`, gets
 *     `.map(_.asString)`;
 *   - the agent's own `ToolCallId` and `IdempotencyKey` (`org.llm4s.agent.graph`) are core's now: their imports move
 *     to `org.llm4s.llmconnect.model.ToolCallId` and `org.llm4s.toolapi.IdempotencyKey`, `.value` on one becomes
 *     `.asString`, and `ToolCallId(call.id)`, which wrapped core's string id, becomes `call.id`.
 *
 * The imports of `ToolCallId` and `IdempotencyKey` are added where the rule writes one. A pattern on an id
 * (`case ToolCall("c1", _, _)`) is left for the compiler to report.
 */
class ToolCallIdTyped extends SemanticRule("ToolCallIdTyped") {

  override def description: String =
    "Wraps the String tool call ids and idempotency keys of llm4s 0.6.0 in ToolCallId and IdempotencyKey"

  import ToolCallIdTyped._

  override def fix(implicit doc: SemanticDocument): Patch = {
    val sets: List[(Term, String)] = doc.tree.collect { case t: Term.Apply => setArguments(t) }.flatten
    val agentWraps: List[(Term.Apply, Term)] = doc.tree.collect {
      case t: Term.Apply
          if isAgentToolCallIdApply(t) && t.argClause.values.size == 1 && isRead(t.argClause.values.head) =>
        t -> t.argClause.values.head
    }
    def setArg(tree: Tree): Boolean   = sets.exists(_._1 eq tree)
    def agentArg(tree: Tree): Boolean = agentWraps.exists(_._2 eq tree)
    val toWrap                        = sets.filterNot { case (arg, _) => isRead(arg) || isTyped(arg) }
    val setPatches                    = toWrap.map { case (arg, w) => wrap(arg, w) }
    val unwraps                       = agentWraps.map { case (apply, read) => Patch.replaceTree(apply, read.syntax) }
    val reads = doc.tree.collect {
      case s: Term.Select if isRead(s) && !setArg(s) && !agentArg(s) && !alreadyString(s) =>
        if (isOptionRead(s)) Patch.addRight(s, ".map(_.asString)") else Patch.addRight(s, ".asString")
    }
    val values = doc.tree.collect {
      case s: Term.Select
          if s.name.value == "value" && Migration.symbolOf(s.name).exists(sym => isAgentValue(sym.value)) =>
        Patch.replaceTree(s.name, "asString")
    }
    val moved = doc.tree.collect {
      case i: Importer if i.ref.syntax == AgentGraph =>
        i.importees.collect {
          case n: Importee.Name if Moves.contains(n.name.value)   => n -> Moves(n.name.value)
          case r: Importee.Rename if Moves.contains(r.name.value) => r -> Moves(r.name.value)
        }
    }.flatten
    val usesAgentType = (name: String) =>
      doc.tree.collect {
        case n: Name if Migration.symbolOf(n).exists(_.value.startsWith(AgentIds + name)) => n
      }.nonEmpty
    val written = toWrap.map(_._2).toSet
    val needed  = written ++ Moves.keySet.filter(usesAgentType)
    val imports = needed.toList.sorted.map(name => Migration.importOnce(Core(name), name))
    val removed = moved.map { case (importee, _) => Patch.removeImportee(importee) }
    (setPatches ++ unwraps ++ reads ++ values ++ removed ++ imports).asPatch
  }

  /** The arguments of a call that set an id or a key, each with the type that wraps it. */
  private def setArguments(t: Term.Apply)(implicit doc: SemanticDocument): List[(Term, String)] =
    Migration.callee(t).toList.flatMap { callee =>
      Setters.collectFirst { case (sym, shape) if matches(callee, sym) => shape }.toList.flatMap { shape =>
        val args = t.argClause.values
        args.zipWithIndex.collect {
          case (Term.Assign(Term.Name(n), rhs), _) if shape.byName.contains(n) => rhs -> shape.byName(n)
          case (arg, i)
              if !arg.is[Term.Assign] && args.take(i).forall(!_.is[Term.Assign]) && shape.byIndex.contains(i) =>
            arg -> shape.byIndex(i)
        }
      }
    }

  private def matches(callee: String, sym: String): Boolean =
    if (sym.endsWith("().")) callee.startsWith(sym.stripSuffix("().") + "(") else Migration.constructs(callee, sym)

  private def isRead(t: Tree)(implicit doc: SemanticDocument): Boolean = t match {
    case s: Term.Select => Migration.symbolOf(s.name).exists(sym => Reads.contains(sym.value))
    case _              => false
  }

  /** An argument typed already: `ToolCallId(...)` or `IdempotencyKey(...)`, by this rule or by hand. */
  private def isTyped(t: Term)(implicit doc: SemanticDocument): Boolean = t match {
    case a: Term.Apply =>
      a.fun match {
        case Term.Name(Id | Key) => true
        case f                   => Migration.symbolOf(f).exists(s => Typed.exists(s.value.startsWith))
      }
    case _ => false
  }

  private def isOptionRead(s: Term.Select)(implicit doc: SemanticDocument): Boolean =
    Migration.symbolOf(s.name).exists(_.value == Events + "ToolExecuted#idempotencyKey.")

  /** A read already unwrapped, by an earlier run of this rule. */
  private def alreadyString(s: Term.Select): Boolean = s.parent match {
    case Some(Term.Select(q, Term.Name("asString"))) => q eq s
    case _                                           => false
  }

  private def isAgentToolCallIdApply(t: Term.Apply)(implicit doc: SemanticDocument): Boolean =
    Migration.symbolOf(t.fun).exists(s => s.value.startsWith(AgentIds + "ToolCallId."))

  private def isAgentValue(symbol: String): Boolean =
    symbol.startsWith(AgentIds + "ToolCallId.value(") || symbol.startsWith(AgentIds + "IdempotencyKey.value(")

  private def wrap(arg: Term, wrapper: String): Patch =
    Patch.addLeft(arg, wrapper + "(") + Patch.addRight(arg, ")")
}

object ToolCallIdTyped {

  final private case class Shape(byIndex: Map[Int, String], byName: Map[String, String])

  private val Model      = "org/llm4s/llmconnect/model/"
  private val ToolApi    = "org/llm4s/toolapi/"
  private val Events     = "org/llm4s/agent/events/"
  private val AgentIds   = "org/llm4s/agent/graph/GraphIds$package."
  private val AgentGraph = "org.llm4s.agent.graph"

  private val Id  = "ToolCallId"
  private val Key = "IdempotencyKey"

  private val Core: Map[String, String] = Map(Id -> "org.llm4s.llmconnect.model", Key -> "org.llm4s.toolapi")

  /** The companions of the newtypes, in core and (in 0.6.0) in the agent. */
  private val Typed: List[String] =
    List(Model + "ToolCallId", ToolApi + "IdempotencyKey", AgentIds + "ToolCallId", AgentIds + "IdempotencyKey")

  /** The agent's newtypes that are core's now. */
  private val Moves: Map[String, String] = Map(Id -> Core(Id), Key -> Core(Key))

  /** The calls that set an id or a key: an owner (its `apply` or constructor) or a method (`...().`). */
  private val Setters: List[(String, Shape)] = List(
    Model + "ToolCall"           -> Shape(Map(0 -> Id), Map("id" -> Id)),
    Model + "ToolCall#withId()." -> Shape(Map(0 -> Id), Map("id" -> Id)),
    Model + "ToolMessage"        -> Shape(Map(1 -> Id), Map("toolCallId" -> Id)),
    Model + "Message.tool()."    -> Shape(Map(1 -> Id), Map("toolCallId" -> Id)),
    ToolApi + "ToolCallInfo"     -> Shape(Map(0 -> Id, 1 -> Key), Map("toolCallId" -> Id, "idempotencyKey" -> Key)),
    ToolApi + "ToolCallInfo#withToolCallId()."     -> Shape(Map(0 -> Id), Map("toolCallId" -> Id)),
    ToolApi + "ToolCallInfo#withIdempotencyKey()." -> Shape(Map(0 -> Key), Map("idempotencyKey" -> Key)),
    Events + "ToolExecuted"                        -> Shape(Map(1 -> Id), Map("toolCallId" -> Id)),
    Events + "ToolExecuted#withToolCallId()."      -> Shape(Map(0 -> Id), Map("value" -> Id)),
    Events + "ToolCallStarted"                     -> Shape(Map(0 -> Id), Map("toolCallId" -> Id)),
    Events + "ToolCallStarted#withToolCallId()."   -> Shape(Map(0 -> Id), Map("value" -> Id)),
    Events + "ToolCallResult"                      -> Shape(Map(0 -> Id), Map("toolCallId" -> Id)),
    Events + "ToolCallResult#withToolCallId()."    -> Shape(Map(0 -> Id), Map("value" -> Id))
  )

  /** The reads that returned a `String` in 0.6.0 and return a `ToolCallId` or `IdempotencyKey` now. */
  private val Reads: Set[String] = Set(
    Model + "ToolCall#id.",
    Model + "ToolMessage#toolCallId.",
    ToolApi + "ToolCallInfo#toolCallId.",
    ToolApi + "ToolCallInfo#idempotencyKey.",
    Events + "ToolExecuted#toolCallId.",
    Events + "ToolExecuted#idempotencyKey.",
    Events + "ToolCallStarted#toolCallId.",
    Events + "ToolCallResult#toolCallId."
  )
}
