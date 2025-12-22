package org.llm4s.llmconnect.utils

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ClientStatusSpec extends AnyFunSuite with Matchers {

  test("ConnectionStatus.Connected represents healthy state") {
    val status: ConnectionStatus = ConnectionStatus.Connected
    status shouldBe ConnectionStatus.Connected
  }

  test("ConnectionStatus.Disconnected represents disconnected state") {
    val status: ConnectionStatus = ConnectionStatus.Disconnected
    status shouldBe ConnectionStatus.Disconnected
  }

  test("ConnectionStatus.Connecting represents in-progress connection") {
    val status: ConnectionStatus = ConnectionStatus.Connecting
    status shouldBe ConnectionStatus.Connecting
  }

  test("ConnectionStatus.Error captures error message and optional cause") {
    val cause  = new RuntimeException("Network failure")
    val status = ConnectionStatus.Error("Failed to connect", Some(cause))

    status.message shouldBe "Failed to connect"
    status.cause shouldBe Some(cause)
  }

  test("ConnectionStatus.Error works without cause") {
    val status = ConnectionStatus.Error("Unknown error")
    status.message shouldBe "Unknown error"
    status.cause shouldBe None
  }

  test("ProviderCapabilities captures provider features") {
    val caps = ProviderCapabilities(
      supportsStreaming = true,
      supportsToolCalls = true,
      supportsFunctionCalling = true,
      supportsVision = false,
      maxTokens = Some(4096),
      supportedModels = List("gpt-4", "gpt-3.5-turbo"),
      metadata = Map("version" -> "1.0")
    )

    caps.supportsStreaming shouldBe true
    caps.supportsToolCalls shouldBe true
    caps.supportsVision shouldBe false
    caps.maxTokens shouldBe Some(4096)
    caps.supportedModels should contain("gpt-4")
    caps.metadata("version") shouldBe "1.0"
  }

  test("ClientHealth captures full health status") {
    val now = Instant.now()
    val caps = ProviderCapabilities(
      supportsStreaming = true,
      supportsToolCalls = false,
      supportsFunctionCalling = false,
      supportsVision = false
    )

    val health = ClientHealth(
      status = ConnectionStatus.Connected,
      lastHealthCheck = now,
      responseTimeMs = Some(150L),
      errorCount = 0,
      capabilities = Some(caps)
    )

    health.status shouldBe ConnectionStatus.Connected
    health.lastHealthCheck shouldBe now
    health.responseTimeMs shouldBe Some(150L)
    health.errorCount shouldBe 0
    health.capabilities.isDefined shouldBe true
  }

  test("ClientHealth defaults work correctly") {
    val now    = Instant.now()
    val health = ClientHealth(status = ConnectionStatus.Disconnected, lastHealthCheck = now)

    health.responseTimeMs shouldBe None
    health.errorCount shouldBe 0
    health.capabilities shouldBe None
  }
}
