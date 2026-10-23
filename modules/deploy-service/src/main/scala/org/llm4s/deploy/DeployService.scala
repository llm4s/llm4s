package org.llm4s.deploy

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
