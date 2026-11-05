package org.llm4s.model

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.core.read.ListAppender
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Message,
  RequestAdjustment,
  RequestOption,
  ResponseFormat,
  UserMessage
}
import org.llm4s.llmconnect.model.RequestAdjustment.{ Adjusted, Dropped, NotNeeded }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters._

/** RFC 0008 part 1: what the transformer drops is reported, and each client warns about it once (#2115). */
class RequestAdjustmentSpec extends AnyFunSuite with Matchers with EitherValues {

  private val messages: Seq[Message] = Seq(UserMessage("hi"))

  private val constrained = ModelCapabilities(
    temperatureConstraint = Some((1.0, 1.0)),
    disallowedParams = Some(Set("top_p", "presence_penalty", "frequency_penalty")),
    supportsFunctionCalling = Some(false),
    supportsResponseSchema = Some(false)
  )

  private def service: ModelRegistryService =
    ModelRegistryTestSupport.defaultServiceResult().fold(e => fail(e.message), identity)

  private def transformer: RequestTransformer =
    RequestTransformer.withOverrides(Map("narrow" -> constrained), service)

  private def withPingTool(options: CompletionOptions): CompletionOptions =
    ToolBuilder[Map[String, Any], String]("ping", "Answers pong", Schema.`object`[Map[String, Any]]("No parameters"))
      .withHandler(_ => Right("pong"))
      .buildSafe()
      .fold(e => fail(e.message), tool => options.withTools(Seq(tool)))

  // ---- the transformer reports what it drops ----

  test("transform reports each dropped or adjusted option, in the order found") {
    val options = withPingTool(
      CompletionOptions(temperature = 0.7, topP = 0.5, presencePenalty = 0.2, frequencyPenalty = 0.3)
        .withResponseFormat(ResponseFormat.Json)
    )

    val result = TransformationResult.transform("narrow", options, messages, transformer).value

    result.adjustments.map(_.option) shouldBe List(
      RequestOption.Temperature,
      RequestOption.TopP,
      RequestOption.PresencePenalty,
      RequestOption.FrequencyPenalty,
      RequestOption.Tools,
      RequestOption.ResponseFormat
    )
    result.adjustments.head shouldBe Adjusted(
      RequestOption.Temperature,
      "0.7",
      "1.0",
      "allowed range for narrow is 1.0-1.0"
    )
    result.adjustments.tail.foreach(_ shouldBe a[Dropped])
    // the options are the ones transformOptions returns
    result.options shouldBe transformer.transformOptions("narrow", options, dropUnsupported = true).value
  }

  test("the default temperature is reported on a model with a fixed temperature, since it is sent") {
    val result = TransformationResult.transform("narrow", CompletionOptions(), messages, transformer).value

    result.adjustments.map(_.option) shouldBe List(RequestOption.Temperature)
  }

  test("an option the model supports is not reported") {
    val result =
      TransformationResult.transform("gpt-4o", CompletionOptions(temperature = 0.7), messages, transformer).value

    result.adjustments shouldBe empty
    result.options.temperature shouldBe 0.7
  }

  test("without dropUnsupported an unsupported option fails, and nothing is reported") {
    val options = CompletionOptions(temperature = 0.7)

    TransformationResult
      .transform("narrow", options, messages, transformer, dropUnsupported = false)
      .isLeft shouldBe true
    TransformationResult
      .transform("gpt-4o", options, messages, transformer, dropUnsupported = false)
      .value
      .adjustments shouldBe empty
  }

  test("a JsonSchema response format the model cannot take is reported as dropped") {
    val schema = ResponseFormat.JsonSchema(ujson.Obj("type" -> "object"))
    // the default temperature, 0.7, is itself adjusted on this model (tested above)
    val options = CompletionOptions(temperature = 1.0).withResponseFormat(schema)

    val result = TransformationResult.transform("narrow", options, messages, transformer).value

    result.options.responseFormat shouldBe None
    result.adjustments shouldBe List(
      Dropped(RequestOption.ResponseFormat, "structured output (JSON schema) not supported for narrow")
    )
  }

  test("a transformer that does not override adjustOptions still transforms, reporting nothing") {
    val custom = new RequestTransformer {
      def transformOptions(
        modelId: String,
        options: CompletionOptions,
        dropUnsupported: Boolean
      ): Result[CompletionOptions] = Right(options.withTemperature(1.0))
      def transformMessages(modelId: String, messages: Seq[Message]): Seq[Message] = messages
      def requiresFakeStreaming(modelId: String): Boolean                          = false
      def disallowedParams(modelId: String): Set[String]                           = Set.empty
    }

    val result = TransformationResult.transform("any", CompletionOptions(temperature = 0.2), messages, custom).value

    result.options.temperature shouldBe 1.0
    result.adjustments shouldBe empty
  }

  // ---- the types ----

  test("TransformationResult and Completion default to no adjustments and set them with withAdjustments") {
    val adjustments = List(Dropped(RequestOption.TopP, "not supported"))

    TransformationResult(CompletionOptions(), messages).adjustments shouldBe empty
    TransformationResult(CompletionOptions(), messages).withAdjustments(adjustments).adjustments shouldBe adjustments

    val completion = Completion("id", Instant.EPOCH, "hi", "m", AssistantMessage("hi"))
    completion.adjustments shouldBe empty
    completion.withAdjustments(adjustments).adjustments shouldBe adjustments
    completion.withAdjustments(adjustments).withAdjustments(Nil) shouldBe completion
  }

  test("each adjustment names its option and round-trips through JSON") {
    val all = List(
      Dropped(RequestOption.Tools, "function calling not supported"),
      Adjusted(RequestOption.Temperature, "0.7", "1.0", "allowed range is 1.0-1.0"),
      NotNeeded(RequestOption.PromptCache, "cached automatically")
    )

    all.map(_.option) shouldBe List(RequestOption.Tools, RequestOption.Temperature, RequestOption.PromptCache)
    all.foreach { adjustment =>
      upickle.default.read[RequestAdjustment](upickle.default.write(adjustment)) shouldBe adjustment
    }
    RequestOption.values.foreach { option =>
      upickle.default.read[RequestOption](upickle.default.write(option)) shouldBe option
    }
  }

  // ---- the client's warn-once log ----

  /** An AdjustmentLog writing to its own logger, and the events it logged. */
  private def capturing(capacity: Int = AdjustmentLog.DefaultCapacity): (AdjustmentLog, () => List[ILoggingEvent]) = {
    val logger   = LoggerFactory.getLogger(s"test.adjustments.${UUID.randomUUID()}").asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.DEBUG)
    logger.setAdditive(false)
    (new AdjustmentLog("fixturechat", capacity, logger), () => appender.list.asScala.toList)
  }

  private val topP        = Dropped(RequestOption.TopP, "top_p not supported for m")
  private val temperature = Adjusted(RequestOption.Temperature, "0.7", "1.0", "allowed range for m is 1.0-1.0")

  test("a client warns once per model and option") {
    val (log, events) = capturing()

    log.record("m", List(topP, temperature))
    log.record("m", List(topP, temperature))
    log.record("other", List(topP))

    events().map(e => (e.getLevel, e.getFormattedMessage)) shouldBe List(
      Level.WARN -> "fixturechat/m: dropped TopP: top_p not supported for m",
      Level.WARN -> "fixturechat/m: adjusted Temperature from 0.7 to 1.0: allowed range for m is 1.0-1.0",
      Level.WARN -> "fixturechat/other: dropped TopP: top_p not supported for m"
    )
  }

  test("two clients keep separate records") {
    val (first, firstEvents)   = capturing()
    val (second, secondEvents) = capturing()

    first.record("m", List(topP))
    second.record("m", List(topP))

    firstEvents().map(_.getLevel) shouldBe List(Level.WARN)
    secondEvents().map(_.getLevel) shouldBe List(Level.WARN)
  }

  test("once the record is full, one WARN says so and later first sightings go to DEBUG") {
    val (log, events) = capturing(capacity = 2)

    log.record("a", List(topP))
    log.record("b", List(topP))
    log.record("c", List(topP))
    log.record("d", List(topP))

    val logged = events()
    logged.map(_.getLevel) shouldBe List(Level.WARN, Level.WARN, Level.WARN, Level.DEBUG, Level.DEBUG)
    logged(2).getFormattedMessage should include("2 (model, option) pairs")
    logged(3).getFormattedMessage shouldBe "fixturechat/c: dropped TopP: top_p not supported for m"
  }

  test("nothing to report logs nothing") {
    val (log, events) = capturing()
    log.record("m", Nil)
    events() shouldBe empty
  }

  test("each kind of adjustment reads as a log line") {
    AdjustmentLog.describe(NotNeeded(RequestOption.PromptCache, "cached automatically")) shouldBe
      "did not send PromptCache: cached automatically"
  }
}
