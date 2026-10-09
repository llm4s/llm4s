package org.llm4s.deploy

import org.llm4s.config.Llm4sConfig
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.util.{ Try, Using }

/** What `GET /llm-check` found, and the HTTP status and JSON body that report it. */
sealed trait LlmCheckOutcome {

  /** `200` when a client for the configured provider can be built, `503` when it cannot. */
  def httpStatus: Int

  def json: ujson.Obj
}

object LlmCheckOutcome {

  /** The default provider is configured and a client for it was built (and closed again). */
  final case class Ready(provider: String) extends LlmCheckOutcome {
    val httpStatus: Int = 200
    def json: ujson.Obj = ujson.Obj("status" -> "ready", "provider" -> provider)
  }

  /** No usable default provider: nothing is configured, or the configuration is invalid. */
  final case class Unconfigured(errorType: String) extends LlmCheckOutcome {
    val httpStatus: Int = 503
    def json: ujson.Obj = ujson.Obj("status" -> "unconfigured", "error" -> errorType)
  }

  /** A provider is configured but a client for it could not be built. */
  final case class Degraded(provider: String, errorType: String) extends LlmCheckOutcome {
    val httpStatus: Int = 503
    def json: ujson.Obj = ujson.Obj("status" -> "degraded", "provider" -> provider, "error" -> errorType)
  }
}

/**
 * The check behind `GET /llm-check`: is an LLM provider configured, and can llm4s build a client for it?
 *
 * This is a **configuration check, not a connectivity check**. It loads the default provider and
 * constructs a client, and never calls the provider, so it costs nothing and needs no network. A
 * deployment that must prove the provider answers has to make a real call of its own.
 *
 * The response names the provider id and, on failure, the error *type* only. The full message goes
 * to the log: the endpoint is unauthenticated, and a message can name a configuration key or a URL.
 *
 * @param loadProvider loads the default provider's configuration
 * @param buildClient  builds a client from it
 */
final class LlmCheck(
  loadProvider: () => Result[ProviderConfig],
  buildClient: ProviderConfig => Result[LLMClient]
) {

  import LlmCheckOutcome._

  private val logger = LoggerFactory.getLogger(getClass)

  def run(): LlmCheckOutcome =
    loadProvider() match {
      case Left(error) =>
        logFailure("No usable default provider", error)
        Unconfigured(errorType(error))
      case Right(config) =>
        val provider = config.providerId.asString
        buildClient(config) match {
          case Left(error) =>
            logFailure(s"Could not build a client for provider '$provider'", error)
            Degraded(provider, errorType(error))
          case Right(client) =>
            // A client holds an HTTP client and its threads; one built per request must be closed.
            Using(client)(_ => ()).failed.foreach(e => logger.warn(s"Closing the '$provider' client failed", e))
            Ready(provider)
        }
    }

  private def errorType(error: LLMError): String = error.getClass.getSimpleName

  private def logFailure(what: String, error: LLMError): Unit =
    logger.warn(s"$what: ${error.message}")
}

object LlmCheck {

  /** The check against the process's own configuration: `llm4s.providers.provider` and its section. */
  def default(): LlmCheck =
    new LlmCheck(
      () => Llm4sConfig.defaultProvider(),
      config =>
        Llm4sConfig.modelRegistryService().flatMap { registry =>
          given ModelRegistryService = registry
          LLMConnect.getClient(config)
        }
    )

  /** Runs `check`, reporting an exception it throws as a degraded outcome rather than a 500. */
  private[deploy] def guarded(check: () => LlmCheckOutcome): LlmCheckOutcome =
    Try(check()).getOrElse(LlmCheckOutcome.Degraded("unknown", "UnexpectedError"))
}
