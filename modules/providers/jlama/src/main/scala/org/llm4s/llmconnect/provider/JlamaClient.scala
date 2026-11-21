package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.config.JlamaConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.metrics.MetricsCollector
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
 * `completeStructured` gets whatever text the model writes. An interrupted call stops after the token being generated
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
  protected def modelName: String         = config.model

  private val lock                        = new ReentrantLock()
  private var engine: Option[JlamaEngine] = None // guarded by lock

  override def getContextWindow(): Int     = config.contextWindow
  override def getReserveCompletion(): Int = config.reserveCompletion

  override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    completeWithMetrics(run(conversation, options, _ => ()).map(_._1))

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    completeWithMetrics {
      val id = UUID.randomUUID().toString
      run(conversation, options, token => onChunk(StreamedChunk(id = id, content = Some(token)))).map {
        (completion, finish) =>
          onChunk(StreamedChunk(id = id, content = None, finishReason = Some(finishReason(finish))))
          completion.withId(id)
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
              options.temperature.toFloat,
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
            content = generation.text,
            model = config.model,
            message = AssistantMessage(generation.text),
            usage = Some(
              TokenUsage(
                promptTokens = generation.promptTokens,
                completionTokens = generation.generatedTokens,
                totalTokens = generation.promptTokens + generation.generatedTokens
              )
            )
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

  private[provider] def finishReason(finish: JlamaFinish): String =
    finish match
      case JlamaFinish.Length => "length"
      case _                  => "stop"

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
