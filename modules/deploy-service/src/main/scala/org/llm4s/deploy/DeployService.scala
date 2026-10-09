package org.llm4s.deploy

import org.slf4j.LoggerFactory

/**
 * The two endpoints a staged deployment gates on.
 *
 *  - `GET /health`: the process is up and serving. Always `200`; this is what the Kubernetes liveness and
 *    readiness probes and the pipeline's smoke test call.
 *  - `GET /llm-check`: is an LLM provider configured and can a client be built? `200` when it can, `503`
 *    when it cannot. A configuration check, not a connectivity check; see [[LlmCheck]].
 *
 * @param check what `/llm-check` runs; injected so a test (or a different deployment) can supply its own
 */
class DeployRoutes(check: () => LlmCheckOutcome) extends cask.Routes {

  @cask.get("/health")
  def health(): cask.Response[String] =
    json(200, ujson.Obj("status" -> "up"))

  @cask.get("/llm-check")
  def llmCheck(): cask.Response[String] = {
    val outcome = LlmCheck.guarded(check)
    json(outcome.httpStatus, outcome.json)
  }

  private def json(status: Int, body: ujson.Obj): cask.Response[String] =
    cask.Response(ujson.write(body), statusCode = status, headers = Seq("Content-Type" -> "application/json"))

  initialize()
}

/** The service: [[DeployRoutes]] on the configured host and port. */
final class DeployApp(config: DeployServiceConfig, check: () => LlmCheckOutcome) extends cask.Main {
  val allRoutes: Seq[cask.main.Routes] = Seq(new DeployRoutes(check))

  override def host: String = config.host
  override def port: Int    = config.port
}

/**
 * Entry point of the container image (`sbt deployService/Docker/publishLocal`).
 *
 * Exits with status 1 when `llm4s.deploy-service` is invalid, so a bad `PORT` fails the rollout instead
 * of starting a service that listens somewhere nobody probes.
 */
object DeployServiceMain {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit =
    DeployServiceConfig.load() match {
      case Right(config) =>
        logger.info(s"Starting the deploy service on ${config.host}:${config.port}")
        val llmCheck = LlmCheck.default()
        new DeployApp(config, () => llmCheck.run()).main(args)
      case Left(error) =>
        logger.error(s"Cannot start the deploy service: ${error.message}")
        sys.exit(1)
    }
}
