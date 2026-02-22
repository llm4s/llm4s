package org.llm4s.trace

import cats.Id
import org.llm4s.llmconnect.model.{ EmbeddingUsage, TokenUsage }
import org.llm4s.trace.model._
import org.llm4s.trace.store.{ InMemoryTraceStore, TraceQuery }
import org.llm4s.types.TraceId
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.time.Instant

class TraceCollectorPropertySpec
    extends AnyFlatSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with BeforeAndAfterEach {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 50)

  private var store: InMemoryTraceStore = _

  override def beforeEach(): Unit =
    store = InMemoryTraceStore()

  // ---- generators ----

  val genNonEmptyString: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  val genId: Gen[String] = Gen.uuid.map(_.toString)

  val genTraceId: Gen[TraceId] = genId.map(TraceId(_))

  val genInstant: Gen[Instant] =
    Gen.choose(0L, 4102444800000L).map(Instant.ofEpochMilli)

  val genTokenUsage: Gen[TokenUsage] =
    for {
      prompt     <- Gen.choose(0, 10000)
      completion <- Gen.choose(0, 10000)
    } yield TokenUsage(prompt, completion, prompt + completion)

  val genEmbeddingUsage: Gen[EmbeddingUsage] =
    for {
      prompt <- Gen.choose(0, 10000)
    } yield EmbeddingUsage(prompt, prompt)

  val genCacheMissReason: Gen[TraceEvent.CacheMissReason] =
    Gen.oneOf(
      TraceEvent.CacheMissReason.LowSimilarity,
      TraceEvent.CacheMissReason.TtlExpired,
      TraceEvent.CacheMissReason.OptionsMismatch
    )

  // Generators for every TraceEvent subtype
  val genAgentInitialized: Gen[TraceEvent] =
    for {
      query <- genNonEmptyString
      tools <- Gen.listOf(genNonEmptyString)
      ts    <- genInstant
    } yield TraceEvent.AgentInitialized(query, tools.toVector, ts)

  val genCompletionReceived: Gen[TraceEvent] =
    for {
      id        <- genId
      model     <- genNonEmptyString
      toolCalls <- Gen.choose(0, 10)
      content   <- Gen.alphaStr
      ts        <- genInstant
    } yield TraceEvent.CompletionReceived(id, model, toolCalls, content, ts)

  val genToolExecutedSuccess: Gen[TraceEvent] =
    for {
      name     <- genNonEmptyString
      input    <- Gen.alphaStr
      output   <- Gen.alphaStr
      duration <- Gen.choose(0L, 10000L)
      ts       <- genInstant
    } yield TraceEvent.ToolExecuted(name, input, output, duration, success = true, ts)

  val genToolExecutedFailure: Gen[TraceEvent] =
    for {
      name     <- genNonEmptyString
      input    <- Gen.alphaStr
      output   <- Gen.alphaStr
      duration <- Gen.choose(0L, 10000L)
      ts       <- genInstant
    } yield TraceEvent.ToolExecuted(name, input, output, duration, success = false, ts)

  val genErrorOccurred: Gen[TraceEvent] =
    for {
      msg     <- genNonEmptyString
      context <- Gen.alphaStr
      ts      <- genInstant
    } yield TraceEvent.ErrorOccurred(new RuntimeException(msg), context, ts)

  val genTokenUsageRecorded: Gen[TraceEvent] =
    for {
      usage     <- genTokenUsage
      model     <- genNonEmptyString
      operation <- genNonEmptyString
      ts        <- genInstant
    } yield TraceEvent.TokenUsageRecorded(usage, model, operation, ts)

  val genAgentStateUpdated: Gen[TraceEvent] =
    for {
      status <- genNonEmptyString
      msgs   <- Gen.choose(0, 100)
      logs   <- Gen.choose(0, 100)
      ts     <- genInstant
    } yield TraceEvent.AgentStateUpdated(status, msgs, logs, ts)

  val genEmbeddingUsageRecorded: Gen[TraceEvent] =
    for {
      usage      <- genEmbeddingUsage
      model      <- genNonEmptyString
      operation  <- genNonEmptyString
      inputCount <- Gen.choose(1, 100)
      ts         <- genInstant
    } yield TraceEvent.EmbeddingUsageRecorded(usage, model, operation, inputCount, ts)

  val genCostRecorded: Gen[TraceEvent] =
    for {
      cost       <- Gen.choose(0.0, 1.0)
      model      <- genNonEmptyString
      operation  <- genNonEmptyString
      tokenCount <- Gen.choose(0, 10000)
      costType   <- genNonEmptyString
      ts         <- genInstant
    } yield TraceEvent.CostRecorded(cost, model, operation, tokenCount, costType, ts)

  val genRAGOperationCompleted: Gen[TraceEvent] =
    for {
      operation  <- genNonEmptyString
      durationMs <- Gen.choose(0L, 60000L)
      ts         <- genInstant
    } yield TraceEvent.RAGOperationCompleted(operation, durationMs, timestamp = ts)

  val genCacheHit: Gen[TraceEvent] =
    for {
      similarity <- Gen.choose(0.0, 1.0)
      threshold  <- Gen.choose(0.0, 1.0)
      ts         <- genInstant
    } yield TraceEvent.CacheHit(similarity, threshold, ts)

  val genCacheMiss: Gen[TraceEvent] =
    for {
      reason <- genCacheMissReason
      ts     <- genInstant
    } yield TraceEvent.CacheMiss(reason, ts)

  val genAnyTraceEvent: Gen[TraceEvent] = Gen.oneOf(
    genAgentInitialized,
    genCompletionReceived,
    genToolExecutedSuccess,
    genToolExecutedFailure,
    genErrorOccurred,
    genTokenUsageRecorded,
    genAgentStateUpdated,
    genEmbeddingUsageRecorded,
    genCostRecorded,
    genRAGOperationCompleted,
    genCacheHit,
    genCacheMiss
  )

  // ---- helpers ----

  private def collector(): TraceCollectorTracing[Id] =
    TraceCollectorTracing(store).getOrElse(fail("could not create TraceCollectorTracing"))

  // ---- one span per traceEvent call ----

  "TraceCollectorTracing.traceEvent" should "produce exactly one span per call" in {
    forAll(genAnyTraceEvent) { event =>
      store.clear()
      val tc     = collector()
      val before = store.getSpans(tc.traceId).size
      tc.traceEvent(event)
      store.getSpans(tc.traceId).size shouldBe (before + 1)
    }
  }

  it should "store all spans under the collector's traceId" in {
    forAll(Gen.nonEmptyListOf(genAnyTraceEvent)) { events =>
      store.clear()
      val tc = collector()
      events.foreach(tc.traceEvent)
      val spans = store.getSpans(tc.traceId)
      spans should have size events.size.toLong
      spans.foreach(_.traceId shouldBe tc.traceId)
    }
  }

  // ---- SpanKind mapping per event type ----

  "TraceCollectorTracing" should "emit AgentCall spans for AgentInitialized" in {
    forAll(genAgentInitialized) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.kind shouldBe SpanKind.AgentCall
    }
  }

  it should "emit LlmCall spans for CompletionReceived" in {
    forAll(genCompletionReceived) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.kind shouldBe SpanKind.LlmCall
    }
  }

  it should "emit ToolCall spans for ToolExecuted" in {
    forAll(Gen.oneOf(genToolExecutedSuccess, genToolExecutedFailure)) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.kind shouldBe SpanKind.ToolCall
    }
  }

  it should "emit Embedding spans for EmbeddingUsageRecorded" in {
    forAll(genEmbeddingUsageRecorded) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.kind shouldBe SpanKind.Embedding
    }
  }

  it should "emit Rag spans for RAGOperationCompleted" in {
    forAll(genRAGOperationCompleted) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.kind shouldBe SpanKind.Rag
    }
  }

  it should "emit Cache spans for CacheHit" in {
    forAll(genCacheHit) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.kind shouldBe SpanKind.Cache
    }
  }

  it should "emit Cache spans for CacheMiss" in {
    forAll(genCacheMiss) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.kind shouldBe SpanKind.Cache
    }
  }

  // ---- ToolExecuted status mapping ----

  it should "emit Ok status for successful ToolExecuted events" in {
    forAll(genToolExecutedSuccess) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.status shouldBe SpanStatus.Ok
    }
  }

  it should "emit Error status for failed ToolExecuted events" in {
    forAll(genToolExecutedFailure) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getSpans(tc.traceId).head.status shouldBe a[SpanStatus.Error]
    }
  }

  // ---- ErrorOccurred → SpanStatus.Error with exception message ----

  it should "emit Error status containing the exception message for ErrorOccurred" in {
    forAll(genNonEmptyString, Gen.alphaStr, genInstant) { (msg, context, ts) =>
      store.clear()
      val tc = collector()
      tc.traceEvent(TraceEvent.ErrorOccurred(new RuntimeException(msg), context, ts))
      val span = store.getSpans(tc.traceId).head
      span.status match {
        case SpanStatus.Error(errorMsg) => errorMsg should include(msg)
        case other                      => fail(s"Expected SpanStatus.Error, got: $other")
      }
    }
  }

  // ---- traceError → SpanStatus.Error ----

  "TraceCollectorTracing.traceError" should "emit an error span with the exception message" in {
    forAll(genNonEmptyString, Gen.alphaStr) { (msg, context) =>
      store.clear()
      val tc = collector()
      tc.traceError(new RuntimeException(msg), context)
      val spans = store.getSpans(tc.traceId)
      spans should have size 1L
      spans.head.status match {
        case SpanStatus.Error(errorMsg) => errorMsg should include(msg)
        case other                      => fail(s"Expected SpanStatus.Error, got: $other")
      }
    }
  }

  it should "return Right(())" in {
    forAll(genNonEmptyString, Gen.alphaStr) { (msg, context) =>
      store.clear()
      val tc = collector()
      tc.traceError(new RuntimeException(msg), context) shouldBe Right(())
    }
  }

  // ---- traceToolCall ----

  "TraceCollectorTracing.traceToolCall" should "emit a ToolCall span with Ok status" in {
    forAll(genNonEmptyString, Gen.alphaStr, Gen.alphaStr) { (toolName, input, output) =>
      store.clear()
      val tc = collector()
      tc.traceToolCall(toolName, input, output)
      val spans = store.getSpans(tc.traceId)
      spans should have size 1L
      spans.head.kind shouldBe SpanKind.ToolCall
      spans.head.status shouldBe SpanStatus.Ok
    }
  }

  // ---- traceCompletion ----

  "TraceCollectorTracing.traceCompletion" should "emit an LlmCall span with Ok status" in {
    forAll(genNonEmptyString) { model =>
      store.clear()
      val tc = collector()
      val completion = org.llm4s.llmconnect.model.Completion(
        id = "cmp-1",
        created = 0L,
        content = "",
        model = model,
        message = org.llm4s.llmconnect.model.AssistantMessage("", List.empty)
      )
      tc.traceCompletion(completion, model)
      val spans = store.getSpans(tc.traceId)
      spans should have size 1L
      spans.head.kind shouldBe SpanKind.LlmCall
      spans.head.status shouldBe SpanStatus.Ok
    }
  }

  // ---- traceTokenUsage ----

  "TraceCollectorTracing.traceTokenUsage" should "emit an Internal span with Ok status" in {
    forAll(genTokenUsage, genNonEmptyString, genNonEmptyString) { (usage, model, operation) =>
      store.clear()
      val tc = collector()
      tc.traceTokenUsage(usage, model, operation)
      val spans = store.getSpans(tc.traceId)
      spans should have size 1L
      spans.head.kind shouldBe SpanKind.Internal
      spans.head.status shouldBe SpanStatus.Ok
    }
  }

  // ---- Fixed traceId injection ----

  "TraceCollectorTracing with explicit traceId" should "use the provided traceId for all spans" in {
    forAll(genTraceId, genAnyTraceEvent) { (traceId, event) =>
      store.clear()
      val tc = TraceCollectorTracing(store, traceId).getOrElse(fail("could not create TraceCollectorTracing"))
      tc.traceId shouldBe traceId
      tc.traceEvent(event)
      store.getSpans(traceId) should not be empty
      store.getSpans(traceId).foreach(_.traceId shouldBe traceId)
    }
  }

  // ---- CacheHit hit attribute ----

  "TraceCollectorTracing" should "set hit=true attribute for CacheHit events" in {
    forAll(genCacheHit) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      val span = store.getSpans(tc.traceId).head
      span.attributes.get("hit").flatMap(_.asBoolean) shouldBe Some(true)
    }
  }

  it should "set hit=false attribute for CacheMiss events" in {
    forAll(genCacheMiss) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      val span = store.getSpans(tc.traceId).head
      span.attributes.get("hit").flatMap(_.asBoolean) shouldBe Some(false)
    }
  }

  // ---- Trace record is persisted (regression for missing saveTrace bug) ----

  "TraceCollectorTracing" should "persist a Trace record visible via getTrace" in {
    forAll(genAnyTraceEvent) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.getTrace(tc.traceId) shouldBe defined
      store.getTrace(tc.traceId).map(_.traceId) shouldBe Some(tc.traceId)
    }
  }

  it should "make the trace visible via queryTraces" in {
    forAll(genAnyTraceEvent) { event =>
      store.clear()
      val tc = collector()
      tc.traceEvent(event)
      store.queryTraces(TraceQuery.empty).traces should not be empty
    }
  }

  it should "persist the Trace record before any spans are recorded" in {
    store.clear()
    val tc = collector()
    store.getTrace(tc.traceId) shouldBe defined
  }
}
