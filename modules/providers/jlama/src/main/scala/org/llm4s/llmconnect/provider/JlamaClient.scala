package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.config.JlamaConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.{ AdjustmentLog, UnmappedOptions }
import org.llm4s.types.Result

import java.time.Instant
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import scala.util.{ Try, Using }

/**
 * A chat client for a model run inside this JVM by [[https://github.com/tjake/Jlama Jlama]]: no API key, no server.
 *
 * The model is downloaded (once, to `config.modelDir`) and loaded on the first call, not when the client is built, so
 * building one is cheap and the first call takes as long as the download and the load. One model is loaded per
 * client and stays in memory until `close()`; calls on one client run one at a time, each using every core Jlama is
 * given. Jlama keeps each call's attention cache until the client is closed, so a long-lived client grows.
 *
 * Text only: an image, audio or file part is refused before the model runs. No tool calling: a request with tools, or
 * a conversation holding tool calls or tool results, is refused. `responseFormat` is not read, so
 * `completeStructured` gets whatever text the model writes. Jlama is given the temperature and the token limit only;
 * `top_p`, the penalties, `responseFormat`, `reasoning` and `thinking` are reported `Dropped` on
 * `Completion.adjustments` when set (RFC 0008). An interrupted call stops after the token being generated
 * and returns `Left(CancelledError)`.
 */
@Experimental
final class JlamaClient private[provider] (
  config: JlamaConfig,
  loadEngine: JlamaConfig => Result[JlamaEngine],
  protected val metrics: MetricsCollector
) extends BaseLifecycleLLMClient:

  import JlamaClient.*

  protected def clientDescription: String = s"Jlama client for model ${config.model}"
  protected def providerName: String      = ProviderName
  protected def modelName: ModelName      = config.model

  private val lock                        = new ReentrantLock()
  private var engine: Option[JlamaEngine] = None // guarded by lock

  override def getContextWindow(): Int     = config.contextWindow
  override def getReserveCompletion(): Int = config.reserveCompletion

  // what the engine is not given goes on every Completion; this warns once per model and option
  private lazy val adjustmentLog = new AdjustmentLog(ProviderName)

  private def unsent(options: CompletionOptions): List[RequestAdjustment] = {
    val adjustments = unsentOptions(config.model.asString, options)
    adjustmentLog.record(config.model.asString, adjustments)
    adjustments
  }

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    completeWithMetrics(run(conversation, options, _ => ()).map(_._1.withAdjustments(unsent(options))))

  override def streamComplete(conversation: Conversation, options: CompletionOptions)(
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    completeWithMetrics {
      val id = UUID.randomUUID().toString
      run(conversation, options, token => onChunk(StreamedChunk(id = id, contentDelta = Some(token)))).map {
        (completion, finish) =>
          onChunk(StreamedChunk(id = id, contentDelta = None, finishReason = Some(finishReason(finish))))
          completion.withId(id).withAdjustments(unsent(options))
      }
    }

  private def run(
    conversation: Conversation,
    options: CompletionOptions,
    onToken: String => Unit
  ): Result[(Completion, JlamaFinish)] =
    for
      turns <- JlamaClient.turns(conversation, options)
      generation <- locked {
        currentEngine.flatMap { loaded =>
          Try(
            loaded.generate(
              turns,
              options.temperature.getOrElse(JlamaClient.DefaultTemperature).toFloat,
              options.maxTokens.getOrElse(config.reserveCompletion),
              stopWhenInterrupted(onToken)
            )
          ).toEither.left.map(e =>
            ProcessingError("jlama.generate", s"Jlama generation failed: ${e.getMessage}", Some(e))
          )
        }
      }
      completion <- toCompletion(generation)
    yield (completion, generation.finish)

  /**
   * Loads the model, as the first completion would, and generates nothing: there is no endpoint and no key to
   * check, so what can fail is the load. `Right(())` means the model is in memory and stays there for the calls that
   * follow; a model that cannot be downloaded or loaded is the `ProcessingError` a completion gets.
   *
   * Not cheap the first time: a model not yet in `config.modelDir` is downloaded, and loading takes as long as it
   * takes. Once the model is loaded it returns at once.
   */
  override def validate(): Result[Unit] =
    if Thread.currentThread().isInterrupted then Left(CancelledError(s"$ProviderName.validate"))
    else
      CancelledError.attempt(s"$ProviderName.validate")(
        validateNotClosed.flatMap(_ => locked(currentEngine)).map(_ => ())
      )

  /** The loaded model, loading it on first use. A failed load is not kept: the next call tries again. */
  private def currentEngine: Result[JlamaEngine] =
    engine match
      case Some(loaded) => Right(loaded)
      case None =>
        loadEngine(config).map { loaded =>
          engine = Some(loaded)
          loaded
        }

  /** Runs `body` holding the client's lock, waiting for it interruptibly. */
  private def locked[A](body: => Result[A]): Result[A] =
    lock.lockInterruptibly()
    Using.resource[AutoCloseable, Result[A]](() => lock.unlock())(_ => body)

  private def toCompletion(generation: JlamaGeneration): Result[Completion] =
    generation.finish match
      case JlamaFinish.Failed =>
        Left(ProcessingError("jlama.generate", s"Jlama reported an error generating with model ${config.model}"))
      case _ =>
        Right(
          Completion(
            id = UUID.randomUUID().toString,
            created = Instant.now(),
            model = config.model,
            message = AssistantMessage(generation.text),
            usage = Some(
              TokenUsage(
                promptTokens = generation.promptTokens,
                completionTokens = generation.generatedTokens,
                totalTokens = generation.promptTokens + generation.generatedTokens
              )
            ),
            finishReason = finishReason(generation.finish)
          )
        )

  override protected def releaseResources(): Unit =
    lock.lock()
    Using.resource[AutoCloseable, Unit](() => lock.unlock()) { _ =>
      engine.foreach(_.close())
      engine = None
    }

@Experimental
object JlamaClient:

  /** The temperature the engine is given when the caller set none: it has no default of its own. */
  private[provider] val DefaultTemperature: Double = 0.7

  /** What `options` sets that the engine is not given, each `Dropped` (RFC 0008): it takes a temperature and a limit. */
  private[provider] def unsentOptions(model: String, options: CompletionOptions): List[RequestAdjustment] =
    def dropped(set: Boolean, option: RequestOption) =
      Option.when(set)(RequestAdjustment.Dropped(option, s"Jlama is not given $option")).toList
    dropped(options.topP.isDefined, RequestOption.TopP) ++
      dropped(options.presencePenalty.isDefined, RequestOption.PresencePenalty) ++
      dropped(options.frequencyPenalty.isDefined, RequestOption.FrequencyPenalty) ++
      dropped(options.responseFormat.isDefined, RequestOption.ResponseFormat) ++
      UnmappedOptions.reasoningAndThinking(model, options) ++
      UnmappedOptions.serviceTier("Jlama", options)

  /** The provider id: the `provider` of this client's errors and metrics. */
  val ProviderName: String = "jlama"

  /**
   * A client for `config`. Nothing is downloaded or loaded until the first call.
   *
   * @param config  the model and where it is kept.
   * @param metrics receives per-call latency and token-usage events.
   */
  def apply(config: JlamaConfig, metrics: MetricsCollector = MetricsCollector.noop): Result[JlamaClient] =
    Right(new JlamaClient(config, JlamaEngine.load, metrics))

  /**
   * Thrown from the token callback to stop generation when the calling thread is interrupted. A `Throwable` rather than
   * an `Exception` because Jlama catches and logs an `Exception` thrown by the callback and carries on generating.
   */
  final private class Stopped extends Throwable("interrupted", null, false, false)

  private[provider] def stopWhenInterrupted(onToken: String => Unit): String => Unit = token =>
    if Thread.currentThread().isInterrupted then throw new Stopped
    onToken(token)

  private[provider] def finishReason(finish: JlamaFinish): FinishReason =
    finish match
      case JlamaFinish.Length => FinishReason.Length
      case _                  => FinishReason.Stop

  private val ToolsUnsupported: String =
    "llm4s-jlama does not support tool calling: send no tools, or use a provider that does (Ollama, a cloud provider)"

  /**
   * The conversation as the turns of a chat template. Text only, and no tools: anything else is refused before the
   * model is loaded.
   */
  private[provider] def turns(conversation: Conversation, options: CompletionOptions): Result[Seq[JlamaTurn]] =
    for
      _ <- Either.cond(options.tools.isEmpty, (), ValidationError("tools", ToolsUnsupported))
      _ <- PartEncoding.check(ProviderName, conversation.messages, PartEncoding.textOnly, PartEncoding.textOnly)
      turns <- conversation.messages.foldLeft[Result[Vector[JlamaTurn]]](Right(Vector.empty)) { (acc, message) =>
        acc.flatMap(done => turn(message).map(done :+ _))
      }
    yield turns

  private def turn(message: Message): Result[JlamaTurn] =
    message match
      case m: SystemMessage                           => Right(JlamaTurn(JlamaRole.System, m.content))
      case m: UserMessage                             => Right(JlamaTurn(JlamaRole.User, m.content))
      case m: AssistantMessage if m.toolCalls.isEmpty => Right(JlamaTurn(JlamaRole.Assistant, m.content))
      case _                                          => Left(ValidationError("messages", ToolsUnsupported): LLMError)
