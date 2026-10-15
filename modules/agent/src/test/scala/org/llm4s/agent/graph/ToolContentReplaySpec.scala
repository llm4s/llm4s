package org.llm4s.agent.graph

import org.llm4s.agent.graph.tool.ToolContent
import org.llm4s.llmconnect.model.ContentPart
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/**
 * A `ToolContent` recorded by a `ReplayGuard` as an effect's outcome (#1913 with C4): it round-trips through the
 * stored record, parts included, and its inline media counts towards `maxOutcomeBytes` at its encoded size.
 */
class ToolContentReplaySpec extends AnyFlatSpec with Matchers with EitherValues {

  /** A journal that keeps each record as the store does: as JSON, read back on lookup. */
  private class StoredJournal extends EffectJournal {
    val json = mutable.Map.empty[String, ujson.Value]
    def lookup(key: EffectKey): Result[Option[EffectRecord]] =
      json.get(key.value) match {
        case Some(stored) => EffectRecord.fromJson(stored).map(Some(_))
        case None         => Right(None)
      }
    def write(record: EffectRecord): Result[Unit] = {
      json.update(record.key.value, EffectRecord.toJson(record)); Right(())
    }
  }

  private def context(journal: EffectJournal) =
    new RunContext(
      RunConfig(),
      RunPosition(ThreadId("t"), RunId("r"), "r/1", TaskId("1.0"), NodeId("n"), 1),
      NodeEventSink.none,
      journal = journal
    )

  private def image(bytes: Int) =
    ContentPart
      .image(MediaSource.Inline(IArray.fill[Byte](bytes)(7)), MediaType.Png)
      .fold(e => fail(e.message), identity)

  "A ToolContent outcome" should "be stored and replayed, its parts unchanged" in {
    val journal = new StoredJournal
    val c       = context(journal)
    val key     = c.effectKey("render", "chart")
    val content = ToolContent.Parts(Vector(ContentPart.Text("the chart"), image(64)))
    var runs    = 0
    def guarded = ReplayGuard.unverifiable[ToolContent](c, key, idempotent = false) { runs += 1; Right(content) }
    guarded.value shouldBe content
    guarded.value shouldBe content
    runs shouldBe 1
    ReplayGuard
      .unverifiable[ToolContent](c, c.effectKey("render", "json"), idempotent = false)(
        Right(ToolContent.Json(ujson.Obj("n" -> 1)))
      )
      .value shouldBe
      ToolContent.Json(ujson.Obj("n" -> 1))
  }

  it should "count its inline media at the encoded size towards maxOutcomeBytes" in {
    val journal = new StoredJournal
    val c       = context(journal)
    val key     = c.effectKey("render", "big")
    val content = ToolContent.Parts(Vector(image(3000)))
    // 3000 bytes are 4000 base64-encoded: a cap between the two omits the outcome
    ReplayGuard
      .unverifiable[ToolContent](c, key, idempotent = false, maxOutcomeBytes = 3500)(Right(content))
      .value shouldBe content
    ReplayGuard
      .unverifiable[ToolContent](c, key, idempotent = false, maxOutcomeBytes = 3500)(Right(content))
      .left
      .value match {
      case r: GraphError.EffectReplayRefused => r.reason should include("over the cap")
      case other                             => fail(s"not a refusal: $other")
    }
  }
}
