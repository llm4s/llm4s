package org.llm4s.llmconnect.config

import org.llm4s.annotation.Experimental
import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.llm4s.types.Result

import java.nio.file.{ Path, Paths }

/**
 * Configuration for a model run in-process by Jlama: `provider = "jlama"`.
 *
 * Build one with [[JlamaConfig.fromValues]]; a `provider = "jlama"` section builds one for you.
 *
 * @param model             the Hugging Face model id, `owner/name`, e.g. `"tjake/Llama-3.2-1B-Instruct-JQ4"`.
 * @param modelDir          where models are downloaded to and loaded from: one directory per model below it.
 * @param threads           how many threads Jlama computes with; `None` leaves Jlama's default, the physical core
 *                          count. Jlama's thread pool is shared by the whole JVM, so the first model loaded sets it.
 * @param contextWindow     the tokens a prompt and its reply may take together.
 * @param reserveCompletion the tokens held back for the reply: the reply's length when a request sets no `maxTokens`.
 */
@Experimental
final case class JlamaConfig private (
  model: ModelName,
  modelDir: Path,
  threads: Option[Int],
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val providerId: ProviderId                = ProviderId("jlama")
  override def endpointUrl: Option[BaseUrl]           = None
  override def withModel(model: ModelName): JlamaConfig = copy(model = model)
  def withModelDir(modelDir: Path): JlamaConfig      = copy(modelDir = modelDir)
  def withThreads(threads: Int): JlamaConfig         = copy(threads = Some(threads))
  def withThreads(threads: Option[Int]): JlamaConfig = copy(threads = threads)

  // Jlama runs the model in this JVM: there is no request to time out and no connection to route. So these
  // setters return the config unchanged, and a `provider = "jlama"` section that sets `timeouts` or an `http`
  // block of its own is refused by the loader instead of being ignored.

  /** Always [[ProviderTimeouts.default]]: inference runs in-process, with no request to time out. */
  override def timeouts: ProviderTimeouts = ProviderTimeouts.default

  /** Returns this config: there are no timeouts to set. */
  override def withTimeouts(timeouts: ProviderTimeouts): JlamaConfig = this

  /** Always the default: inference makes no HTTP call. */
  override def network: org.llm4s.http.HttpNetworkSettings = org.llm4s.http.HttpNetworkSettings.default

  /** Returns this config: there is no HTTP call to route. */
  override def withNetwork(network: org.llm4s.http.HttpNetworkSettings): JlamaConfig = this

  /** Always `None`: this provider authenticates without an API key. */
  override def apiKeyProvider: Option[org.llm4s.llmconnect.auth.ApiKeyProvider] = None

  /** Always a `ConfigurationError`: this provider takes no API key, so there is none to supply per request. */
  override def withApiKeyProvider(provider: org.llm4s.llmconnect.auth.ApiKeyProvider): Result[Nothing] =
    ProviderConfig.noApiKeyProvider(providerId)

@Experimental
object JlamaConfig:

  /** The context window assumed for every model: Jlama's KV cache grows with it, so it is kept small. */
  val DEFAULT_CONTEXT_WINDOW: Int = 4096

  /** The reply's length when a request sets no `maxTokens`. */
  val DEFAULT_RESERVE_COMPLETION: Int = 512

  /** `~/.cache/llm4s/jlama`: where models go when a section sets no `modelDir`. */
  def defaultModelDir: Path =
    Paths.get(Option(System.getProperty("user.home")).getOrElse("."), ".cache", "llm4s", "jlama")

  /**
   * Builds a [[JlamaConfig]]. The `model` is trimmed and must be a Hugging Face id, `owner/name`; `threads`, when
   * given, must be positive. Anything else is a `ConfigurationError`.
   */
  def fromValues(
    model: ModelName,
    modelDir: Path = defaultModelDir,
    threads: Option[Int] = None
  ): Result[JlamaConfig] =
    val name = model.asString.trim
    val isHuggingFaceId = name.split("/", -1) match
      case Array(owner, repo) => owner.nonEmpty && repo.nonEmpty
      case _                  => false
    for
      _ <- ProviderConfig.nonEmpty("jlama", "model", name)
      _ <- Either.cond(
        isHuggingFaceId,
        (),
        ConfigurationError(
          s"jlama model must be a Hugging Face model id, owner/name (e.g. tjake/Llama-3.2-1B-Instruct-JQ4), got '$name'",
          List("model")
        )
      )
      _ <- Either.cond(
        threads.forall(_ > 0),
        (),
        ConfigurationError("jlama threads must be a positive whole number", List("threads"))
      )
    yield new JlamaConfig(ModelName(name), modelDir, threads, DEFAULT_CONTEXT_WINDOW, DEFAULT_RESERVE_COMPLETION)
