package org.llm4s.kotlin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.LlmErrorKind
import org.llm4s.model.ModelRegistryConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.llm4s.javaapi.Llm4s as JLlm4s

/**
 * [Llm4s.wrapClient] over a client a Java builder made in code (#1850): no `application.conf`, no factory seam, the
 * real Ollama client talking HTTP to a loopback server that stands in for Ollama's `/api/chat`. Nothing leaves the
 * machine and no API key is needed.
 */
class WrapClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** The reply the stand-in sends: a status and a body. */
    private val reply = AtomicReference(200 to ollamaReply("unset"))
    private val requests = AtomicInteger(0)
    private val lastPath = AtomicReference("")
    private val lastBody = AtomicReference("")

    private val savedRegistryProperties = mutableMapOf<String, String?>()

    @BeforeTest
    fun setUp() {
        pinModelRegistryToBundledResource()
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            lastPath.set(exchange.requestURI.path)
            lastBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val (status, body) = reply.get()
            respond(exchange, status, body)
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        (server.executor as ExecutorService).shutdownNow()
        restoreModelRegistryProperties()
    }

    /** The model registry pinned to the bundled snapshot, as in [KotlinApiIntegrationTest]: no file read, no fetch. */
    private fun pinModelRegistryToBundledResource() {
        val pinned = mapOf(
            "llm4s.modelRegistry.resourcePath" to ModelRegistryConfig.DefaultResourcePath(),
            "llm4s.modelRegistry.filePath" to "",
            "llm4s.modelRegistry.url" to "",
        )
        pinned.forEach { (key, value) ->
            savedRegistryProperties[key] = System.getProperty(key)
            System.setProperty(key, value)
        }
        ConfigFactory.invalidateCaches()
    }

    private fun restoreModelRegistryProperties() {
        savedRegistryProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
        savedRegistryProperties.clear()
        ConfigFactory.invalidateCaches()
    }

    private fun ollamaClient(): JLlmClient =
        JLlm4s.ollama().model("llama3.2").baseUrl(baseUrl).build().get()

    @Test
    fun `wrapClient keeps the client it is given, and closing it closes that client`() {
        val jClient = mockk<JLlmClient>()
        every { jClient.close() } returns Unit

        val client = Llm4s.wrapClient(jClient)
        assertSame(jClient, client.underlying)

        client.close()
        verify(exactly = 1) { jClient.close() }
    }

    @Test
    fun `a client built with the Ollama builder completes a call through the suspend API`() = runBlocking<Unit> {
        reply.set(200 to ollamaReply("four"))

        Llm4s.wrapClient(ollamaClient()).use { client ->
            assertEquals("four", client.complete("What is 2 + 2?"))
        }

        assertEquals(1, requests.get())
        assertEquals("/api/chat", lastPath.get())
        assertTrue(lastBody.get().contains("\"llama3.2\""), "the builder's model reaches the request: ${lastBody.get()}")
        assertTrue(lastBody.get().contains("What is 2 + 2?"), "the query reaches the request: ${lastBody.get()}")
    }

    @Test
    fun `a wrapped builder client streams through a cold Flow`() = runBlocking<Unit> {
        reply.set(200 to ollamaReply("chunk"))

        Llm4s.wrapClient(ollamaClient()).use { client ->
            val flow = client.streamComplete("go")
            assertEquals(0, requests.get(), "cold: nothing is sent until collected")
            assertEquals("chunk", flow.toList().joinToString(""))
        }

        assertEquals(1, requests.get())
    }

    @Test
    fun `a wrapped builder client drives an agent turn`() = runBlocking<Unit> {
        reply.set(200 to ollamaReply("the answer"))

        Llm4s.wrapClient(ollamaClient()).use { client ->
            assertEquals(Optional.of("the answer"), Llm4s.createAgent(client).run("a question").answer())
        }
    }

    @Test
    fun `an endpoint failure from a wrapped builder client is thrown as LLMException`() = runBlocking<Unit> {
        reply.set(500 to """{"error":"boom"}""")

        Llm4s.wrapClient(ollamaClient()).use { client ->
            assertFailsWith<LLMException> { client.complete("q") }
            assertFailsWith<LLMException> { client.streamComplete("q").toList() }
        }
    }

    @Test
    fun `a builder with a required field missing fails as VALIDATION before there is a client to wrap`() {
        val result = JLlm4s.ollama().baseUrl(baseUrl).build()

        assertEquals(LlmErrorKind.VALIDATION, result.getError().kind)
        assertEquals(0, requests.get(), "build() sends no request")
    }

    private fun ollamaReply(content: String): String =
        """{"model":"llama3.2","created_at":"2026-01-01T00:00:00Z",""" +
            """"message":{"role":"assistant","content":"$content"},""" +
            """"done":true,"prompt_eval_count":1,"eval_count":1}"""

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
