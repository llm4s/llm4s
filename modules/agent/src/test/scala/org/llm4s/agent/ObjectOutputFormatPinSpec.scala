package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.AgentOutputSpec.{ invoiceSchema, Invoice }
import org.llm4s.agent.graph.{
  Checkpoint,
  ClaimRequest,
  Commit,
  EventDraft,
  EventRecord,
  GraphRuntime,
  InMemoryCheckpointer,
  PendingWrite,
  RunId,
  ThreadId
}
import org.llm4s.llmconnect.model.{ AssistantMessage, ResponseFormat, UserMessage }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * What 1.0.0 stores and sends for an agent whose response format has an object at its root, pinned before a root
 * `anyOf` was accepted (#2953): this build writes the same JSON and continues a thread 1.0.0 wrote.
 *
 * `fixtures/output-format-1.0.0-object-root.json` was written by a18040237 (whose `modules/agent` and `modules/core`
 * are those of the `v1.0.0` tag) with the program below, run as
 * `sbt "agent/Test/runMain org.llm4s.agent.WriteObjectOutputFixture <target>"`. It holds what that build's
 * `InMemoryCheckpointer` stored for thread `object-output-1.0.0` after one turn whose first answer was invalid and
 * whose second came in a code fence - the latest checkpoint, its pending writes and its events - with the graph
 * version, the answer, and the system prompt and response format each of the two model calls was sent.
 *
 * {{{
 * object WriteObjectOutputFixture:
 *   def main(args: Array[String]): Unit =
 *     val fixture = ObjectOutputFormatPinSpec.written(new InMemoryCheckpointer())
 *     Files.writeString(Path.of(args(0)), ujson.write(fixture, indent = 2) + "\n"): Unit
 * }}}
 */
class ObjectOutputFormatPinSpec extends AnyFlatSpec with Matchers with EitherValues {
  import ObjectOutputFormatPinSpec._

  private val fixture: ujson.Value =
    Using.resource(Source.fromResource("fixtures/output-format-1.0.0-object-root.json"))(s => ujson.read(s.mkString))

  private val thread = ThreadId(fixture("threadId").str)

  /** A store holding what 1.0.0 stored for the thread. */
  private def seeded(): InMemoryCheckpointer =
    val store      = new InMemoryCheckpointer()
    val checkpoint = Checkpoint.fromJson(fixture("checkpoint")).value
    val writes     = fixture("pendingWrites").arr.toVector.map(upickle.default.read[PendingWrite](_))
    val events = fixture("events").arr.toVector.map(upickle.default.read[EventRecord](_)).map { r =>
      EventDraft(r.runId, r.checkpointId, r.taskId, r.nodeId, r.timestamp, r.event)
    }
    val claim = store.claim(thread, ClaimRequest(RunId("seed"), None, 1.minute)).value
    store.commit(thread, Commit(claim.token, Some(checkpoint.withParent(None)), writes, events)).value
    store.release(thread, claim.token).value
    store

  "An object-root response format" should "store and send, byte for byte, what 1.0.0 did" in {
    val now = written(new InMemoryCheckpointer())

    ujson.write(normalised(now), indent = 2) shouldBe ujson.write(normalised(fixture), indent = 2)
    // what is compared is not trivial: the stored answer, the events and both calls' formats are in it
    fixture("answer").str shouldBe """{"customer":"Acme","total":12.5}"""
    fixture("events").arr.size should be > 5
    fixture("calls").arr.map(_("responseFormat")("schema")).toSet shouldBe
      Set(invoiceSchema.toJsonSchema(strict = true))
  }

  "A thread 1.0.0 wrote" should "take another turn on this build, with the same graph version" in {
    val store  = seeded()
    val client = ScriptedLLMClient.of(CompletionFixture.simple("""{"customer":"Bolt","total":3}"""))
    val agent  = built(builder(client).withRuntime(new GraphRuntime(store)))

    agent.loop.graph.version shouldBe fixture("graphVersion").str
    val result = agent.run(thread, "bill Bolt").value
    result.decodeOutput[Invoice] shouldBe Right(Invoice("Bolt", 3))
    result.messages shouldBe Vector(
      UserMessage("bill Acme"),
      AssistantMessage("""{"customer":"Acme","total":12.5}"""),
      UserMessage("bill Bolt"),
      AssistantMessage("""{"customer":"Bolt","total":3}""")
    )
  }
}

object ObjectOutputFormatPinSpec {

  private def builder(client: ScriptedLLMClient): AgentBuilder =
    Agent.builder("extractor", client).withSystemPrompt("Extract the invoice.").withOutput(invoiceSchema)

  /** The fixture's turn run on this build against `store`, as the fixture's program wrote it. */
  def written(store: InMemoryCheckpointer): ujson.Value = {
    val thread = ThreadId("object-output-1.0.0")
    val client = ScriptedLLMClient.of(
      CompletionFixture.simple("""{"customer":"Acme","total":"lots"}"""),
      CompletionFixture.simple("Here it is:\n```json\n{\"customer\":\"Acme\",\"total\":12.5}\n```")
    )
    val agent  = builder(client).withRuntime(new GraphRuntime(store)).build().fold(e => sys.error(e.message), identity)
    val result = agent.run(thread, "bill Acme").fold(e => sys.error(e.message), identity)
    val stored = store.latest(thread).fold(e => sys.error(e.message), identity).get
    val events = store.eventsAfter(thread, 0L, 10000).fold(e => sys.error(e.message), identity)
    ujson.Obj(
      "threadId"      -> thread.value,
      "graphVersion"  -> agent.loop.graph.version,
      "answer"        -> result.answer.get,
      "checkpoint"    -> Checkpoint.toJson(stored.checkpoint),
      "pendingWrites" -> ujson.Arr.from(stored.pendingWrites.map(upickle.default.writeJs(_))),
      "events"        -> ujson.Arr.from(events.map(upickle.default.writeJs(_))),
      "calls" -> ujson.Arr.from(client.calls.map { (conversation, options) =>
        ujson.Obj(
          "system" -> conversation.messages.head.content,
          "responseFormat" -> options.responseFormat
            .collect { case f: ResponseFormat.JsonSchema =>
              ujson.Obj("name" -> f.name, "strict" -> f.strict, "schema" -> f.schema)
            }
            .getOrElse(ujson.Null)
        )
      })
    )
  }

  /** What differs from run to run, whatever the build: the wall clock, a call's duration and the trace id. */
  private val Volatile = Set("createdAt", "timestamp", "duration", "trace")

  /** `json` with its run id (a random UUID, also the stem of every checkpoint id) and its volatile values fixed. */
  def normalised(json: ujson.Value): ujson.Value = {
    val runId = json("checkpoint")("runId").str
    def scrub(value: ujson.Value): ujson.Value = value match {
      case ujson.Obj(fields) =>
        ujson.Obj.from(fields.map((k, v) => k -> (if (Volatile(k)) ujson.Str("-") else scrub(v))))
      case ujson.Arr(items) => ujson.Arr.from(items.map(scrub))
      case ujson.Str(s)     => ujson.Str(s.replace(runId, "RUN"))
      case other            => other
    }
    scrub(json)
  }
}
