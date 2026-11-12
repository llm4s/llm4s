package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ ContextWindowResolver, JlamaConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor, ProviderFeatures }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.nio.file.{ Path, Paths }
import scala.util.Try

/**
 * Registration for a model run in-process by Jlama: `provider = "jlama"`. No API key and no server:
 *
 * {{{
 * local {
 *   provider = "jlama"
 *   model    = "tjake/Llama-3.2-1B-Instruct-JQ4"
 * }
 * }}}
 *
 * The model, a Hugging Face id, is downloaded on the first call to `modelDir` (default `~/.cache/llm4s/jlama`) and
 * loaded from there afterwards. `threads` sets how many threads Jlama computes with. Jlama needs the JDK's Vector API:
 * run the JVM with `--add-modules=jdk.incubator.vector --enable-preview` (without the first, Jlama falls back to
 * much slower scalar code). Streaming, no tool calling.
 */
@Experimental
object JlamaProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("jlama")

  /** The key naming the directory models are downloaded to. */
  val ModelDirKey: String = "modelDir"

  /** The key setting how many threads Jlama computes with. */
  val ThreadsKey: String = "threads"

  /** Streams; tool calling is not offered, since a small local model does not call tools reliably. */
  override def features: ProviderFeatures = ProviderFeatures(toolCalling = false)

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    extras = Seq(
      ProviderConfigKey.optional(
        ModelDirKey,
        "the directory models are downloaded to and loaded from",
        default = Some("~/.cache/llm4s/jlama")
      ),
      ProviderConfigKey.optional(
        ThreadsKey,
        "how many threads Jlama computes with, a positive whole number (default: the physical core count)"
      )
    )
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    (for
      modelDir <- parseDir(section.extra(ModelDirKey))
      threads  <- parseThreads(section.extra(ThreadsKey))
      config   <- JlamaConfig.fromValues(section.model.asString, modelDir, threads)
    yield config).left.map(ProviderConfig.inSection(providerName))

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor.expectConfig[JlamaConfig](id, config).flatMap(JlamaClient(_, options.metrics))

  /** The section's `modelDir`; a leading `~` stands for the user's home, as in the declared default. */
  private def parseDir(raw: Option[String]): Result[Path] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None => Right(JlamaConfig.defaultModelDir)
      case Some(text) =>
        val home = Option(System.getProperty("user.home")).getOrElse(".")
        val expanded =
          if text == "~" then home
          else if text.startsWith("~/") || text.startsWith("~\\") then home + text.substring(1)
          else text
        Try(Paths.get(expanded)).toOption
          .toRight(ConfigurationError(s"jlama $ModelDirKey must be a directory path, got '$text'", List(ModelDirKey)))

  private def parseThreads(raw: Option[String]): Result[Option[Int]] =
    raw.map(_.trim) match
      case None => Right(None)
      case Some(text) =>
        text.toIntOption
          .filter(_ > 0)
          .map(Some(_))
          .toRight(
            ConfigurationError(s"jlama $ThreadsKey must be a positive whole number, got '$text'", List(ThreadsKey))
          )
