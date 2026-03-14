package org.llm4s.samples.deploy

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.slf4j.LoggerFactory
import upickle.default._

/**
 * Minimal HTTP service for CI/CD staged deployments (dev → staging → prod).
 *
 * Exposes:
 * - GET /health — readiness; returns 200 with {"status":"up"}.
 * - GET /llm-check — lightweight LLM connectivity check; returns 200 with
 *   {"status":"ok","llm_configured":true} when provider is configured and a
 *   client can be obtained, or 200 with llm_configured:false when not configured
 *   (so smoke tests can pass without API keys).
 *
 * Used by the reusable deployment workflow (see .github/workflows/deploy-staged.yml)
 * and deploy/ Kustomize manifests. See issue #846.
 *
 * Run locally:
 * {{{
 * export LLM_MODEL=openai/gpt-4o   # optional, for /llm-check
 * export OPENAI_API_KEY=sk-...     # optional
 * sbt "samples/runMain org.llm4s.samples.deploy.SafeDeploymentService"
 * curl http://localhost:8080/health
 * curl http://localhost:8080/llm-check
 * }}}
 */
object SafeDeploymentService extends cask.MainRoutes {

  private val logger = LoggerFactory.getLogger(getClass)

  override def port: Int = sys.env.get("PORT").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(8080)
  override def host: String = "0.0.0.0"

  @cask.get("/health")
  def health(): cask.Response =
    cask.Response(
      write(ujson.Obj("status" -> "up")),
      statusCode = 200,
      headers = Seq("Content-Type" -> "application/json")
    )

  @cask.get("/llm-check")
  def llmCheck(): cask.Response = {
    val (status, code, llmConfigured) = Llm4sConfig.provider() match {
      case Right(providerCfg) =>
        LLMConnect.getClient(providerCfg) match {
          case Right(_) =>
            logger.debug("LLM client obtained for llm-check")
            ("ok", 200, true)
          case Left(_) =>
            ("degraded", 200, false)
        }
      case Left(_) =>
        ("ok", 200, false)
    }
    val body = write(ujson.Obj("status" -> status, "llm_configured" -> llmConfigured))
    cask.Response(body, statusCode = code, headers = Seq("Content-Type" -> "application/json"))
  }

  @cask.get("/")
  def root(): String =
    "LLM4S Safe Deployment Service - GET /health, GET /llm-check"

  initialize()
}
