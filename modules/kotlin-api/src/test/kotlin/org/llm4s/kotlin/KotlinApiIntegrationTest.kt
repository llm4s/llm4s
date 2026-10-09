package org.llm4s.kotlin

import com.sun.net.httpserver.HttpExchange
import com.typesafe.config.ConfigFactory
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.LlmResult
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.model.ModelRegistryConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.llm4s.javaapi.Llm4s as JLlm4s
import scala.collection.immutable.`Map$` as ScalaMap
import java.util.Optional

private val NoHeaders = ScalaMap.`MODULE$`

private const val RegistryResource = "llm4s.modelRegistry.resourcePath"
private const val RegistryFile = "llm4s.modelRegistry.filePath"
private const val RegistryUrl = "llm4s.modelRegistry.url"

/**
 * Integration tests for the Kotlin API over the real Scala stack:
 * [Llm4s] -> [LLMClientKt] / [AgentKt] -> `JLlmClient` / `JAgent` -> `LLMConnect` -> the
 * OpenAI-compatible client -> HTTP, against a loopback server in this JVM.
 *
 * Nothing here is mocked below the factory seam, and nothing leaves the machine: no API key, no
 * external network. Synchronisation uses latches and bounded waits, never sleeps.
 */
class KotlinApiIntegrationTest {

    private lateinit var server: HttpServer
    private lateinit var originalFactory: ClientFactory

    /** What the fake endpoint does with a chat-completions request. */
    private val handler = AtomicReference<(HttpExchange) -> Unit> { reply(it, 200, completion("unset")) }
    private val requests = AtomicInteger(0)
    private val lastBody = AtomicReference<String>("")

    /** The registry properties as they were before [setUp], restored in [tearDown]. */
    private val savedRegistryProperties = mutableMapOf<String, String?>()

    @BeforeTest
    fun setUp() {
        pinModelRegistryToBundledResource()

        server =HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            lastBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            handler.get()(exchange)
        }
        server.start()

        originalFactory = Llm4s.factory
        Llm4s.factory = EndpointFactory("http://127.0.0.1:${server.address.port}/v1")
    }

    @AfterTest
    fun tearDown() {
        Llm4s.factory = originalFactory
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        restoreModelRegistryProperties()
    }

    /**
     * `JLlm4s.createClient` loads `Llm4sConfig.modelRegistryService()`, whose source
     * (`llm4s.modelRegistry.*`) `reference.conf` binds to `LLM4S_MODEL_REGISTRY_RESOURCE`, `_FILE` and
     * `_URL`. With one of those set the suite would read an arbitrary file or fetch a URL before it
     * reaches the loopback endpoint. System properties beat those `${?ENV}` bindings, so pin the
     * bundled snapshot and blank the other two (a blank source counts as unset). Typesafe Config
     * caches system properties, hence the cache invalidation on the way in and out.
     */
    private fun pinModelRegistryToBundledResource() {
        val pinned = mapOf(
            RegistryResource to ModelRegistryConfig.DefaultResourcePath(),
            RegistryFile to "",
            RegistryUrl to "",
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

    @Test
    fun `complete returns the endpoint's text through the real stack`() = runBlocking<Unit> {
        handler.set { reply(it, 200, completion("four")) }

        Llm4s.createDefaultClient().use { client ->
            assertEquals("four", client.complete("What is 2 + 2?"))
        }

        assertEquals(1, requests.get())
        assertTrue(lastBody.get().contains("What is 2 + 2?"), "the query reaches the endpoint: ${lastBody.get()}")
    }

    @Test
    fun `streamComplete emits the response text as a cold flow, once per collection`() = runBlocking<Unit> {
        handler.set { reply(it, 200, completion("chunk")) }

        Llm4s.createDefaultClient().use { client ->
            val flow = client.streamComplete("go")
            assertEquals(0, requests.get(), "cold: nothing is sent until collected")

            // The concatenated text, not the chunk count: it stays true when real token streaming lands.
            assertEquals("chunk", flow.toList().joinToString(""))
            assertEquals("chunk", flow.toList().joinToString(""))
        }

        assertEquals(2, requests.get())
    }

    @Test
    fun `an agent run returns a result whose answer and last message are the endpoint's reply`() = runBlocking<Unit> {
        handler.set { reply(it, 200, completion("the answer")) }

        Llm4s.createDefaultClient().use { client ->
            val result = Llm4s.createAgent(client).run("a question")

            assertEquals(Optional.of("the answer"), result.answer())
            assertEquals("the answer", result.messages().last().content())
        }
    }

    @Test
    fun `an endpoint failure is thrown as LLMException, not returned as an Either`() = runBlocking<Unit> {
        handler.set { reply(it, 500, """{"error":{"message":"boom"}}""") }

        Llm4s.createDefaultClient().use { client ->
            assertFailsWith<LLMException> { client.complete("q") }
            assertFailsWith<LLMException> { client.streamComplete("q").toList() }
            assertFailsWith<LLMException> { Llm4s.createAgent(client).run("q") }
        }
    }

    @Test
    fun `cancelling a call that is blocked on the endpoint does not hang and aborts the request`() = runBlocking<Unit> {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clientAborted = CountDownLatch(1)
        handler.set { exchange ->
            reached.countDown()
            release.await(30, TimeUnit.SECONDS)
            // Stream a large body once released: a client that aborted the request makes a write fail (broken
            // pipe) within a few chunks, whereas one still reading it, a leaked request, takes all of it.
            exchange.responseHeaders.add("Content-Type", "application/json")
            val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
            try {
                exchange.sendResponseHeaders(200, 0)
                repeat(256) {
                    exchange.responseBody.write(chunk)
                    exchange.responseBody.flush()
                }
                exchange.close()
            } catch (e: java.io.IOException) {
                clientAborted.countDown()
            }
        }

        try {
            Llm4s.createDefaultClient().use { client ->
                val job = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
                    client.streamComplete("slow").toList()
                }
                assertTrue(reached.await(10, TimeUnit.SECONDS), "the request reached the endpoint")

                withTimeout(10_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)

                release.countDown()
                assertTrue(
                    clientAborted.await(10, TimeUnit.SECONDS),
                    "cancelling the call aborts the in-flight request: the endpoint's writes must fail once released",
                )
            }
        } finally {
            release.countDown()
        }
    }

    private class EndpointFactory(private val baseUrl: String) : ClientFactory {
        override fun createDefault(): LlmResult<JLlmClient> =
            JLlm4s.createClient(OpenAICompatibleConfig("test-model", baseUrl, scala.Option.apply("test-key"), 8192, 2048, NoHeaders.empty<String, String>(), true, ProviderTimeouts.default()))

        override fun createAgent(client: JLlmClient): JAgent = JLlm4s.createAgent(client)
    }

    private fun completion(content: String): String =
        """{"id":"c1","object":"chat.completion","created":0,"model":"test-model",""" +
            """"choices":[{"index":0,"message":{"role":"assistant","content":"$content"},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
