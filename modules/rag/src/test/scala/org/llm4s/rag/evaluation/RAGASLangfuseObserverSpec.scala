package org.llm4s.rag.evaluation

import org.llm4s.security.Secret
import org.llm4s.trace.{ LangfuseBatchSender, LangfuseHttpApiCaller }
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.ListMap

/** What the observer sends: its trace and details span as an OTLP export, its scores as one array (#2883). */
class RAGASLangfuseObserverSpec extends AnyFlatSpec with Matchers with LoneElement {

  private class RecordingBatchSender extends LangfuseBatchSender {
    var traces: Vector[(ujson.Obj, LangfuseHttpApiCaller)]      = Vector.empty
    var scores: Vector[(Seq[ujson.Obj], LangfuseHttpApiCaller)] = Vector.empty

    override def sendTraces(request: ujson.Obj, config: LangfuseHttpApiCaller): Unit =
      traces :+= ((request, config))
    override def sendScores(scores: Seq[ujson.Obj], config: LangfuseHttpApiCaller): Unit =
      this.scores :+= ((scores.toVector, config))

    /** The spans of the one export sent. */
    def spans: Seq[Span]           = Span.all(traces.loneElement._1)
    def sentScores: Seq[ujson.Obj] = scores.loneElement._1
  }

  /** A span of an OTLP export: its ids, name and attributes, string values as strings and numbers as numbers. */
  final private case class Span(
    traceId: String,
    spanId: String,
    parent: Option[String],
    name: String,
    attrs: Map[String, ujson.Value]
  ) {
    def json(key: String): ujson.Value = ujson.read(attrs(key).str)
    def input: ujson.Value             = json("langfuse.observation.input")
    def output: ujson.Value            = json("langfuse.observation.output")
  }
  private object Span {
    def all(request: ujson.Value): Seq[Span] =
      for {
        rs <- request("resourceSpans").arr.toSeq
        ss <- rs("scopeSpans").arr.toSeq
        s  <- ss("spans").arr.toSeq
      } yield Span(
        s("traceId").str,
        s("spanId").str,
        s.obj.get("parentSpanId").map(_.str),
        s("name").str,
        s("attributes").arr.map { a =>
          val v = a("value").obj
          a("key").str -> v
            .get("stringValue")
            .orElse(v.get("boolValue"))
            .orElse(v.get("doubleValue"))
            .getOrElse(
              v.get("intValue").map(i => ujson.Num(i.str.toDouble)).getOrElse(ujson.Null)
            )
        }.toMap
      )
  }

  private val httpConfig = LangfuseHttpApiCaller(
    "https://langfuse.example.test/custom",
    Secret("test-public-key"),
    Secret("test-secret-key")
  )

  private def observer(sender: RecordingBatchSender): RAGASLangfuseObserver =
    new RAGASLangfuseObserver(
      langfuseUrl = httpConfig.langfuseUrl,
      publicKey = httpConfig.publicKey,
      secretKey = httpConfig.secretKey,
      environment = "test-environment",
      release = "test-release",
      version = "test-version",
      batchSender = sender
    )

  private val result = EvalResult(
    sample = EvalSample(
      question = "What powers the station?",
      answer = "The station uses solar panels.",
      contexts = Seq("The station has solar panels.", "Batteries store surplus energy."),
      groundTruth = Some("Solar power"),
      metadata = Map("source" -> "station-manual")
    ),
    metrics = Seq(MetricResult("faithfulness", 0.8), MetricResult("answer_relevancy", 0.4)),
    ragasScore = 0.6,
    evaluatedAt = 1234L
  )

  private val summary = EvalSummary(
    results = Seq(
      result,
      result
        .copy(metrics = Seq(MetricResult("faithfulness", 0.6), MetricResult("answer_relevancy", 0.2)), ragasScore = 0.4)
    ),
    averages = Map("faithfulness" -> 0.7, "answer_relevancy" -> 0.3),
    overallRagasScore = 0.5,
    sampleCount = 2
  )

  private val existingTrace = "4bf92f3577b34da6a3ce929d0e0e4736"

  "logEvaluation" should "send the trace with its inputs and metadata, the details span under it, and the scores" in {
    val sender = new RecordingBatchSender
    observer(sender).logEvaluation(result)

    val spans = sender.spans
    spans.map(_.name) shouldBe Seq("RAGAS Evaluation", "Evaluation Details")
    val trace = spans.head
    (trace.traceId should fullyMatch).regex("[0-9a-f]{32}")
    trace.parent shouldBe None
    trace.attrs("langfuse.trace.name").str shouldBe "RAGAS Evaluation"
    trace.attrs("langfuse.environment").str shouldBe "test-environment"
    trace.input shouldBe ujson.Obj(
      "question" -> "What powers the station?",
      "answer"   -> "The station uses solar panels.",
      "contexts" -> ujson.Arr("The station has solar panels.", "Batteries store surplus energy.")
    )
    trace.output shouldBe ujson.Obj("ragasScore" -> 0.6, "metricCount" -> 2)
    Map(
      "environment"    -> ujson.Str("test-environment"),
      "release"        -> ujson.Str("test-release"),
      "version"        -> ujson.Str("test-version"),
      "hasGroundTruth" -> ujson.Bool(true),
      "evaluatedAt"    -> ujson.Str("1234") // ujson writes a Long as a string
    ).foreach { case (k, v) => trace.attrs(s"langfuse.trace.metadata.$k") shouldBe v }
    spans(1).traceId shouldBe trace.traceId
    spans(1).parent shouldBe Some(trace.spanId)

    val scores = sender.sentScores
    scores.map(s => (s("name").str, s("value").num)) shouldBe Seq(
      "faithfulness"     -> 0.8,
      "answer_relevancy" -> 0.4,
      "ragas_score"      -> 0.6
    )
    scores.foreach { s =>
      s("traceId").str shouldBe trace.traceId
      s("dataType").str shouldBe "NUMERIC"
    }
    scores.map(_("id").str).distinct should have size 3
  }

  it should "add the details span to a supplied trace, and score it, without creating another trace" in {
    val sender = new RecordingBatchSender
    observer(sender).logEvaluation(result, traceId = Some(existingTrace))

    val span = sender.spans.loneElement
    span.name shouldBe "Evaluation Details"
    span.traceId shouldBe existingTrace
    span.attrs.contains("langfuse.trace.name") shouldBe false
    sender.sentScores.foreach(_("traceId").str shouldBe existingTrace)
  }

  it should "score a supplied trace id that is not an OpenTelemetry one, sending no span" in {
    val sender = new RecordingBatchSender
    observer(sender).logEvaluation(result, traceId = Some("t-1"))

    sender.traces shouldBe empty
    sender.sentScores should have size 3
    sender.sentScores.foreach(_("traceId").str shouldBe "t-1")
  }

  it should "include only the first three shortened details and explain when there are none" in {
    val details = ListMap[String, Any](
      "first"  -> ("x" * 51),
      "second" -> ("y" * 52),
      "third"  -> ("z" * 53),
      "fourth" -> "excluded"
    )
    val sender = new RecordingBatchSender
    observer(sender).logEvaluation(
      result.copy(metrics = Seq(MetricResult("with_details", 0.7, details), MetricResult("no_details", 0.3))),
      traceId = Some("t-1")
    )

    val scores = sender.sentScores
    scores.head("comment").str shouldBe s"first: ${"x" * 50}, second: ${"y" * 50}, third: ${"z" * 50}"
    scores(1)("comment").str shouldBe "No additional details"
  }

  Seq(
    ("omit an emoji crossing the detail boundary", "a" * 49 + "😀" + "tail", "a" * 49),
    ("keep an emoji ending at the detail boundary", "b" * 48 + "😀" + "tail", "b" * 48 + "😀")
  ).foreach { case (description, detail, expected) =>
    it should description in {
      val sender = new RecordingBatchSender
      observer(sender).logEvaluation(
        result.copy(metrics = Seq(MetricResult("unicode", 0.7, Map("detail" -> detail)))),
        traceId = Some("t-1")
      )

      sender.sentScores.head("comment").str shouldBe s"detail: $expected"
    }
  }

  Seq(
    ("cut a long answer at 500 UTF-16 units", "a" * 501, "a" * 500),
    ("omit an emoji crossing the answer boundary", "b" * 499 + "😀" + "tail", "b" * 499),
    ("keep an emoji ending at the answer boundary", "c" * 498 + "😀" + "tail", "c" * 498 + "😀")
  ).foreach { case (description, answer, expected) =>
    it should description in {
      val sender = new RecordingBatchSender
      observer(sender).logEvaluation(result.copy(sample = result.sample.copy(answer = answer)))

      val spans = sender.spans
      spans.head.input("answer").str shouldBe answer
      val span = spans.last
      span.name shouldBe "Evaluation Details"
      span.input shouldBe ujson.Obj(
        "question"       -> "What powers the station?",
        "answer"         -> expected,
        "contextCount"   -> 2,
        "hasGroundTruth" -> true
      )
      span.output shouldBe ujson.Obj(
        "metrics"    -> ujson.Obj("faithfulness" -> 0.8, "answer_relevancy" -> 0.4),
        "ragasScore" -> 0.6
      )
      span.json("langfuse.observation.metadata.sampleMetadata") shouldBe ujson.Obj("source" -> "station-manual")
    }
  }

  "logEvaluationSummary" should "send a batch trace, then every average and the overall score" in {
    val sender = new RecordingBatchSender
    observer(sender).logEvaluationSummary(summary)

    val trace = sender.spans.loneElement
    trace.name shouldBe "RAGAS Batch Evaluation"
    trace.attrs("langfuse.trace.name").str shouldBe "RAGAS Batch Evaluation"
    trace.input shouldBe ujson.Obj("sampleCount" -> 2)
    trace.output shouldBe ujson.Obj(
      "overallRagasScore" -> 0.5,
      "averages"          -> ujson.Obj("faithfulness" -> 0.7, "answer_relevancy" -> 0.3)
    )
    trace.attrs("langfuse.trace.metadata.evaluationType").str shouldBe "batch"
    trace.attrs("langfuse.observation.metadata.release").str shouldBe "test-release"

    val scores = sender.sentScores
    scores.map(s => s("name").str -> s("value").num).toMap shouldBe Map(
      "avg_faithfulness"     -> 0.7,
      "avg_answer_relevancy" -> 0.3,
      "avg_ragas_score"      -> 0.5
    )
    scores.last("name").str shouldBe "avg_ragas_score"
    scores.foreach(_("traceId").str shouldBe trace.traceId)
  }

  "RAGASLangfuseObserver" should "pass the configured URL and keys to the sender for every request" in {
    val sender = new RecordingBatchSender
    val obs    = observer(sender)
    obs.logEvaluation(result)
    obs.logEvaluationSummary(summary)

    sender.traces.map(_._2) shouldBe Vector(httpConfig, httpConfig)
    sender.scores.map(_._2) shouldBe Vector(httpConfig, httpConfig)
  }
}
