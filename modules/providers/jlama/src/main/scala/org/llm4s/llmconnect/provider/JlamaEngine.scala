package org.llm4s.llmconnect.provider

import com.github.tjake.jlama.model.functions.Generator
import com.github.tjake.jlama.model.{ AbstractModel, ModelSupport }
import com.github.tjake.jlama.safetensors.prompt.PromptContext
import com.github.tjake.jlama.safetensors.{ DType, SafeTensorSupport }
import com.github.tjake.jlama.util.{ PhysicalCoreExecutor, ProgressReporter }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.config.JlamaConfig
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.collection.mutable
import scala.jdk.OptionConverters.*
import scala.util.Try

/** Who said a turn of the prompt [[JlamaEngine.generate]] is given. */
private[provider] enum JlamaRole:
  case System, User, Assistant

/** One turn of the conversation, as the model's chat template takes it. */
final private[provider] case class JlamaTurn(role: JlamaRole, text: String)

/** Why generation stopped: the model's end-of-turn token, the token limit, or a failure inside the engine. */
private[provider] enum JlamaFinish:
  case Stop, Length, Failed

/** What one call to [[JlamaEngine.generate]] produced. */
final private[provider] case class JlamaGeneration(
  text: String,
  finish: JlamaFinish,
  promptTokens: Int,
  generatedTokens: Int
)

/**
 * A loaded model, as [[JlamaClient]] drives it: the seam between the client and Jlama, which the tests stub so that
 * no test downloads or loads a model.
 */
private[provider] trait JlamaEngine extends AutoCloseable:

  /**
   * Generates a reply to `turns`, calling `onToken` with each piece of text as it is produced, on the calling thread.
   * `onToken` may throw to stop generation; the throw propagates out of this call.
   */
  def generate(turns: Seq[JlamaTurn], temperature: Float, maxNewTokens: Int, onToken: String => Unit): JlamaGeneration

private[provider] object JlamaEngine:

  private val logger = LoggerFactory.getLogger(classOf[JlamaEngine])

  /**
   * Downloads the model to `config.modelDir` unless it is already there, then loads it. A download is logged, with
   * each file's size; it happens once per model and directory.
   */
  def load(config: JlamaConfig): Result[JlamaEngine] =
    Try {
      config.threads.foreach(setThreads)
      val (owner, slashName) = config.model.span(_ != '/')
      val dir                = config.modelDir.toAbsolutePath.toString
      val local              = SafeTensorSupport.constructLocalModelPath(dir, owner, slashName.drop(1))
      if SafeTensorSupport.isModelLocal(local) then logger.info("Loading Jlama model {} from {}", config.model, local)
      else
        logger.info(
          "Downloading Jlama model {} from Hugging Face to {}. This happens once; later runs load it from there.",
          config.model,
          local
        )
      val modelFile = SafeTensorSupport.maybeDownloadModel(dir, config.model, downloadReporter)
      new Loaded(ModelSupport.loadModel(modelFile, DType.F32, DType.I8)): JlamaEngine
    }.toEither.left.map(e =>
      ProcessingError(
        "jlama.load",
        s"could not load Jlama model ${config.model} from ${config.modelDir}: ${e.getMessage}",
        Some(e)
      )
    )

  /** Jlama's thread pool belongs to the JVM and is fixed once it has started, so a later model cannot change it. */
  private def setThreads(threads: Int): Unit =
    Try(PhysicalCoreExecutor.overrideThreadCount(threads)).failed.foreach(e =>
      logger.warn("Jlama threads = {} not applied: Jlama's thread pool has already started ({})", threads, e.toString)
    )

  /** Logs each file once, when its download starts, with its size. */
  private def downloadReporter: ProgressReporter =
    val started = mutable.Set.empty[String]
    (file: String, _: Long, total: Long) =>
      if started.add(file) then logger.info("Downloading {} ({} MB)", file, total / (1024 * 1024))

  final private class Loaded(model: AbstractModel) extends JlamaEngine:

    def generate(
      turns: Seq[JlamaTurn],
      temperature: Float,
      maxNewTokens: Int,
      onToken: String => Unit
    ): JlamaGeneration =
      val context = prompt(turns)
      // Jlama's limit counts the prompt's tokens as well as the reply's.
      val limit    = model.encodePrompt(context).length + maxNewTokens
      val response = model.generate(UUID.randomUUID(), context, temperature, limit, (token, _) => onToken(token))
      JlamaGeneration(
        text = response.responseText,
        finish = response.finishReason match
          case Generator.FinishReason.MAX_TOKENS => JlamaFinish.Length
          case Generator.FinishReason.ERROR      => JlamaFinish.Failed
          case _                                 => JlamaFinish.Stop
        ,
        promptTokens = response.promptTokens,
        generatedTokens = response.generatedTokens
      )

    /** The model's own chat template when it has one; else the turns' text, one paragraph each. */
    private def prompt(turns: Seq[JlamaTurn]): PromptContext =
      model.promptSupport().toScala match
        case Some(support) =>
          turns
            .foldLeft(support.builder()) { (builder, turn) =>
              turn.role match
                case JlamaRole.System    => builder.addSystemMessage(turn.text)
                case JlamaRole.User      => builder.addUserMessage(turn.text)
                case JlamaRole.Assistant => builder.addAssistantMessage(turn.text)
            }
            .build()
        case None => PromptContext.of(turns.map(_.text).mkString("\n\n"))

    def close(): Unit = model.close()
