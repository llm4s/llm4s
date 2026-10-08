package org.llm4s.javaapi

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ APIError, CancelledError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.util.Using

/**
 * The threading facts that `docs/guide/java-threading-and-cancellation.md` states, one test per
 * statement: which thread runs a call, whether one client or agent is safe to share, whether many
 * virtual threads can be inside the facade at once, and what an interrupt does.
 *
 * Nothing here waits on the clock to prove a point. Concurrency is proved with a latch that opens
 * only when every call is in flight at the same time (so a facade that serialised its callers
 * would hold the latch shut and the test would fail by its bounded wait, not by a tight margin);
 * interruption is proved with a server that never answers. Every wait is bounded at
 * [[waitSeconds]], so a regression fails the test instead of hanging the build.
 *
 * The calls that go over HTTP use the real Ollama client against a local server, so the claims
 * hold for a real provider's request path and not only for a fake.
 */
class ThreadingModelSpec extends AnyFlatSpec with Matchers {

  private def waitSeconds: Long = 20L

  private def completion(text: String): Completion =
    Completion("id", 0L, text, "test-model", AssistantMessage(text))

  private def lastUser(conversation: Conversation): String =
    conversation.messages.collect { case u: UserMessage => u.content }.lastOption.getOrElse("")

  private def fake(onComplete: Conversation => Result[Completion]): LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      onComplete(conversation)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  /** Nanoseconds left until `deadline` (`System.nanoTime` based); zero or less once it has passed. */
  private def remainingNanos(deadline: Long): Long = deadline - System.nanoTime()

  /**
   * A client whose every call waits until `n` calls are in flight at once, then echoes its query.
   * All calls share one deadline of [[waitSeconds]], so when the calls cannot overlap (a facade
   * that serialised them) the first waits out the deadline and the rest fail at once: the test
   * fails after about [[waitSeconds]], not after `n` times that.
   */
  private def echoClientAfter(n: Int): JLlmClient = {
    val inFlight = new CountDownLatch(n)
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds)
    new JLlmClient(fake { conversation =>
      inFlight.countDown()
      if (remainingNanos(deadline) > 0 && inFlight.await(remainingNanos(deadline), TimeUnit.NANOSECONDS))
        Right(completion("echo:" + lastUser(conversation)))
      else Left(org.llm4s.error.ProcessingError("test", s"fewer than $n calls were in flight at once"))
    })
  }

  private val okBody =
    """{"model":"llama3","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"ok"},""" +
      """"done":true,"prompt_eval_count":1,"eval_count":1}"""

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  }

  /**
   * A local HTTP server (handlers on virtual threads). `release` is opened when the server closes,
   * so a handler that holds a request open is let go and the server can stop.
   */
  final private class TestServer(handler: (HttpExchange, CountDownLatch) => Unit) extends AutoCloseable {
    private val release  = new CountDownLatch(1)
    private val handlers = Executors.newVirtualThreadPerTaskExecutor()
    private val server   = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/", exchange => handler(exchange, release))
    server.setExecutor(handlers)
    server.start()

    def client(): JLlmClient =
      Llm4s
        .createClient(OllamaConfig("llama3", s"http://localhost:${server.getAddress.getPort}", 4096, 512))
        .get()

    override def close(): Unit = {
      release.countDown()
      server.stop(0)
      handlers.shutdownNow(): Unit
    }
  }

  /**
   * An executor that is stopped with `shutdownNow`, which returns at once. `Using.resource` on the
   * executor itself would wait for every task, and when a regression leaves calls stuck that wait
   * turns a failing test into a very long one.
   */
  final private class Pool(val executor: java.util.concurrent.ExecutorService) extends AutoCloseable {
    override def close(): Unit = executor.shutdownNow(): Unit
  }

  private def platformThread(body: Runnable): Thread = {
    val thread = new Thread(body)
    thread.setDaemon(true)
    thread.start()
    thread
  }

  private def virtualThread(body: Runnable): Thread = Thread.ofVirtual().start(body)

  /**
   * Runs `call` on a thread made by `start`, interrupts that thread once the server holds its
   * request, and returns the call's result and whether the thread's interrupt flag was still set
   * when the call returned.
   */
  private def interruptedWhileBlocked(start: Runnable => Thread): (LlmResult[String], Boolean) = {
    val entered = new CountDownLatch(1)
    val result  = new AtomicReference[LlmResult[String]]()
    val flagSet = new AtomicReference[java.lang.Boolean](java.lang.Boolean.FALSE)
    Using.resource(new TestServer((_, release) => { entered.countDown(); release.await() })) { server =>
      val client = server.client()
      val caller = start { () =>
        result.set(client.complete("hi"))
        flagSet.set(java.lang.Boolean.valueOf(Thread.currentThread().isInterrupted))
      }
      entered.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
      caller.interrupt()
      caller.join(waitSeconds * 1000)
      caller.isAlive shouldBe false
    }
    (result.get(), flagSet.get().booleanValue())
  }

  // ---- which thread runs a call ----

  "JLlmClient.complete" should "run the provider call on the calling thread, platform or virtual" in {
    val seen = new AtomicReference[Thread]()
    val client = new JLlmClient(fake { _ =>
      seen.set(Thread.currentThread()); Right(completion("x"))
    })

    client.complete("hi").get() shouldBe "x"
    (seen.get() should be).theSameInstanceAs(Thread.currentThread())

    val onVirtual = new AtomicReference[Thread]()
    val caller = virtualThread { () =>
      client.complete("hi").get()
      onVirtual.set(Thread.currentThread())
    }
    caller.join(waitSeconds * 1000)
    (seen.get() should be).theSameInstanceAs(onVirtual.get())
    seen.get().isVirtual shouldBe true
  }

  "JAgent.run" should "block the caller while the model call runs on a virtual daemon thread of the library" in {
    val seen = new AtomicReference[Thread]()
    val agent = Llm4s.createAgent(new JLlmClient(fake { _ =>
      seen.set(Thread.currentThread()); Right(completion("x"))
    }))
    val caller = Thread.currentThread()

    agent.run("hi").get().messages.last.content shouldBe "x"

    (seen.get() should not).be(theSameInstanceAs(caller))
    seen.get().isVirtual shouldBe true
    seen.get().isDaemon shouldBe true
  }

  // ---- sharing one client or agent between threads ----

  "one JLlmClient shared by 50 platform threads" should "give each thread the answer to its own query" in {
    val n      = 50
    val client = echoClientAfter(n)
    val answers = Using.resource(new Pool(Executors.newFixedThreadPool(n))) { pool =>
      val futures = (0 until n).map(i => pool.executor.submit(() => client.complete("q" + i).get()))
      futures.map(_.get(waitSeconds, TimeUnit.SECONDS))
    }
    answers shouldBe (0 until n).map(i => "echo:q" + i)
  }

  "one JAgent shared by 32 virtual threads" should "give each thread the answer to its own query" in {
    val n     = 32
    val agent = Llm4s.createAgent(echoClientAfter(n))
    val answers = Using.resource(new Pool(Executors.newVirtualThreadPerTaskExecutor())) { pool =>
      val futures =
        (0 until n).map(i => pool.executor.submit(() => agent.run("a" + i).get().messages.last.content))
      futures.map(_.get(waitSeconds, TimeUnit.SECONDS))
    }
    answers shouldBe (0 until n).map(i => "echo:a" + i)
  }

  // ---- virtual threads ----

  "a real provider client reached through JLlmClient" should
    "serve more virtual threads at once than there are cores, so a call does not hold a carrier thread" in {
      val n        = math.max(64, 4 * Runtime.getRuntime.availableProcessors())
      val arrived  = new CountDownLatch(n)
      val inside   = new AtomicInteger(0)
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds)
      val handler = (exchange: HttpExchange, _: CountDownLatch) => {
        arrived.countDown()
        // opens only when all n requests are on the server at once; one shared deadline, so a client
        // that serialised its calls fails the test after about waitSeconds, not n times that
        if (remainingNanos(deadline) > 0 && arrived.await(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
          inside.incrementAndGet()
          respond(exchange, 200, okBody)
        } else respond(exchange, 503, "{}")
      }
      Using.resource(new TestServer(handler)) { server =>
        val client = server.client()
        val outcomes = Using.resource(new Pool(Executors.newVirtualThreadPerTaskExecutor())) { pool =>
          val futures = (0 until n).map(_ => pool.executor.submit(() => client.complete("hi").isSuccess))
          futures.map(_.get(waitSeconds + 10, TimeUnit.SECONDS))
        }
        outcomes.forall(identity) shouldBe true
        inside.get() shouldBe n
      }
    }

  // ---- interruption and cancellation ----

  "interrupting a platform thread blocked in a provider call" should
    "return a CancelledError with the thread's interrupt flag still set" in {
      val (result, flagSet) = interruptedWhileBlocked(platformThread)
      result.isFailure shouldBe true
      result.getError().error shouldBe a[CancelledError]
      flagSet shouldBe true
    }

  "interrupting a virtual thread blocked in a provider call" should
    "return a CancelledError with the thread's interrupt flag still set" in {
      val (result, flagSet) = interruptedWhileBlocked(virtualThread)
      result.isFailure shouldBe true
      result.getError().error shouldBe a[CancelledError]
      flagSet shouldBe true
    }

  "Future.cancel(true) on an executor task around a blocked call" should
    "interrupt the provider call, which returns a CancelledError" in {
      val entered = new CountDownLatch(1)
      val done    = new CountDownLatch(1)
      val result  = new AtomicReference[LlmResult[String]]()
      Using.resource(new TestServer((_, release) => { entered.countDown(); release.await() })) { server =>
        val client = server.client()
        Using.resource(new Pool(Executors.newSingleThreadExecutor())) { pool =>
          val task = pool.executor.submit(new Runnable {
            override def run(): Unit = { result.set(client.complete("hi")); done.countDown() }
          })
          entered.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
          task.cancel(true) shouldBe true
          task.isCancelled shouldBe true
          done.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
        }
      }
      result.get().getError().error shouldBe a[CancelledError]
    }

  "a custom client that lets InterruptedException escape" should
    "have it propagate out of JLlmClient.complete, as its Scaladoc says" in {
      val client = new JLlmClient(fake(_ => throw new InterruptedException("stop")))
      an[InterruptedException] should be thrownBy client.complete("hi")
    }

  "the blocking facade methods" should
    "declare no checked exception, so Java rejects a catch of InterruptedException around them" in {
      val complete = classOf[JLlmClient].getMethod("complete", classOf[String])
      val inChat   = classOf[JLlmClient].getMethod("complete", classOf[Conversation])
      val run      = classOf[JAgent].getMethod("run", classOf[String])
      complete.getExceptionTypes shouldBe empty
      inChat.getExceptionTypes shouldBe empty
      run.getExceptionTypes shouldBe empty
    }

  "interrupting the thread that called JAgent.run" should
    "return a CancelledError while the run, and its model call, carry on" in {
      val entered         = new CountDownLatch(1)
      val release         = new CountDownLatch(1)
      val modelDone       = new CountDownLatch(1)
      val modelInterrupts = new AtomicInteger(0)
      val agent = Llm4s.createAgent(new JLlmClient(fake { _ =>
        entered.countDown()
        CancelledError.catchInterrupt(release.await(waitSeconds, TimeUnit.SECONDS)).left.foreach { _ =>
          modelInterrupts.incrementAndGet(): Unit
        }
        modelDone.countDown()
        Right(completion("done"))
      }))
      val result  = new AtomicReference[LlmResult[org.llm4s.agent.AgentResult]]()
      val flagSet = new AtomicReference[java.lang.Boolean](java.lang.Boolean.FALSE)

      val caller = platformThread { () =>
        result.set(agent.run("hi"))
        flagSet.set(java.lang.Boolean.valueOf(Thread.currentThread().isInterrupted))
      }
      entered.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
      caller.interrupt()
      caller.join(waitSeconds * 1000)

      caller.isAlive shouldBe false
      result.get().getError().error shouldBe a[CancelledError]
      flagSet.get().booleanValue() shouldBe true
      // the run did not stop: its model call is still waiting and was never interrupted
      modelDone.getCount shouldBe 1L
      modelInterrupts.get() shouldBe 0

      release.countDown()
      modelDone.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
      modelInterrupts.get() shouldBe 0
    }

  // ---- the guide's Java snippets ----

  "the guide's Java snippets" should "behave as the guide says" in {
    val ok = new JLlmClient(fake(_ => Right(completion("x"))))
    ThreadingGuideSnippets.onVirtualThread(ok) shouldBe "x"

    ThreadingGuideSnippets.wasCancelled(LlmResult.failure(CancelledError("test", None))) shouldBe true
    ThreadingGuideSnippets.wasCancelled(LlmResult.failure(APIError("test", "down"))) shouldBe false
    ThreadingGuideSnippets.wasCancelled(LlmResult.success("x")) shouldBe false

    ThreadingGuideSnippets.completeOrNull(ok) shouldBe "x"

    // an escaping InterruptedException is caught, and the thread's flag is restored (checked on a
    // thread of its own so the flag does not leak into other tests)
    val interrupting = new JLlmClient(fake(_ => throw new InterruptedException("stop")))
    val returned     = new AtomicReference[String]("not run")
    val flagSet      = new AtomicReference[java.lang.Boolean](java.lang.Boolean.FALSE)
    val thread = platformThread { () =>
      returned.set(ThreadingGuideSnippets.completeOrNull(interrupting))
      flagSet.set(java.lang.Boolean.valueOf(Thread.currentThread().isInterrupted))
    }
    thread.join(waitSeconds * 1000)
    returned.get() shouldBe null
    flagSet.get().booleanValue() shouldBe true
  }

  "LlmResult.toCompletableFuture" should "return a future that is already complete, so cancel has nothing to stop" in {
    val future = LlmResult.success("x").toCompletableFuture
    future.isDone shouldBe true
    future.cancel(true) shouldBe false
    future.isCancelled shouldBe false
    future.get() shouldBe "x"
  }
}
