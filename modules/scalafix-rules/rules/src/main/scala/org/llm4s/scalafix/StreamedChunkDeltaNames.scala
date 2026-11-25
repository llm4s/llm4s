package org.llm4s.scalafix

import scala.meta._
import scalafix.v1._

/**
 * `StreamedChunk.content` is `contentDelta` and `StreamedChunk.toolCall` is `toolCallDelta` (#2916): both are what
 * one chunk adds, as `thinkingDelta` already said of itself.
 *
 *   - `chunk.content` becomes `chunk.contentDelta`, and `chunk.toolCall` `chunk.toolCallDelta`.
 *   - `chunk.withContent(...)` becomes `chunk.withContentDelta(...)`, and `withToolCall` `withToolCallDelta`.
 *   - `StreamedChunk(id = ..., content = ..., toolCall = ...)` names its arguments `contentDelta` and
 *     `toolCallDelta`.
 */
class StreamedChunkDeltaNames extends SemanticRule("StreamedChunkDeltaNames") {

  override def description: String =
    "Renames StreamedChunk.content to contentDelta and StreamedChunk.toolCall to toolCallDelta"

  import StreamedChunkDeltaNames._

  override def fix(implicit doc: SemanticDocument): Patch =
    doc.tree.collect {
      case Term.Select(_, member: Term.Name) if renamed.contains(member.value) && isMember(member) =>
        Patch.replaceTree(member, renamed(member.value))
      case t: Term.Apply if Migration.callee(t).exists(Migration.constructs(_, Type)) =>
        t.argClause.values.collect {
          case Term.Assign(arg: Term.Name, _) if fields.contains(arg.value) =>
            Patch.replaceTree(arg, fields(arg.value))
        }.asPatch
    }.asPatch

  private def isMember(name: Term.Name)(implicit doc: SemanticDocument): Boolean =
    Migration.symbolOf(name).exists(_.value.startsWith(Type + "#"))
}

object StreamedChunkDeltaNames {
  private val Type   = "org/llm4s/llmconnect/model/StreamedChunk"
  private val fields = Map("content" -> "contentDelta", "toolCall" -> "toolCallDelta")
  private val renamed =
    fields ++ Map("withContent" -> "withContentDelta", "withToolCall" -> "withToolCallDelta")
}
