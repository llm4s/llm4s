package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpServer
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** `CompletionOptions.serviceTier` as the Messages API's `service_tier` (#2726). */
class AnthropicServiceTierSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val reply =
    """{"id":"m","type":"message","role":"assistant","model":"x","content":[{"type":"text","text":"ok"}],
      |"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}""".stripMargin

  /** The request body `complete` sends for `options`, and the completion. */
  private def sent(options: CompletionOptions): (ujson.Value, Completion) = {
    val (body, completion) = exchange(options, stream = false)
    (body, completion.toOption.get)
  }

  /** The request body `complete`, or `streamComplete`, sends for `options`, and what it returned. */
  private def exchange(
    options: CompletionOptions,
    stream: Boolean
  ): (ujson.Value, org.llm4s.types.Result[Completion]) = {
    val seen   = new AtomicReference[String]()
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/messages",
      exchange => {
        seen.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val bytes = reply.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try {
      val config = AnthropicConfig(
        apiKey = Secret("test-key"),
        model = "claude-sonnet-4-6",
        baseUrl = s"http://localhost:${server.getAddress.getPort}",
        contextWindow = 200000,
        reserveCompletion = 4096
      )
      val client       = new AnthropicClient(config)
      val conversation = Conversation(Seq(UserMessage("Hi")))
      val completion =
        if (stream) client.streamComplete(conversation, options, _ => ()) else client.complete(conversation, options)
      (ujson.read(seen.get()), completion)
    } finally server.stop(0)
  }

  "serviceTier" should "send Auto as auto and Default as standard_only, reporting nothing" in {
    val (auto, autoCompletion) = sent(CompletionOptions().withServiceTier(ServiceTier.Auto))
    auto("service_tier").str shouldBe "auto"
    autoCompletion.adjustments shouldBe Nil
    sent(CompletionOptions().withServiceTier(ServiceTier.Default))._1("service_tier").str shouldBe "standard_only"
  }

  it should "send Priority as auto, which uses Priority Tier where there is a commitment, and report it" in {
    val (body, completion) = sent(CompletionOptions().withServiceTier(ServiceTier.Priority))
    body("service_tier").str shouldBe "auto"
    completion.adjustments.map(_.option) shouldBe List(RequestOption.ServiceTier)
    completion.adjustments.head shouldBe a[RequestAdjustment.Adjusted]
  }

  it should "send no tier for Flex, and report it dropped" in {
    val (body, completion) = sent(CompletionOptions().withServiceTier(ServiceTier.Flex))
    body.obj.keySet should not contain "service_tier"
    completion.adjustments shouldBe List(
      RequestAdjustment.Dropped(RequestOption.ServiceTier, "Anthropic has no flex tier")
    )
  }

  it should "send the tier on a stream too" in {
    exchange(CompletionOptions().withServiceTier(ServiceTier.Default), stream = true)._1("service_tier").str shouldBe
      "standard_only"
  }

  it should "send no tier without one" in {
    sent(CompletionOptions())._1.obj.keySet should not contain "service_tier"
  }
}
