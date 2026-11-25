package org.llm4s.testkit

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.{ ContentFilteredError, NetworkError, ProcessingError, ServiceError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  Completion,
  CompletionOptions,
  Conversation,
  FinishReason,
  RequestAdjustment,
  RequestOption,
  StreamedChunk,
  UserMessage
}
import org.llm4s.types.Result
import org.scalactic.source.Position

import scala.util.Try

/** How `ProviderModuleChecks.assertModelCallContract` runs one [[ContractCase]] and judges its result. */
private[testkit] object ModelCallContractRun:

  private val conversation = Conversation(Seq(UserMessage("Hello")))

  /** Runs `send` on a client pointed at a server answering with `respond`, closing the client after. */
  def call(path: String, respond: HttpExchange => Unit, client: String => LLMClient)(
    send: (LLMClient, Conversation) => Result[Completion]
  )(using Position): Result[Completion] =
    var result = Option.empty[Result[Completion]]
    LocalProviderTestServer.withServer(path)(respond) { baseUrl =>
      val built = client(baseUrl)
      val sent  = Try(send(built, conversation))
      Try(built.close()): Unit
      result = Some(sent.fold(thrown => ProviderModuleChecks.failAt(s"the call threw $thrown"), identity))
    }
    result.getOrElse(ProviderModuleChecks.failAt("the call did not run"))

  /**
   * The penalties the unsupported-option check asks for: values no request carries by chance, each exact in binary,
   * so a body that holds one is a body the client put it in.
   */
  val PresencePenalty: Double  = 0.4375
  val FrequencyPenalty: Double = 0.3125

  /** The options of the unsupported-option check: both penalties set, nothing else. */
  val penaltyOptions: CompletionOptions =
    CompletionOptions().withPresencePenalty(PresencePenalty).withFrequencyPenalty(FrequencyPenalty)

  /**
   * Answers with `respond`, after recording the request's body in `bodies`: the request a client sent, for a check
   * that reads what it carried. The body is read whole before `respond` runs, so `respond` sees an exchange it can
   * only answer - which is all a recorded reply does.
   */
  def recording(respond: HttpExchange => Unit, bodies: java.util.Queue[String]): HttpExchange => Unit =
    exchange =>
      val body = Try(new String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
      bodies.add(body.getOrElse(""))
      respond(exchange)

  /**
   * Fails unless each penalty `penaltyOptions` asked for was either sent - one of `bodies`, the requests the client
   * made, carries its value - or reported on the `Completion`'s `adjustments` (RFC 0008): an option the provider or
   * model cannot honour is dropped with a report, never silently. A `Dropped` report for a value that was sent
   * fails too: the report would be false.
   */
  def expectPenalties(how: String, result: Result[Completion], bodies: Iterable[String])(using Position): Unit =
    def fail(detail: String): Nothing = ProviderModuleChecks.failAt(s"penalties, $how: $detail")
    val completion =
      result.fold(e => fail(s"expected a Right, got Left(${e.getClass.getSimpleName}: ${e.message})"), identity)
    val sentJson = bodies.flatMap(body => Try(ujson.read(body)).toOption).toList
    Seq(
      ("presencePenalty", RequestOption.PresencePenalty, PresencePenalty),
      ("frequencyPenalty", RequestOption.FrequencyPenalty, FrequencyPenalty)
    ).foreach { (name, option, value) =>
      val sent     = sentJson.exists(carries(_, value))
      val reported = completion.adjustments.filter(_.option == option)
      if !sent && reported.isEmpty then
        fail(
          s"CompletionOptions.$name was set to $value, the request does not carry it, and Completion.adjustments " +
            s"reports nothing for RequestOption.$option: the option was dropped silently. Send it, or report a " +
            "RequestAdjustment.Dropped saying why it cannot be honoured"
        )
      if sent && reported.exists(_.isInstanceOf[RequestAdjustment.Dropped]) then
        fail(s"CompletionOptions.$name is reported Dropped, but the request carries $value")
    }

  /** Whether `json` holds the number `value` anywhere. */
  private def carries(json: ujson.Value, value: Double): Boolean = json match
    case ujson.Num(n)   => n == value
    case ujson.Arr(vs)  => vs.exists(carries(_, value))
    case ujson.Obj(kvs) => kvs.values.exists(carries(_, value))
    case _              => false

  /** Fails unless `result`, from `how` (`complete` or `streamComplete`), is what `c` requires. */
  def expect(c: ContractCase, how: String, result: Result[Completion])(using Position): Unit =
    def fail(detail: String): Nothing = ProviderModuleChecks.failAt(s"$c, $how: $detail")
    (c, result) match
      case (ContractCase.ContentFiltered, Left(_: ContentFilteredError)) => ()
      case (ContractCase.ContentFiltered, other) => fail(s"expected Left(ContentFilteredError), got ${show(other)}")
      // RFC 0009 rule 1: a retryable NetworkError, or the ServiceError(502) watsonx and Perplexity already return
      case (ContractCase.StreamEndsEarly, Left(_: NetworkError))                            => ()
      case (ContractCase.StreamEndsEarly, Left(error: ServiceError)) if error.status == 502 => ()
      case (ContractCase.StreamEndsEarly, other) =>
        fail(s"expected Left(NetworkError) or Left(ServiceError) with status 502, got ${show(other)}")
      // RFC 0009 rule 2: the Left carries the provider's error
      case (ContractCase.ErrorEvent, Left(error)) =>
        if !error.message.contains(ContractCase.ProviderErrorMessage) then
          fail(s"expected the provider's error '${ContractCase.ProviderErrorMessage}', got ${show(result)}")
      case (ContractCase.ErrorEvent, other) => fail(s"expected a Left, got ${show(other)}")
      // RFC 0009, Part 3: a cut or broken call is the malformed-tool-call ProcessingError, never {} or dropped
      case (ContractCase.ToolCallCutByLimit | ContractCase.MalformedArguments, Left(_: ProcessingError)) => ()
      case (ContractCase.ToolCallCutByLimit | ContractCase.MalformedArguments, other) =>
        fail(s"expected Left(ProcessingError), the malformed tool call, got ${show(other)}")
      case (_, Left(error)) => fail(s"expected a Right, got Left(${error.getClass.getSimpleName}: ${error.message})")
      case (ContractCase.CleanText, Right(completion)) =>
        reason(completion, FinishReason.Stop, fail)
        if completion.content.isEmpty then fail("the text is empty")
        if calls(completion).nonEmpty then fail(s"expected no tool call, got ${calls(completion).map(_.name)}")
      case (ContractCase.TextCutByLimit, Right(completion)) =>
        reason(completion, FinishReason.Length, fail)
        if completion.content.isEmpty then fail("the partial text is empty")
      case (ContractCase.ToolCall, Right(completion)) =>
        reason(completion, FinishReason.ToolCalls, fail)
        if calls(completion).isEmpty then fail("expected a tool call, got none")
        calls(completion).filterNot(_.arguments.objOpt.isDefined).foreach { call =>
          fail(s"tool call '${call.name}' has arguments that are not a JSON object: ${call.arguments.render()}")
        }
      case (ContractCase.NoArgumentToolCall, Right(completion)) =>
        reason(completion, FinishReason.ToolCalls, fail)
        if calls(completion).isEmpty then fail("expected a tool call, got none")
        calls(completion).filterNot(_.arguments == ujson.Obj()).foreach { call =>
          fail(s"tool call '${call.name}' takes no arguments, expected {}, got ${call.arguments.render()}")
        }

  /** Fails unless the chunks a successful stream delivered add up to the `Completion` it returned. */
  def delivered(c: ContractCase, chunks: List[StreamedChunk], completion: Completion)(using Position): Unit =
    def fail(detail: String): Nothing = ProviderModuleChecks.failAt(s"$c, streamComplete: $detail")
    val text                          = chunks.flatMap(_.contentDelta).mkString
    if text != completion.content then
      fail(s"the chunks delivered ${text.length} characters of text, the Completion has ${completion.content.length}")
    val lastReason = chunks.flatMap(_.finishReason).lastOption
    if !lastReason.contains(completion.finishReason) then
      fail(s"the last finish reason a chunk carried is $lastReason, the Completion's is ${completion.finishReason}")
    val streamedIds  = chunks.flatMap(_.toolCallDelta).map(_.id).filter(_.asString.nonEmpty).distinct
    val completedIds = calls(completion).map(_.id).filter(_.asString.nonEmpty)
    if streamedIds.toSet != completedIds.toSet then
      fail(s"the chunks name tool calls $streamedIds, the Completion has $completedIds")

  private def reason(completion: Completion, expected: FinishReason, fail: String => Nothing): Unit =
    if completion.finishReason != expected then
      fail(s"expected FinishReason.$expected, got FinishReason.${completion.finishReason}")

  /** The tool calls, from the message when the `Completion` lists none itself. */
  private def calls(completion: Completion) =
    if completion.toolCalls.nonEmpty then completion.toolCalls else completion.message.toolCalls.toList

  private def show(result: Result[Completion]): String =
    result.fold(
      error => s"Left(${error.getClass.getSimpleName}: ${error.message})",
      c => s"Right(finishReason ${c.finishReason}, ${c.content.length} characters, ${calls(c).size} tool calls)"
    )
