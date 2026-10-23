// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.deploy

import io.undertow.Undertow
import org.slf4j.LoggerFactory
import pureconfig.ConfigSource

import scala.util.{ Failure, Success, Try }

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
    // the application's edge: the one read of the process's configuration, injected into every loader here
    val source = ConfigSource.default
    val opened = for {
      config  <- DeployServiceConfig.load(source)
      agent   <- AgentServiceConfig.load(source)
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
