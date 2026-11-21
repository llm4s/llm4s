package org.llm4s.rag.evaluation

import org.llm4s.security.Secret
import org.llm4s.trace.{ LangfuseBatchSender, LangfuseHttpApiCaller }
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.ListMap

class RAGASLangfuseObserverSpec extends AnyFlatSpec with Matchers with LoneElement {

  private class RecordingBatchSender extends LangfuseBatchSender {
    var batches: Vector[(Seq[ujson.Obj], LangfuseHttpApiCaller)] = Vector.empty

    override def sendBatch(events: Seq[ujson.Obj], config: LangfuseHttpApiCaller): Unit =
      batches :+= ((events.toVector, config))
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

  "logEvaluation" should "send one ordered batch with the trace inputs, metadata and scores" in {
    val sender = new RecordingBatchSender
    observer(sender).logEvaluation(result)

    val events = sender.batches.loneElement._1
    events.map(_("type").str) shouldBe Seq(
      "trace-create",
      "score-create",
      "score-create",
      "score-create",
      "span-create"
    )
    val trace = events.head("body")
    trace("name").str shouldBe "RAGAS Evaluation"
    trace("input") shouldBe ujson.Obj(
      "question" -> "What powers the station?",
      "answer"   -> "The station uses solar panels.",
      "contexts" -> ujson.Arr("The station has solar panels.", "Batteries store surplus energy.")
    )
    trace("output") shouldBe ujson.Obj("ragasScore" -> 0.6, "metricCount" -> 2)
    trace("metadata") shouldBe ujson.Obj(
      "environment"    -> "test-environment",
      "release"        -> "test-release",
      "version"        -> "test-version",
      "hasGroundTruth" -> true,
      "evaluatedAt"    -> 1234L
    )
    events.slice(1, 4).map(event => (event("body")("name").str, event("body")("value").num)) shouldBe Seq(
      "faithfulness"     -> 0.8,
      "answer_relevancy" -> 0.4,
      "ragas_score"      -> 0.6
    )
  }

  it should "attach every event to a supplied trace without creating another trace" in {
    val sender = new RecordingBatchSender
    observer(sender).logEvaluation(result, traceId = Some("t-1"))

    val events = sender.batches.loneElement._1
    events.map(_("type").str) shouldBe Seq("score-create", "score-create", "score-create", "span-create")
    events.foreach(event => event("body")("traceId").str shouldBe "t-1")
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

    val events = sender.batches.loneElement._1
    events.head("body")("comment").str shouldBe s"first: ${"x" * 50}, second: ${"y" * 50}, third: ${"z" * 50}"
    events(1)("body")("comment").str shouldBe "No additional details"
  }

  Seq(
    ("omit an emoji crossing the detail boundary", "a" * 49 + "\uD83D\uDE00" + "tail", "a" * 49),
    ("keep an emoji ending at the detail boundary", "b" * 48 + "\uD83D\uDE00" + "tail", "b" * 48 + "\uD83D\uDE00")
  ).foreach { case (description, detail, expected) =>
    it should description in {
      val sender = new RecordingBatchSender
      observer(sender).logEvaluation(
        result.copy(metrics = Seq(MetricResult("unicode", 0.7, Map("detail" -> detail)))),
        traceId = Some("t-1")
      )

      sender.batches.loneElement._1.head("body")("comment").str shouldBe s"detail: $expected"
    }
  }

  Seq(
    ("cut a long answer at 500 UTF-16 units", "a" * 501, "a" * 500),
    ("omit an emoji crossing the answer boundary", "b" * 499 + "\uD83D\uDE00" + "tail", "b" * 499),
    ("keep an emoji ending at the answer boundary", "c" * 498 + "\uD83D\uDE00" + "tail", "c" * 498 + "\uD83D\uDE00")
  ).foreach { case (description, answer, expected) =>
    it should description in {
      val sender = new RecordingBatchSender
      observer(sender).logEvaluation(result.copy(sample = result.sample.copy(answer = answer)))

      val events = sender.batches.loneElement._1
      events.head("body")("input")("answer").str shouldBe answer
      val span = events.last("body")
      span("name").str shouldBe "Evaluation Details"
      span("input") shouldBe ujson.Obj(
        "question"       -> "What powers the station?",
        "answer"         -> expected,
        "contextCount"   -> 2,
        "hasGroundTruth" -> true
      )
      span("output") shouldBe ujson.Obj(
        "metrics"    -> ujson.Obj("faithfulness" -> 0.8, "answer_relevancy" -> 0.4),
        "ragasScore" -> 0.6
      )
      span("metadata") shouldBe ujson.Obj("sampleMetadata" -> ujson.Obj("source" -> "station-manual"))
    }
  }

  "logEvaluationSummary" should "send a batch trace followed by every average and the overall score" in {
    val sender = new RecordingBatchSender
    observer(sender).logEvaluationSummary(summary)

    val events = sender.batches.loneElement._1
    events.map(_("type").str) shouldBe Seq("trace-create", "score-create", "score-create", "score-create")
    val trace = events.head("body")
    trace("name").str shouldBe "RAGAS Batch Evaluation"
    trace("input") shouldBe ujson.Obj("sampleCount" -> 2)
    trace("output") shouldBe ujson.Obj(
      "overallRagasScore" -> 0.5,
      "averages"          -> ujson.Obj("faithfulness" -> 0.7, "answer_relevancy" -> 0.3)
    )
    trace("metadata") shouldBe ujson.Obj(
      "environment"    -> "test-environment",
      "release"        -> "test-release",
      "version"        -> "test-version",
      "evaluationType" -> "batch"
    )
    events.tail.map(event => event("body")("name").str -> event("body")("value").num).toMap shouldBe Map(
      "avg_faithfulness"     -> 0.7,
      "avg_answer_relevancy" -> 0.3,
      "avg_ragas_score"      -> 0.5
    )
    events.last("body")("name").str shouldBe "avg_ragas_score"
  }

  "RAGASLangfuseObserver" should "pass the configured URL and keys to the sender for every batch" in {
    val sender = new RecordingBatchSender
    val obs    = observer(sender)
    obs.logEvaluation(result)
    obs.logEvaluationSummary(summary)

    sender.batches.map(_._2) shouldBe Vector(httpConfig, httpConfig)
  }
}
