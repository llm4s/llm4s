package org.llm4s.llmconnect.provider

import org.llm4s.error.{ CancelledError, NetworkError }
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.{ ByteArrayInputStream, IOException, InputStream }
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable.ListBuffer

/** SSE parsing of `generation_stream`: framing, chunk boundaries, malformed and truncated streams. */
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

  private def event(text: String, stop: String, prompt: Int = 7, gen: Int = 1): String =
    s"""{"results":[{"generated_text":${ujson
        .Str(text)
        .render()},"generated_token_count":$gen,"input_token_count":$prompt,"stop_reason":"$stop"}]}"""

  private val sampleEvents = Seq(
    event("Héllo 🌍", "not_finished", gen = 1),
    event(" wörld", "eos_token", gen = 2)
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
    chunks = List((Some("Héllo 🌍"), None), (Some(" wörld"), Some("eos_token"))),
    content = "Héllo 🌍 wörld",
    usage = Some((7, 2, 9))
  )

  test("baseline: events are accumulated, not_finished is not a finish reason, last counts win") {
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

  test("empty results, an empty object and an empty-text event emit no chunk and do not fail") {
    val text = Seq("""{"results":[]}""", "{}", event("", "not_finished"), event("x", "eos_token"))
    val seen = observe(sse(text))
    seen.chunks shouldBe List((Some("x"), Some("eos_token")))
  }

  test("a stop reason other than not_finished is passed through verbatim (no normalisation)") {
    Seq("max_tokens", "stop_sequence", "eos_token", "time_limit", "cancelled", "error").foreach { reason =>
      val seen = observe(sse(Seq(event("a", "not_finished"), event("", reason))))
      withClue(reason)(seen.chunks.last shouldBe ((None, Some(reason))))
    }
  }

  test("a stream that ends without a terminal event completes with the text so far (documented gap)") {
    val seen = observe(sse(Seq(event("par", "not_finished"))))
    seen.content shouldBe "par"
    seen.chunks.flatMap(_._2) shouldBe empty
  }

  test("an empty stream and a stream with only [DONE] complete with empty text and no usage") {
    observe("") shouldBe Observed(Nil, "", None)
    observe("data: [DONE]\n\n") shouldBe Observed(Nil, "", None)
  }

  test("a very long event (2 MB of text) streams through intact") {
    val big  = "x" * (2 * 1024 * 1024)
    val seen = observe(sse(Seq(event(big, "eos_token"))))
    seen.content.length shouldBe big.length
  }

  test("malformed JSON mid-stream is a Left, after the earlier chunks were delivered") {
    val text   = sse(Seq(event("Hel", "not_finished"))) + "\ndata: {not json\n\n" + sse(Seq(event("lo", "eos_token")))
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = new WatsonXClient(config, httpClient = streaming(streamOf(bytes(text))))
      .streamComplete(hi, CompletionOptions(), chunks += _)
    result.isLeft shouldBe true
    chunks.flatMap(_.content).mkString shouldBe "Hel"
  }

  test("a final event cut off mid-JSON is a Left, not a silently shortened completion") {
    val cut = sse(Seq(event("Hel", "not_finished"))) + "\ndata: {\"results\":[{\"generated_text\":\"lo"
    run(bytes(cut))._1.isLeft shouldBe true
  }

  test("data that is JSON but not an object is a Left") {
    Seq("5", "null", "[]", "\"s\"", "true").foreach { data =>
      withClue(s"data: $data -> ")(run(bytes(s"data: $data\n\n"))._1.isLeft shouldBe true)
    }
  }

  test("one JSON value split across several data: lines is not reassembled (known limitation, IBM sends one line)") {
    val json  = event("Hi", "eos_token")
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
