package org.llm4s.llmconnect.provider

import org.llm4s.error.{ CancelledError, NetworkError, ServiceError }
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.{ ByteArrayInputStream, IOException, InputStream }
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ListBuffer

/** SSE parsing of `chat_stream`: framing, chunk boundaries, tool-call fragments, malformed and truncated streams. */
class WatsonXStreamSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.config

  private val hi = Conversation(Seq(UserMessage("Hi")))

  /** Serves `bytes` in at most the given segments, so a read never spans a segment boundary. */
  final private class SegmentedStream(bytes: Array[Byte], cuts: Seq[Int]) extends InputStream:
    private val bounds = (cuts.filter(c => c > 0 && c < bytes.length).distinct.sorted :+ bytes.length).toVector
    private var pos    = 0
    val closedFlag     = new AtomicBoolean(false)
    override def read(): Int =
      if pos >= bytes.length then -1
      else
        val b = bytes(pos) & 0xff
        pos += 1
        b
    override def read(buf: Array[Byte], off: Int, len: Int): Int =
      if pos >= bytes.length then -1
      else
        val limit = bounds.find(_ > pos).getOrElse(bytes.length)
        val n     = math.min(len, limit - pos)
        System.arraycopy(bytes, pos, buf, off, n)
        pos += n
        n
    override def close(): Unit = closedFlag.set(true)

  /** Serves `bytes`, then fails the next read with `failure`. */
  final private class FailingStream(bytes: Array[Byte], failure: => Throwable) extends InputStream:
    private val inner = new ByteArrayInputStream(bytes)
    val closedFlag    = new AtomicBoolean(false)
    override def read(): Int = inner.read() match
      case -1 => throw failure
      case b  => b
    override def read(buf: Array[Byte], off: Int, len: Int): Int = inner.read(buf, off, len) match
      case -1 => throw failure
      case n  => n
    override def close(): Unit = closedFlag.set(true)

  /** One chat_stream event: a content delta, a `finish_reason` (`not_finished` stands for none yet) and the usage. */
  private def event(text: String, stop: String, prompt: Int = 7, gen: Int = 1): String =
    val finish = if stop == "not_finished" then "null" else ujson.Str(stop).render()
    s"""{"id":"c-1","choices":[{"index":0,"delta":{"content":${ujson
        .Str(text)
        .render()}},"finish_reason":$finish}],"usage":{"prompt_tokens":$prompt,"completion_tokens":$gen,"total_tokens":${prompt + gen}}}"""

  private val sampleEvents = Seq(
    event("Héllo 🌍", "not_finished", gen = 1),
    event(" wörld", "stop", gen = 2)
  )

  private def sse(events: Seq[String], eol: String = "\n"): String =
    events.zipWithIndex
      .map { case (data, i) => s"id: ${i + 1}${eol}event: message${eol}data: $data$eol" }
      .mkString(eol) + eol

  private val sample = sse(sampleEvents)

  /** What a run observed, comparable across runs. */
  private case class Observed(
    chunks: List[(Option[String], Option[String])],
    content: String,
    usage: Option[(Int, Int, Int)]
  )

  private def run(in: InputStream): (Either[org.llm4s.error.LLMError, Observed], StubHttp) =
    val http   = streaming(streamOf(in))
    val chunks = ListBuffer.empty[(Option[String], Option[String])]
    val result = new WatsonXClient(config, httpClient = http)
      .streamComplete(hi, CompletionOptions(), c => chunks += ((c.content, c.finishReason)))
      .map(c =>
        Observed(chunks.toList, c.content, c.usage.map(u => (u.promptTokens, u.completionTokens, u.totalTokens)))
      )
    (result, http)

  private def observe(text: String): Observed =
    run(bytes(text))._1.getOrElse(fail(s"expected success for: ${text.take(80)}"))

  private val expected = Observed(
    chunks = List((Some("Héllo 🌍"), None), (Some(" wörld"), None), (None, Some("stop"))),
    content = "Héllo 🌍 wörld",
    usage = Some((7, 2, 9))
  )

  test("baseline: content deltas are accumulated, the finish reason closes the stream, the last usage wins") {
    observe(sample) shouldBe expected
  }

  test("splitting the byte stream at every single offset (incl. inside multi-byte characters) changes nothing") {
    val all = sample.getBytes("UTF-8")
    (1 until all.length).foreach { at =>
      val result = run(new SegmentedStream(all, Seq(at)))._1
      withClue(s"split at byte $at: ")(result shouldBe Right(expected))
    }
  }

  test("splitting at every pair of offsets within the first event and one byte at a time also changes nothing") {
    val all = sample.getBytes("UTF-8")
    run(new SegmentedStream(all, 1 until all.length))._1 shouldBe Right(expected)
    val firstEventEnd = sample.indexOf("\n\n")
    for
      a <- 1 until firstEventEnd by 7
      b <- (a + 1) until firstEventEnd by 11
    do withClue(s"split at $a and $b: ")(run(new SegmentedStream(all, Seq(a, b)))._1 shouldBe Right(expected))
  }

  test("framing variants all yield the same result") {
    val crlf    = sse(sampleEvents, "\r\n")
    val noSpace = sample.replace("data: ", "data:")
    val comments =
      ": keep-alive\n\n" + sample.replace("event: message\n", ": c\nevent: message\nretry: 100\n") + ": bye\n"
    val done       = sample + "\ndata: [DONE]\n\n"
    val noFinalEol = sample.stripSuffix("\n")
    val bareCr     = sample.replace("\n", "\r")
    val blanks     = "\n\n\n" + sample.replace("\n\n", "\n\n\ndata:\n\ndata:   \n\n")
    val padded     = sample.replace("data: ", "data:    ").replace("\n", "  \n")
    Seq(
      "crlf"         -> crlf,
      "no space"     -> noSpace,
      "comments"     -> comments,
      "[DONE]"       -> done,
      "no final eol" -> noFinalEol,
      "bare CR"      -> bareCr,
      "empty events" -> blanks,
      "padding"      -> padded
    ).foreach { case (name, text) => withClue(s"$name: ")(observe(text) shouldBe expected) }
  }

  test("an unknown field or a non-data line is ignored, never parsed as JSON") {
    observe("event: ping\nid: 9\nretry: 5\n: note\n\n" + sample) shouldBe expected
  }

  test("empty choices, an empty object and an empty-text event emit no chunk and do not fail") {
    val text = Seq("""{"choices":[]}""", "{}", event("", "not_finished"), event("x", "stop"))
    val seen = observe(sse(text))
    seen.chunks shouldBe List((Some("x"), None), (None, Some("stop")))
  }

  private val normalStops = Seq("stop", "length", "tool_calls", "something_new", "ERROR_X")
  private val errorStops  = Seq("error", "cancelled", "time_limit", "ERROR", "Cancelled", "TIME_LIMIT", " error ")

  test("a normal finish reason is delivered normalised (trimmed, lower case) and the stream succeeds") {
    normalStops.foreach { reason =>
      val seen = observe(sse(Seq(event("a", "not_finished"), event("", reason))))
      withClue(reason)(seen.chunks.last shouldBe ((None, Some(reason.trim.toLowerCase))))
    }
  }

  test("finish_reason table: error-type values (any case) are a ServiceError naming the reason; the rest are Right") {
    errorStops.foreach { reason =>
      val result = run(bytes(sse(Seq(event("par", "not_finished"), event("", reason)))))._1
      withClue(s"'$reason': ")(result.left.toOption match
        case Some(e: ServiceError) =>
          e.message should include(s"finish_reason '${reason.trim.toLowerCase}'")
          e.provider shouldBe "watsonx"
        case other => fail(s"expected ServiceError, got $other")
      )
    }
    normalStops.foreach { reason =>
      withClue(s"'$reason': ")(run(bytes(sse(Seq(event("par", reason)))))._1.isRight shouldBe true)
    }
  }

  test("an error-type ending never returns the partial text as a success (chunks were still delivered)") {
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = new WatsonXClient(config, httpClient = streaming(streamOf(bytes(sse(Seq(event("partial", "error")))))))
      .streamComplete(hi, CompletionOptions(), chunks += _)
    result.isLeft shouldBe true
    result.toOption shouldBe None
    chunks.flatMap(_.content).mkString shouldBe "partial"
  }

  test("a stream that ends without a finish_reason is a ServiceError, not a success with the text so far") {
    val result = run(bytes(sse(Seq(event("par", "not_finished")))))._1
    result.left.toOption match
      case Some(e: ServiceError) => e.message should include("without a finish_reason")
      case other                 => fail(s"expected ServiceError, got $other")
  }

  test("an empty stream and a stream with only [DONE] have no finish_reason: ServiceError") {
    Seq("", "data: [DONE]\n\n", ": keep-alive\n\n", """{"choices":[]}""").foreach { text =>
      withClue(text)(run(bytes(text))._1.left.toOption.exists(_.isInstanceOf[ServiceError]) shouldBe true)
    }
  }

  test("a very long event (2 MB of text) streams through intact") {
    val big  = "x" * (2 * 1024 * 1024)
    val seen = observe(sse(Seq(event(big, "stop"))))
    seen.content.length shouldBe big.length
  }

  test("malformed JSON mid-stream is a Left, after the earlier chunks were delivered") {
    val text   = sse(Seq(event("Hel", "not_finished"))) + "\ndata: {not json\n\n" + sse(Seq(event("lo", "stop")))
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = new WatsonXClient(config, httpClient = streaming(streamOf(bytes(text))))
      .streamComplete(hi, CompletionOptions(), chunks += _)
    result.isLeft shouldBe true
    chunks.flatMap(_.content).mkString shouldBe "Hel"
  }

  test("a final event cut off mid-JSON is a Left, not a silently shortened completion") {
    val cut = sse(Seq(event("Hel", "not_finished"))) + "\ndata: {\"choices\":[{\"delta\":{\"content\":\"lo"
    run(bytes(cut))._1.isLeft shouldBe true
  }

  test("data that is JSON but not an object is a Left") {
    Seq("5", "null", "[]", "\"s\"", "true").foreach { data =>
      withClue(s"data: $data -> ")(run(bytes(s"data: $data\n\n"))._1.isLeft shouldBe true)
    }
  }

  test("one JSON value split across several data: lines is not reassembled (known limitation, IBM sends one line)") {
    val json  = event("Hi", "stop")
    val split = "data: " + json.take(10) + "\ndata: " + json.drop(10) + "\n\n"
    run(bytes(split))._1.isLeft shouldBe true
  }

  test("an I/O failure mid-stream is a NetworkError, earlier chunks were delivered, and the stream is closed") {
    val stream =
      new FailingStream((sse(Seq(event("Hel", "not_finished"))) + "\n").getBytes("UTF-8"), new IOException("reset"))
    val seen = ListBuffer.empty[StreamedChunk]
    val result = new WatsonXClient(config, httpClient = streaming(streamOf(stream)))
      .streamComplete(hi, CompletionOptions(), seen += _)
    result.left.toOption.exists(_.isInstanceOf[NetworkError]) shouldBe true
    seen.flatMap(_.content).mkString shouldBe "Hel"
    stream.closedFlag.get() shouldBe true
  }

  test("an interrupt surfacing from the read is a CancelledError and the stream is closed") {
    val stream = new FailingStream(Array.emptyByteArray, new InterruptedException("stop"))
    val result = new WatsonXClient(config, httpClient = streaming(streamOf(stream)))
      .streamComplete(hi, CompletionOptions(), _ => ())
    Thread.interrupted(): Unit
    result.left.toOption.exists(_.isInstanceOf[CancelledError]) shouldBe true
    stream.closedFlag.get() shouldBe true
  }

  test("the stream is closed after success, after a parse failure, and after an error status") {
    val ok = new SegmentedStream(sample.getBytes("UTF-8"), Nil)
    run(ok)._1.isRight shouldBe true
    ok.closedFlag.get() shouldBe true

    val bad = new SegmentedStream("data: {nope\n\n".getBytes("UTF-8"), Nil)
    run(bad)._1.isLeft shouldBe true
    bad.closedFlag.get() shouldBe true

    val err = new SegmentedStream("""{"errors":[{"message":"x"}]}""".getBytes("UTF-8"), Nil)
    val res = new WatsonXClient(config, httpClient = streaming(streamOf(err, 500)))
      .streamComplete(hi, CompletionOptions(), _ => ())
    res.isLeft shouldBe true
    err.closedFlag.get() shouldBe true
  }

  test("an exception thrown by the caller's onChunk is a Left, not a throw, and the stream is closed") {
    val s = new SegmentedStream(sample.getBytes("UTF-8"), Nil)
    val result = scala.util.Try(
      new WatsonXClient(config, httpClient = streaming(streamOf(s)))
        .streamComplete(hi, CompletionOptions(), _ => throw new IllegalStateException("boom"))
    )
    result.toOption.exists(_.isLeft) shouldBe true
    s.closedFlag.get() shouldBe true
  }

  // ---- tool calls and usage

  private def piece(index: Int, id: Option[String], name: Option[String], args: Option[String]): String =
    val fields = Seq(
      Some(s""""index":$index"""),
      id.map(i => s""""id":${ujson.Str(i).render()},"type":"function""""),
      Some(
        s""""function":{${Seq(
            name.map(n => s""""name":${ujson.Str(n).render()}"""),
            args.map(a => s""""arguments":${ujson.Str(a).render()}""")
          ).flatten.mkString(",")}}"""
      )
    ).flatten
    "{" + fields.mkString(",") + "}"

  private def callEvent(pieces: String*): String =
    s"""{"id":"c-2","choices":[{"index":0,"delta":{"tool_calls":[${pieces.mkString(",")}]},"finish_reason":null}]}"""

  private def finishEvent(reason: String): String =
    s"""{"id":"c-2","choices":[{"index":0,"delta":{},"finish_reason":"$reason"}]}"""

  private def completionOf(events: Seq[String]): (Either[org.llm4s.error.LLMError, Completion], Seq[StreamedChunk]) =
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = new WatsonXClient(config, httpClient = streaming(streamOf(bytes(sse(events)))))
      .streamComplete(hi, CompletionOptions(), chunks += _)
    (result, chunks.toSeq)

  test("tool call fragments are merged by index and reported once, whole, after the last delta") {
    val (result, chunks) = completionOf(
      Seq(
        callEvent(piece(0, Some("call-a"), Some("add"), Some(""))),
        callEvent(piece(0, None, None, Some("{\"a\":"))),
        callEvent(piece(0, None, None, Some("1,\"b\":2}"))),
        finishEvent("tool_calls")
      )
    )
    val completion   = result.getOrElse(fail(s"expected success, got $result"))
    val expectedCall = ToolCall("call-a", "add", ujson.Obj("a" -> 1, "b" -> 2))
    completion.toolCalls shouldBe List(expectedCall)
    completion.message.toolCalls shouldBe Seq(expectedCall)
    chunks.flatMap(_.toolCall) shouldBe Seq(expectedCall)
    chunks.last.finishReason shouldBe Some("tool_calls")
    chunks.indexWhere(_.toolCall.isDefined) should be < chunks.indexWhere(_.finishReason.isDefined)
  }

  test("two calls streamed interleaved are separated by index and reported in index order") {
    val (result, _) = completionOf(
      Seq(
        callEvent(piece(1, Some("call-b"), Some("mul"), Some("{\"x\":"))),
        callEvent(piece(0, Some("call-a"), Some("add"), Some("{\"a\":1}"))),
        callEvent(piece(1, None, None, Some("3}"))),
        finishEvent("tool_calls")
      )
    )
    result.map(_.toolCalls).getOrElse(fail(s"expected success, got $result")) shouldBe List(
      ToolCall("call-a", "add", ujson.Obj("a" -> 1)),
      ToolCall("call-b", "mul", ujson.Obj("x" -> 3))
    )
  }

  test("pieces without an index take their position in the delta's array") {
    val (result, _) = completionOf(
      Seq(
        s"""{"choices":[{"delta":{"tool_calls":[{"id":"c0","function":{"name":"a","arguments":"{}"}},{"id":"c1","function":{"name":"b","arguments":"{}"}}]},"finish_reason":null}]}""",
        finishEvent("tool_calls")
      )
    )
    result.map(_.toolCalls.map(_.name)).getOrElse(fail(s"expected success, got $result")) shouldBe List("a", "b")
  }

  test("a call the service sent without an id gets a generated one, distinct per call and per reply") {
    def ids(): List[String] =
      completionOf(
        Seq(
          callEvent(piece(0, None, Some("a"), Some("{}")), piece(1, None, Some("b"), Some("{}"))),
          finishEvent("tool_calls")
        )
      )._1.map(_.toolCalls.map(_.id)).getOrElse(fail("expected success"))
    val first  = ids()
    val second = ids()
    first.distinct should have size 2
    first.foreach(_ should startWith("call_"))
    first.intersect(second) shouldBe empty
  }

  test("a call with no arguments, null arguments or blank arguments has an empty object") {
    val (result, _) = completionOf(
      Seq(
        callEvent(piece(0, Some("c0"), Some("a"), None)),
        callEvent(piece(1, Some("c1"), Some("b"), Some("  "))),
        finishEvent("tool_calls")
      )
    )
    result.map(_.toolCalls.map(_.arguments)).getOrElse(fail(s"expected success, got $result")) shouldBe List(
      ujson.Obj(),
      ujson.Obj()
    )
  }

  test("arguments sent as a JSON object rather than a string are kept") {
    val (result, _) = completionOf(
      Seq(
        s"""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c0","function":{"name":"a","arguments":{"k":"v"}}}]},"finish_reason":null}]}""",
        finishEvent("tool_calls")
      )
    )
    result.map(_.toolCalls.map(_.arguments)).getOrElse(fail(s"expected success, got $result")) shouldBe List(
      ujson.Obj("k" -> "v")
    )
  }

  test("a streamed call that never got a name is a ProcessingError") {
    val (result, _) = completionOf(Seq(callEvent(piece(0, Some("c0"), None, Some("{}"))), finishEvent("tool_calls")))
    result.left.toOption match
      case Some(e: org.llm4s.error.ProcessingError) => e.message should include("no name")
      case other                                    => fail(s"expected ProcessingError, got $other")
  }

  test("arguments that are not a JSON object are a ProcessingError that does not echo them") {
    Seq("not json SECRETARG", "[1,2]", "5").foreach { args =>
      val (result, _) =
        completionOf(Seq(callEvent(piece(0, Some("c0"), Some("a"), Some(args))), finishEvent("tool_calls")))
      withClue(args) {
        result.left.toOption match
          case Some(e: org.llm4s.error.ProcessingError) =>
            e.message should include("not a JSON object")
            (e.message should not).include("SECRETARG")
          case other => fail(s"expected ProcessingError, got $other")
      }
    }
  }

  test("text and a tool call in one stream: the text chunks come first, then the call, then the finish") {
    val (result, chunks) = completionOf(
      Seq(
        event("Let me check.", "not_finished"),
        callEvent(piece(0, Some("c0"), Some("lookup"), Some("{}"))),
        finishEvent("tool_calls")
      )
    )
    val completion = result.getOrElse(fail(s"expected success, got $result"))
    completion.content shouldBe "Let me check."
    completion.toolCalls.map(_.name) shouldBe List("lookup")
    chunks.map(c => (c.content.isDefined, c.toolCall.isDefined, c.finishReason.isDefined)) shouldBe
      Seq((true, false, false), (false, true, false), (false, false, true))
  }

  test("usage sent in a final chunk with no choices is counted") {
    val (result, _) = completionOf(
      Seq(
        """{"choices":[{"delta":{"content":"Hi"},"finish_reason":"stop"}]}""",
        """{"choices":[],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}"""
      )
    )
    result
      .map(_.usage.map(u => (u.promptTokens, u.completionTokens, u.totalTokens)))
      .getOrElse(fail("expected")) shouldBe
      Some((11, 3, 14))
  }

  test("a stream with no usage at all still succeeds, with no usage reported as zero tokens") {
    val (result, _) = completionOf(Seq("""{"choices":[{"delta":{"content":"Hi"},"finish_reason":"stop"}]}"""))
    result.map(_.content).getOrElse(fail(s"expected success, got $result")) shouldBe "Hi"
  }
