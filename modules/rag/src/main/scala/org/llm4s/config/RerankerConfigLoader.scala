// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.reranker.{ CohereReranker, RerankProviderConfig, RerankerFactory }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import pureconfig.{ ConfigReader => PureConfigReader, ConfigSource }

/**
 * Loads the reranker selected under `llm4s.rerank` into a [[org.llm4s.reranker.RerankProviderConfig]].
 *
 * `llm4s-rag`'s `reference.conf` binds `RERANK_PROVIDER`, `COHERE_RERANK_BASE_URL` and
 * `COHERE_RERANK_MODEL` there. The Cohere key is `llm4s.rerank.cohere.apiKey` when that is set,
 * and otherwise Cohere's shared `llm4s.credentials.cohere.apiKey`, bound to `COHERE_API_KEY` -
 * the key the Cohere chat provider uses too, so one variable serves both.
 *
 * {{{
 * for
 *   reranker <- RerankerConfigLoader.default()
 *   rag      <- RAG.build(config, resolveRerankerConfig = () => Right(reranker))
 * yield rag
 * }}}
 *
 * Only Cohere is configured here. `llm` reranking needs an `LLMClient`, which config cannot
 * supply: select it in code with `RAGConfig.withLLMReranking` or `RerankerFactory.llm`.
 */
object RerankerConfigLoader {

  private val CohereId: ProviderId = ProviderId("cohere")
  private val CohereSection        = "llm4s.rerank.cohere"

  /**
   * The variable this module's `reference.conf` binds to `llm4s.credentials.cohere.apiKey`, named
   * in the missing-key error. `llm4s-openai-compatible` binds the same one for Cohere chat.
   */
  val COHERE_API_KEY: String = "COHERE_API_KEY"

  final private case class CohereSettings(apiKey: Option[String], baseUrl: Option[String], model: Option[String])

  implicit private val cohereReader: PureConfigReader[CohereSettings] =
    PureConfigReader.forProduct3("apiKey", "baseUrl", "model")(CohereSettings.apply)

  /**
   * Reads `llm4s.rerank` from `source`.
   *
   * @return `None` when no reranker is selected (`provider` unset, empty or `none`), the Cohere
   *         config when `provider = cohere`, or a [[org.llm4s.error.ConfigurationError]] for a
   *         provider config cannot build or a Cohere key set in neither place.
   */
  def load(source: ConfigSource): Result[Option[RerankProviderConfig]] =
    readProvider(source).flatMap {
      case None | Some(RerankerFactory.Backend.None) => Right(None)
      case Some(RerankerFactory.Backend.Cohere)      => loadCohere(source).map(Some(_))
      case Some(RerankerFactory.Backend.LLM) =>
        Left(
          ConfigurationError(
            "llm4s.rerank.provider = llm cannot be configured: LLM reranking needs an LLMClient. " +
              "Use RAGConfig.withLLMReranking or RerankerFactory.llm(client) instead."
          )
        )
    }

  /** [[load]] against the current environment: system properties, `application.conf` and every `reference.conf`. */
  def default(): Result[Option[RerankProviderConfig]] =
    load(ConfigSource.default)

  private def readProvider(source: ConfigSource): Result[Option[RerankerFactory.Backend]] = {
    val at = source.at("llm4s.rerank.provider")
    if (at.value().isLeft) Right(None)
    else
      at.load[String]
        .left
        .map(failures => ConfigurationError(s"Failed to read llm4s.rerank.provider: ${failures.prettyPrint()}"))
        .flatMap { raw =>
          RerankerFactory.Backend
            .fromString(raw)
            .map(Some(_))
            .toRight(
              ConfigurationError(s"Unknown llm4s.rerank.provider '$raw' (expected cohere or none)")
            )
        }
  }

  private def loadCohere(source: ConfigSource): Result[RerankProviderConfig] = {
    val at = source.at(CohereSection)
    val settings =
      if (at.value().isLeft) Right(CohereSettings(None, None, None))
      else
        at.load[CohereSettings]
          .left
          .map(failures => ConfigurationError(s"Failed to read $CohereSection: ${failures.prettyPrint()}"))

    for {
      cohere   <- settings
      resolved <- SharedCredentials.read(source).resolve(cohere.apiKey, s"$CohereSection.apiKey", CohereId)
      key <- resolved.toRight(
        ConfigurationError(
          "Missing Cohere reranker apiKey: " +
            SharedCredentials.missingKeyHint(Seq(COHERE_API_KEY), CohereId, CohereSection)
        )
      )
    } yield {
      SharedCredentials.logSource(CohereSection, key)
      RerankProviderConfig(
        baseUrl = nonEmpty(cohere.baseUrl).getOrElse(CohereReranker.DEFAULT_BASE_URL),
        apiKey = key.value,
        model = nonEmpty(cohere.model).getOrElse(CohereReranker.DEFAULT_MODEL)
      )
    }
  }

  private def nonEmpty(value: Option[String]): Option[String] = value.map(_.trim).filter(_.nonEmpty)
}
