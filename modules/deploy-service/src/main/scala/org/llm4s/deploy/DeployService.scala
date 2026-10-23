package org.llm4s.deploy

import io.undertow.Undertow
import org.slf4j.LoggerFactory

import scala.util.{ Failure, Success, Try }

/**
 * The two endpoints a staged deployment gates on.
 *
 *  - `GET /health`: the process is up and serving. Always `200`; this is what the Kubernetes liveness probe and
 *    the pipeline's smoke test call. Readiness is the agent service's `GET /ready` ([[AgentRoutes]]).
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

/**
 * The service: [[DeployRoutes]] and any `routes` - the example agent service's [[AgentRoutes]] - on the configured
 * host and port.
 */
final class DeployApp(
  config: DeployServiceConfig,
  check: () => LlmCheckOutcome,
  routes: Seq[cask.main.Routes] = Nil
) extends cask.Main {
  val allRoutes: Seq[cask.main.Routes] = new DeployRoutes(check) +: routes

  override def host: String = config.host
  override def port: Int    = config.port
}

/**
 * Entry point of the container image (`sbt deployService/Docker/publishLocal`), and of a plain JVM process
 * (`java -cp <deployService's runtime classpath> org.llm4s.deploy.DeployServiceMain`), configured entirely by
 * `application.conf`, system properties and environment variables (design 4.60): `PORT`, `LLM4S_CHECKPOINTER`,
 * `LLM4S_CHECKPOINTER_POSTGRES_URL` / `_USER` / `_PASSWORD`, `LLM4S_RUNTIME_LABEL`, `LLM4S_WORKER_LABEL`,
 * `LLM4S_SERVICE_MODEL`, `LLM4S_SERVICE_TOOL_DELAY` and `LLM4S_SERVICE_METRICS`.
 *
 * It opens the store, starts the worker, then serves; on SIGTERM (a JVM shutdown hook) it drains the worker, stops
 * the server and closes the store. Exits with status 1 when the configuration is invalid or the store cannot be
 * opened, so a bad setting fails the rollout instead of starting a service that cannot run anything.
 */
object DeployServiceMain {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val opened = for {
      config  <- DeployServiceConfig.load()
      agent   <- AgentServiceConfig.load()
      runtime <- ServiceRuntime.open(agent)
    } yield (config, agent, runtime)
    opened match {
      case Right((config, agent, runtime)) =>
        val llmCheck = LlmCheck.default()
        val routes   = new AgentRoutes(runtime.service, agent, runtime.metricsText)
        val app      = new DeployApp(config, () => llmCheck.run(), Seq(routes))
        val server = Undertow
          .builder()
          .addHttpListener(config.port, config.host)
          .setHandler(app.defaultHandler)
          .build()
        // listen first: a port that cannot be bound stops the process before its worker takes any run
        Try(server.start()) match {
          case Failure(e) =>
            logger.error(s"Cannot listen on ${config.host}:${config.port}: ${e.getMessage}")
            runtime.close()
            sys.exit(1)
          case Success(_) =>
            Runtime.getRuntime.addShutdownHook(
              new Thread(
                () => {
                  logger.info("Stopping: draining the worker")
                  runtime.drain(): Unit
                  server.stop()
                  runtime.close()
                  logger.info("Stopped")
                },
                "llm4s-deploy-service-shutdown"
              )
            )
            runtime.start()
            logger.info(
              s"The deploy service is listening on ${config.host}:${config.port}: agent '${agent.agentId.value}', " +
                s"'${runtime.store.kind}' store, worker ${runtime.worker.holder}"
            )
        }
      case Left(error) =>
        logger.error(s"Cannot start the deploy service: ${error.message}")
        sys.exit(1)
    }
  }
}
