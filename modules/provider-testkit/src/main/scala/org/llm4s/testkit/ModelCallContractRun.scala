package org.llm4s.testkit

import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.ContentFilteredError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, Conversation, FinishReason, StreamedChunk, UserMessage }
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

  /** Fails unless `result`, from `how` (`complete` or `streamComplete`), is what `c` requires. */
  def expect(c: ContractCase, how: String, result: Result[Completion])(using Position): Unit =
    def fail(detail: String): Nothing = ProviderModuleChecks.failAt(s"$c, $how: $detail")
    (c, result) match
      case (ContractCase.ContentFiltered, Left(_: ContentFilteredError)) => ()
      case (ContractCase.ContentFiltered, other) => fail(s"expected Left(ContentFilteredError), got ${show(other)}")
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

  /** Fails unless the chunks a successful stream delivered add up to the `Completion` it returned. */
  def delivered(c: ContractCase, chunks: List[StreamedChunk], completion: Completion)(using Position): Unit =
    def fail(detail: String): Nothing = ProviderModuleChecks.failAt(s"$c, streamComplete: $detail")
    val text                          = chunks.flatMap(_.content).mkString
    if text != completion.content then
      fail(s"the chunks delivered ${text.length} characters of text, the Completion has ${completion.content.length}")
    val lastReason = chunks.flatMap(_.finishReason).lastOption
    if !lastReason.contains(completion.finishReason) then
      fail(s"the last finish reason a chunk carried is $lastReason, the Completion's is ${completion.finishReason}")
    val streamedIds  = chunks.flatMap(_.toolCall).map(_.id).filter(_.nonEmpty).distinct
    val completedIds = calls(completion).map(_.id).filter(_.nonEmpty)
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
      c => s"Right(finishReason ${c.finishReason}, ${c.content.length} characters)"
    )
